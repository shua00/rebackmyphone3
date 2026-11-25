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
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatActivity extends Activity {

    private TextView chatText;
    private ScrollView scrollView;
    private EditText inputMsg;
    private Button btnSend;
    private Button btnTransfer;
    private Button btnChatSettings;
    private TextView tvNewMsg; // 【新增】新消息标签
    private TextView tvDeliveryStatus;
    private ExecutorService deliveryStatusExecutor;
    private final AtomicBoolean deliveryStatusRefreshQueued = new AtomicBoolean(false);
    private boolean deliveryStatusAwaitingNewOutbox = false;
    private boolean pendingComposerImeRestore = false;
    private static final long COMPOSER_IME_RESTORE_DELAY_MS = 80L;
    private long deliveryStatusAwaitingSinceMs = 0L;
    private int deliveryStatusEmptyPolls = 0;
    private static final long DELIVERY_STATUS_REFRESH_MS = 2_000L;
    private final Runnable deliveryStatusPoll = () -> refreshDeliveryStatusAsync();

    // [M1] volatile：TrackingService 的 UDP 接收线程读此字段决定是否 sendBroadcast，
    //      若无 volatile，跨线程可见性无保证，可能出现"手机收到消息但聊天页不刷新"
    public static volatile boolean isForeground = false;
    private boolean isAtBottom = true; // 【新增】是否在最底部
    private static final int CHAT_BOTTOM_THRESHOLD_PX = 50;
    // A keyboard-driven viewport resize is not user scrolling; preserve the prior bottom intent.
    private int lastScrollViewportHeight = -1;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver msgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHistory(true); // 收到新消息
        }
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
        btnTransfer = findViewById(R.id.btnTransfer);
        btnChatSettings = findViewById(R.id.btnChatSettings);
        updateTransferControls();
        btnTransfer.setOnClickListener(v -> {
            if (!LanTransferFeature.isEnabled(this)) { updateTransferControls(); return; }
            startActivity(new Intent(this, LanTransferActivity.class));
        });
        btnChatSettings.setOnClickListener(v -> showChatSettings());
        tvNewMsg = findViewById(R.id.tvNewMsg); // 绑定新标签
        tvDeliveryStatus = findViewById(R.id.tvDeliveryStatus);
        deliveryStatusExecutor = Executors.newSingleThreadExecutor();

        // 【新增】监听滚动，判断是否滑到了底部
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            // ★ 修复：防止布局未加载完或子 View 被系统回收导致的 NPE 闪退
            if (scrollView.getChildCount() == 0) return;
            
            int viewportHeight = scrollView.getHeight();
            if (lastScrollViewportHeight > 0 && viewportHeight != lastScrollViewportHeight) {
                // adjustResize changes the visible viewport when the IME opens/closes.
                // Do not mistake that geometry change for the user's scrolling away
                // from the latest message; the next real scroll event recalculates it.
                lastScrollViewportHeight = viewportHeight;
                return;
            }
            lastScrollViewportHeight = viewportHeight;
            int diff = (scrollView.getChildAt(0).getBottom()
                    - (viewportHeight + scrollView.getScrollY()));
            isAtBottom = diff <= CHAT_BOTTOM_THRESHOLD_PX;
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
    }


    private void updateTransferControls() {
        boolean enabled = LanTransferFeature.isEnabled(this);
        if (btnTransfer != null) btnTransfer.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void showChatSettings() {
        CheckBox fileTransfer = new CheckBox(this);
        fileTransfer.setText("启用局域网文件传输");
        fileTransfer.setTextSize(16f);
        fileTransfer.setPadding(24, 20, 24, 20);
        fileTransfer.setChecked(LanTransferFeature.isEnabled(this));
        TextView explanation = new TextView(this);
        explanation.setText("开启后，聊天输入区显示附件入口，并可进入独立文件管理页面。关闭后隐藏入口、停止本机接收监听，并在当前 TCP 会话结束后暂停发送任务；不会删除配对密钥、文件或历史任务。对讲消息通道不受影响。");
        explanation.setTextSize(13f);
        explanation.setPadding(24, 0, 24, 12);
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(fileTransfer);
        content.addView(explanation);
        new AlertDialog.Builder(this).setTitle("聊天与文件传输设置").setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    boolean enabled = fileTransfer.isChecked();
                    if (LanTransferFeature.setEnabled(this, enabled)) {
                        updateTransferControls();
                        Toast.makeText(this, enabled ? "文件传输已开启" : "文件传输已关闭", Toast.LENGTH_SHORT).show();
                    } else Toast.makeText(this, "设置保存失败，请重试", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void sendMessage() {
        String msg = inputMsg.getText().toString().trim();
        if (!msg.isEmpty()) {
            // Capture the IME state before clearing text or posting focus restoration.
            // A send continues typing only when the user had the keyboard open already.
            boolean keepKeyboardVisible = isComposerImeVisible();
            inputMsg.setText("");
            // Restore focus after the Button click has completed; never reopen a keyboard
            // that the user had deliberately dismissed before sending.
            restoreChatInputFocus(keepKeyboardVisible);

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

            // Start watching for this message before refreshHistory can enqueue a status read.
            // This prevents an older in-flight UI query from cancelling the new-message refresh.
            deliveryStatusAwaitingNewOutbox = true;
            deliveryStatusAwaitingSinceMs = System.currentTimeMillis();
            deliveryStatusEmptyPolls = 0;

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
            refreshHandler.removeCallbacks(deliveryStatusPoll);
            refreshHandler.postDelayed(deliveryStatusPoll, 500L);
        }
    }

    /**
     * Detect actual IME visibility rather than editor focus. Focus can remain on the
     * composer after the user hides the keyboard, so it is not a safe proxy.
     */
    private boolean isComposerImeVisible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                return insets.isVisible(android.view.WindowInsets.Type.ime());
            }
            // Root insets can briefly be unavailable during window transitions;
            // fall through to the same conservative visible-frame check as older APIs.
        }

        // Pre-Android 11 fallback for adjustResize devices. The threshold ignores
        // status/navigation bars and small layout changes.
        android.graphics.Rect visibleFrame = new android.graphics.Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        int obscuredHeight = screenHeight - visibleFrame.bottom;
        return obscuredHeight > (int) (150f * density);
    }

    /**
     * Keep the composer ready for consecutive messages.
     *
     * Sending is an explicit continuation of text entry, so restore both editor focus
     * and the IME after the send button/IME action has completed its own focus dispatch.
     * The delayed IME request avoids OEM keyboards losing the race to the button click.
     * Lifecycle-only focus restoration does not open a keyboard the user dismissed.
     */
    private void restoreChatInputFocus(boolean keepKeyboardVisible) {
        if (inputMsg == null) return;
        inputMsg.post(() -> {
            if (isFinishing() || inputMsg == null) return;
            inputMsg.requestFocus();
            inputMsg.setSelection(inputMsg.length());
            if (!keepKeyboardVisible) return;

            // Sending is an explicit continuation of typing. If the window temporarily
            // loses focus during the click/IME transition, retry once it regains focus.
            pendingComposerImeRestore = true;
            scheduleComposerImeRestore();
        });
    }

    private void scheduleComposerImeRestore() {
        if (!pendingComposerImeRestore || inputMsg == null || !hasWindowFocus()) return;
        inputMsg.postDelayed(() -> {
            if (!pendingComposerImeRestore || isFinishing() || inputMsg == null
                    || !hasWindowFocus()) return;
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(inputMsg, InputMethodManager.SHOW_IMPLICIT);
            }
            // Clear after the explicit send-triggered attempt. A later user dismissal
            // must remain respected and must not be undone by unrelated focus changes.
            pendingComposerImeRestore = false;
        }, COMPOSER_IME_RESTORE_DELAY_MS);
    }

    /**
     * Render progress from the same durable Outbox/target ledger used by retry.
     * SQLite reads stay off the main thread; polling runs only while this screen is visible.
     */
    private void refreshDeliveryStatusAsync() {
        if (!isForeground || isFinishing() || tvDeliveryStatus == null
                || deliveryStatusExecutor == null
                || !deliveryStatusRefreshQueued.compareAndSet(false, true)) return;
        final boolean awaitingNewOutbox = deliveryStatusAwaitingNewOutbox;
        final long awaitingSinceMs = deliveryStatusAwaitingSinceMs;
        final int emptyPollCount = deliveryStatusEmptyPolls;
        try {
            deliveryStatusExecutor.execute(() -> {
                String display = "";
                boolean hasPending = false;
                boolean queryFailed = false;
                boolean matchedAwaitedOutbox = false;
                try {
                    LanChatStore store = new LanChatStore(getApplicationContext());
                    try {
                        List<LanChatStore.DeliverySummary> summaries =
                                store.loadRecentDeliverySummaries(3);
                        if (!summaries.isEmpty()) {
                            StringBuilder text = new StringBuilder("发送状态");
                            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
                            for (LanChatStore.DeliverySummary item : summaries) {
                                if (awaitingNewOutbox && item.createdAtMs >= awaitingSinceMs) {
                                    matchedAwaitedOutbox = true;
                                }
                                String stateLabel;
                                if ("delivered".equals(item.state)) {
                                    stateLabel = "全部送达";
                                } else if ("waiting_peers".equals(item.state)) {
                                    stateLabel = "未发现收件设备";
                                } else if ("discovery_pending".equals(item.state)) {
                                    stateLabel = "正在发现收件设备";
                                    hasPending = true;
                                } else if ("retry_paused".equals(item.state)) {
                                    stateLabel = "重试已暂停，等待恢复";
                                } else if ("expired".equals(item.state)) {
                                    stateLabel = "已过期";
                                } else if ("pending".equals(item.state)) {
                                    stateLabel = item.ackedTargets > 0 ? "部分送达" : "等待设备确认";
                                    hasPending = true;
                                } else {
                                    stateLabel = "状态：" + item.state;
                                }

                                String body = item.body == null ? "" : item.body.replace('\n', ' ').trim();
                                if (body.length() > 18) body = body.substring(0, 18) + "…";
                                text.append('\n').append(timeFormat.format(new Date(item.createdAtMs)))
                                        .append("  ").append(body).append(" — ").append(stateLabel);
                                if (item.totalTargets > 0) {
                                    text.append(" (").append(item.ackedTargets).append('/')
                                            .append(item.totalTargets).append(')');
                                }
                            }
                            display = text.toString();
                        }
                    } finally {
                        store.closeStore();
                    }
                } catch (Exception e) {
                    queryFailed = true;
                    android.util.Log.w("ChatActivity", "读取 LAN 投递状态失败: " + e.getClass().getSimpleName());
                }

                final String resultText = queryFailed ? "发送状态暂不可用" : display;
                final boolean readFailed = queryFailed;
                final boolean foundAwaitedOutbox = matchedAwaitedOutbox;
                final boolean retryPending = hasPending
                        || (awaitingNewOutbox && !foundAwaitedOutbox
                            && !readFailed && emptyPollCount < 5);
                runOnUiThread(() -> {
                    deliveryStatusRefreshQueued.set(false);
                    if (isFinishing() || !isForeground || tvDeliveryStatus == null) return;
                    tvDeliveryStatus.setText(resultText);
                    tvDeliveryStatus.setVisibility(resultText.isEmpty() ? View.GONE : View.VISIBLE);
                    if (foundAwaitedOutbox) {
                        deliveryStatusAwaitingNewOutbox = false;
                        deliveryStatusEmptyPolls = 0;
                    } else if (awaitingNewOutbox && !readFailed) {
                        deliveryStatusEmptyPolls++;
                        if (deliveryStatusEmptyPolls >= 5) deliveryStatusAwaitingNewOutbox = false;
                    }
                    if (retryPending) {
                        refreshHandler.removeCallbacks(deliveryStatusPoll);
                        refreshHandler.postDelayed(deliveryStatusPoll, DELIVERY_STATUS_REFRESH_MS);
                    } else if (!deliveryStatusAwaitingNewOutbox) {
                        // A stale query must not cancel the refresh scheduled by a newer send.
                        refreshHandler.removeCallbacks(deliveryStatusPoll);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            deliveryStatusRefreshQueued.set(false);
        }
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
        refreshDeliveryStatusAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTransferControls();
        isForeground = true;
        // Treat changes accumulated while backgrounded like incoming messages:
        // preserve a history-reading position instead of unconditionally jumping to bottom.
        refreshHistory(true);
        // Reclaim the editor after Activity/IME focus transitions so an already-visible
        // keyboard is immediately ready for typing without an extra tap on the field.
        restoreChatInputFocus(false);
    }

    // 删掉 onPause 里的 finishAndRemoveTask，保留正常的生命周期
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingComposerImeRestore) {
            scheduleComposerImeRestore();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pendingComposerImeRestore = false;
        isForeground = false;
        refreshHandler.removeCallbacks(deliveryStatusPoll);
    }

    @Override
    public void onBackPressed() {
        // ★ 只有按返回键时，才彻底销毁并抹除任务栈卡片
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(deliveryStatusPoll);
        if (deliveryStatusExecutor != null) {
            deliveryStatusExecutor.shutdownNow();
            deliveryStatusExecutor = null;
        }
        // ★ 修复：双保险，防止极端情况下没走 onPause 导致后台一直发无用广播
        isForeground = false;
        
        // [B7] 防御：onCreate 中 registerReceiver 已包 try/catch，
        //      若注册失败或 Activity 生命周期异常未注册就走到 onDestroy，
        //      unregisterReceiver 会抛 IllegalArgumentException 崩溃
        try {
            unregisterReceiver(msgReceiver);
        } catch (Exception ignored) {}
    }
}
