/*
 * ============================================================
 *  ChatActivity.java — 局域网聊天界面（LAN 对讲机 UI）
 * ------------------------------------------------------------
 *  构建批次 : 20261111-2000
 *  协议版本 : v2
 *  最后修改 : 2026-11-13 13:15（修复滚动监听 NPE 与 onDestroy 状态残留）
 * ------------------------------------------------------------
 *  改动记录（倒序，仅列最近 3 条，更早见 CHANGELOG.txt）:
 *    2026-11-13 13:15  修复滚动监听 NPE 与 onDestroy 状态残留
 *    2026-11-12 22:45  v2.0 通道可视化 + v2.0.1 架构优化 + 修复版本常量
 *    2026-11-11 19:45  注释规范统一（无逻辑改动）
 * ============================================================
 */

package com.example.devicefinder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends Activity {

    private TextView chatText;
    private ScrollView scrollView;
    private EditText inputMsg;
    private Button btnSend;
    private TextView tvNewMsg; // 【新增】新消息标签
    private Button btnTransferSettings, btnIncomingTransfers;
    private TextView tvTransferStatus;
    private final ExecutorService transferUiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LanTransfer-UI"); t.setDaemon(true); return t;
    });

    // [M1] volatile：TrackingService 的 UDP 接收线程读此字段决定是否 sendBroadcast，
    //      若无 volatile，跨线程可见性无保证，可能出现"手机收到消息但聊天页不刷新"
    public static volatile boolean isForeground = false;
    private boolean isAtBottom = true; // 【新增】是否在最底部

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver msgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHistory(true); // 收到新消息
        }
    };
    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshIncomingTransferCount(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        Intent svc = new Intent(this, TrackingService.class);
        svc.setAction("ACTION_RESET_NOTIFICATION");
        // [C1] 防御：Activity 尚未完全可见时，个别 ROM 对 startForegroundService 限制更严，
        //      抛异常会让 Activity 直接崩溃；改为记日志 + 继续，页面仍可打开查看历史
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            android.util.Log.e("ChatActivity", "启动 TrackingService 失败: " + e.getMessage());
        }

        chatText = findViewById(R.id.chatText);
        scrollView = findViewById(R.id.scrollView);
        inputMsg = findViewById(R.id.inputMsg);
        btnSend = findViewById(R.id.btnSend);
        tvNewMsg = findViewById(R.id.tvNewMsg); // 绑定新标签
        btnTransferSettings = findViewById(R.id.btnTransferSettings);
        btnIncomingTransfers = findViewById(R.id.btnIncomingTransfers);
        tvTransferStatus = findViewById(R.id.tvTransferStatus);
        btnTransferSettings.setOnClickListener(v -> showTransferPairingDialog());
        btnIncomingTransfers.setOnClickListener(v -> showIncomingTransfers());
        refreshIncomingTransferCount();

        // 【新增】监听滚动，判断是否滑到了底部
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            // ★ 修复：防止布局未加载完或子 View 被系统回收导致的 NPE 闪退
            if (scrollView.getChildCount() == 0) return;
            
            int diff = (scrollView.getChildAt(0).getBottom() - (scrollView.getHeight() + scrollView.getScrollY()));
            isAtBottom = diff <= 50; // 留50像素余量
            if (isAtBottom) {
                tvNewMsg.setVisibility(View.GONE);
            }
        });

        // 点击悬浮标签，滑到底部
        tvNewMsg.setOnClickListener(v -> {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            tvNewMsg.setVisibility(View.GONE);
        });

        refreshHistory(false); // 首次进入不弹标签

        btnSend.setOnClickListener(v -> sendMessage());

        inputMsg.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
               (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendMessage();
                return true;
            }
            return false;
        });

        // 已有的 API 判断保留，外层加 try/catch 兜底
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"));
            }
        } catch (Exception e) {
            android.util.Log.e("ChatActivity", "注册 UPDATE_CHAT_UI 广播失败: " + e.getMessage());
        }
        try {
            IntentFilter filter = new IntentFilter("ACTION_LAN_TRANSFER_UPDATED");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(transferReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(transferReceiver, filter);
        } catch (Exception e) { android.util.Log.e("ChatActivity", "注册文件传输广播失败", e); }
    }

    private void showTransferPairingDialog() {
        String localId = TrackingService.getUniqueDeviceId(this);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, 0);
        TextView local = new TextView(this); local.setText("本机设备 ID（提供给对端）：\\n" + localId); form.addView(local);
        EditText secret = new EditText(this); secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint("双方一致的共享密钥（至少 32 字节）"); form.addView(secret);
        EditText peer = new EditText(this); peer.setSingleLine(true); peer.setHint("对端设备 ID（精确匹配）"); form.addView(peer);
        new AlertDialog.Builder(this).setTitle("LAN 文件传输安全配对")
            .setMessage("两台设备使用相同共享密钥，并分别填写对方设备 ID。仅允许名单内设备连接。密钥由 AndroidKeyStore 加密保存。")
            .setView(form).setNegativeButton("取消", null)
            .setPositiveButton("保存并启用", (d, w) -> {
                String value = secret.getText().toString(); String peerId = peer.getText().toString().trim(); secret.setText("");
                try {
                    LanTransferCredentials.provision(this, value, Collections.singleton(peerId));
                    Toast.makeText(this, "配对已保存，局域网文件接收正在启用", Toast.LENGTH_LONG).show();
                    refreshIncomingTransferCount();
                } catch (Exception failure) {
                    Toast.makeText(this, "配对保存失败：" + failure.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void refreshIncomingTransferCount() {
        if (isFinishing() || transferUiExecutor.isShutdown()) return;
        transferUiExecutor.execute(() -> {
            int count = 0; String error = null; LanTransferStore store = null;
            try {
                store = new LanTransferStore(getApplicationContext());
                count = store.loadIncomingOffers(TrackingService.getUniqueDeviceId(this), 50).size();
            } catch (Exception failure) { error = failure.getClass().getSimpleName(); }
            finally { if (store != null) store.closeStore(); }
            final int n = count; final String e = error;
            runOnUiThread(() -> { if (isFinishing()) return;
                btnIncomingTransfers.setText("待接收文件 (" + n + ")");
                tvTransferStatus.setText(e == null ? (n == 0 ? "文件传输：暂无待处理请求" : "文件传输：有 " + n + " 个待处理请求") : "读取失败：" + e);
            });
        });
    }

    private void showIncomingTransfers() {
        if (transferUiExecutor.isShutdown()) return;
        transferUiExecutor.execute(() -> {
            List<LanTransferStore.Transfer> offers = Collections.emptyList(); String error = null;
            LanTransferStore store = null;
            try { store = new LanTransferStore(getApplicationContext());
                offers = store.loadIncomingOffers(TrackingService.getUniqueDeviceId(this), 50);
            } catch (Exception failure) { error = failure.getClass().getSimpleName(); }
            finally { if (store != null) store.closeStore(); }
            final List<LanTransferStore.Transfer> rows = offers; final String e = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (e != null) { Toast.makeText(this, "读取文件请求失败：" + e, Toast.LENGTH_LONG).show(); return; }
                if (rows.isEmpty()) { Toast.makeText(this, "当前没有待处理的文件请求", Toast.LENGTH_SHORT).show(); refreshIncomingTransferCount(); return; }
                LinearLayout items = new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (12 * getResources().getDisplayMetrics().density); items.setPadding(pad,pad,pad,pad);
                for (LanTransferStore.Transfer t : rows) {
                    TextView detail = new TextView(this);
                    detail.setText(t.offer.filename + "\\n发送方：" + t.offer.senderPeerId + "\\n大小：" + android.text.format.Formatter.formatFileSize(this,t.offer.sizeBytes) + "\\n状态：" + t.state);
                    items.addView(detail);
                    LinearLayout actions = new LinearLayout(this);
                    Button accept = new Button(this); accept.setText("接受");
                    Button reject = new Button(this); reject.setText("拒绝");
                    actions.addView(accept,new LinearLayout.LayoutParams(0,-2,1)); actions.addView(reject,new LinearLayout.LayoutParams(0,-2,1)); items.addView(actions);
                    accept.setOnClickListener(v -> { sendTransferAction("ACTION_LAN_TRANSFER_ACCEPT",t.offer.transferId); refreshIncomingTransferCount(); });
                    reject.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("确定拒绝接收该文件？").setNegativeButton("取消",null)
                        .setPositiveButton("拒绝",(d,w) -> { sendTransferAction("ACTION_LAN_TRANSFER_REJECT",t.offer.transferId); refreshIncomingTransferCount(); }).show());
                }
                ScrollView scroll = new ScrollView(this); scroll.addView(items);
                new AlertDialog.Builder(this).setTitle("待接收文件请求").setView(scroll).setPositiveButton("关闭",null).show();
            });
        });
    }

    private void sendTransferAction(String action, String transferId) {
        Intent intent = new Intent(this, TrackingService.class).setAction(action).putExtra("transfer_id",transferId);
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent); else startService(intent); }
        catch (Exception failure) { Toast.makeText(this,"文件操作提交失败："+failure.getClass().getSimpleName(),Toast.LENGTH_LONG).show(); }
    }

    private void sendMessage() {
        String msg = inputMsg.getText().toString().trim();
        if (!msg.isEmpty()) {
            inputMsg.setText("");
            // Restore focus after the Button click has fully completed. An immediate
            // requestFocus() can be overwritten by the Button's own focus dispatch.
            restoreChatInputFocus();

            // 【新增】拦截暗号清空指令
            if ("/clear".equalsIgnoreCase(msg)) {
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                // ★ 2026-11-07 加固：/clear 属低频操作，用 commit() 同步落盘，
                //   避免与 TrackingService 的 UDP 接收线程并发写入时被 apply() 的异步落盘覆盖。
                sp.edit().putString("chat_history", "").commit();
                Toast.makeText(this, "🧹 聊天记录已清空", Toast.LENGTH_SHORT).show();
                // 同步通知后台刷新（虽然 isForeground=true 时本 Activity 自己刷新即可，
                // 但显式广播可保证未来若服务端缓存了聊天快照也能一并清空）
                sendBroadcast(new Intent("UPDATE_CHAT_UI"));
                refreshHistory(false);
                return;
            }

            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String history = sp.getString("chat_history", "");
            String timeStr = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String newRecord = timeStr + " | [我] " + msg;

            // 本地追加到最后面
            String updatedHistory = history.isEmpty() ? newRecord : history + "\n" + newRecord;
            sp.edit().putString("chat_history", updatedHistory).apply();

            refreshHistory(false); // 自己发的，强制滑到底部

            Intent intent = new Intent(this, TrackingService.class);
            intent.setAction("ACTION_SEND_UDP_CHAT");
            intent.putExtra("message", msg);
            // [C2] 原 startService 在服务被 kill 时会抛 IllegalStateException（Android 8+）；
            //      先按原行为调用，失败再兜底用 startForegroundService 复活服务
            try {
                startService(intent);
            } catch (IllegalStateException e) {
                android.util.Log.w("ChatActivity", "startService 失败，尝试 startForegroundService: " + e.getMessage());
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        throw e; // 老版本不兜底，保持原状
                    }
                } catch (Exception e2) {
                    android.util.Log.e("ChatActivity", "启动 UDP 发送彻底失败: " + e2.getMessage());
                    Toast.makeText(this, "⚠️ 发送失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                android.util.Log.e("ChatActivity", "启动 UDP 发送异常: " + e.getMessage());
                Toast.makeText(this, "⚠️ 发送失败，请稍后重试", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Keep continuous typing usable after sending from either the button or IME action.
     * Post the focus request so it runs after the current click/focus dispatch; do not
     * force-show the keyboard when the user had intentionally dismissed it.
     */
    private void restoreChatInputFocus() {
        if (inputMsg == null) return;
        inputMsg.post(() -> {
            if (isFinishing()) return;
            inputMsg.requestFocus();
            inputMsg.setSelection(inputMsg.length());
        });
    }

    private void refreshHistory(boolean isNewMessage) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("chat_history", "暂无消息...");

        if (!chatText.getText().toString().equals(history)) {
            chatText.setText(history);
            chatText.setTextIsSelectable(true);

            if (!isNewMessage || isAtBottom) {
                // 如果是自己发的，或者当前已经在底部，就自动滑到底部
                scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                tvNewMsg.setVisibility(View.GONE);
            } else {
                // 如果收到新消息但人在上面看历史，就弹提示
                tvNewMsg.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        refreshHistory(false);
        refreshIncomingTransferCount();
        // Reclaim the editor after Activity/IME focus transitions so an already-visible
        // keyboard is immediately ready for typing without an extra tap on the field.
        restoreChatInputFocus();
    }

    // 删掉 onPause 里的 finishAndRemoveTask，保留正常的生命周期
    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
    }

    @Override
    public void onBackPressed() {
        // ★ 只有按返回键时，才彻底销毁并抹除任务栈卡片
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ★ 修复：双保险，防止极端情况下没走 onPause 导致后台一直发无用广播
        isForeground = false;
        
        // [B7] 防御：onCreate 中 registerReceiver 已包 try/catch，
        //      若注册失败或 Activity 生命周期异常未注册就走到 onDestroy，
        //      unregisterReceiver 会抛 IllegalArgumentException 崩溃
        try { unregisterReceiver(msgReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(transferReceiver); } catch (Exception ignored) {}
        transferUiExecutor.shutdownNow();
    }
}
