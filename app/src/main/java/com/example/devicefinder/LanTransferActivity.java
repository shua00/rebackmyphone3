package com.example.devicefinder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Independent LAN file-transfer control surface; deliberately separate from ChatActivity. */
public final class LanTransferActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LanTransfer-UI");
        t.setDaemon(true);
        return t;
    });
    private LinearLayout offerList;
    private TextView status;
    private Button refreshButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshOffers(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_lan_transfer);
        TextView localId = findViewById(R.id.lanTransferLocalPeer);
        status = findViewById(R.id.lanTransferStatus);
        offerList = findViewById(R.id.lanTransferOfferList);
        Button pairing = findViewById(R.id.lanTransferPairing);
        refreshButton = findViewById(R.id.lanTransferRefresh);
        localId.setText("本机 Peer ID：" + TrackingService.getUniqueDeviceId(this));
        pairing.setOnClickListener(v -> showPairingDialog());
        refreshButton.setOnClickListener(v -> refreshOffers());
        IntentFilter filter = new IntentFilter("ACTION_LAN_TRANSFER_UPDATED");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(updates, filter);
            receiverRegistered = true;
        } catch (Exception failure) {
            status.setText("无法监听传输状态更新：" + failure.getClass().getSimpleName());
        }
        refreshOffers();
    }

    private void showPairingDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, 0);
        TextView identity = new TextView(this);
        identity.setText("本机设备 ID（提供给对端）：\n" + TrackingService.getUniqueDeviceId(this));
        form.addView(identity);
        EditText secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint("双方一致的共享密钥（至少 32 字节）");
        form.addView(secret);
        EditText peer = new EditText(this);
        peer.setSingleLine(true);
        peer.setHint("对端设备 ID（精确匹配）");
        form.addView(peer);
        new AlertDialog.Builder(this)
            .setTitle("LAN 文件传输安全配对")
            .setMessage("两台设备使用相同共享密钥，并分别填写对方设备 ID。只信任明确配置的对端；密钥由 AndroidKeyStore 加密保存。")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并启用", (dialog, which) -> {
                String value = secret.getText().toString();
                String peerId = peer.getText().toString().trim();
                secret.setText("");
                if (value.getBytes(StandardCharsets.UTF_8).length < 32 || peerId.isEmpty()) {
                    Toast.makeText(this, "共享密钥至少 32 字节，且必须填写对端 ID", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    LanTransferCredentials.provision(this, value, Collections.singleton(peerId));
                    Toast.makeText(this, "配对凭据已保存；监听器将按服务网络生命周期刷新", Toast.LENGTH_LONG).show();
                    refreshOffers();
                } catch (Exception failure) {
                    Toast.makeText(this, "配对保存失败：" + failure.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void refreshOffers() {
        if (isFinishing() || isDestroyed() || io.isShutdown()) return;
        try {
            io.execute(() -> {
                List<LanTransferStore.Transfer> offers = Collections.emptyList();
                String error = null;
                LanTransferStore store = null;
                try {
                    store = new LanTransferStore(getApplicationContext());
                    offers = store.loadIncomingOffers(TrackingService.getUniqueDeviceId(getApplicationContext()), 50);
                } catch (Exception failure) {
                    error = failure.getClass().getSimpleName();
                } finally {
                    if (store != null) store.closeStore();
                }
                final List<LanTransferStore.Transfer> result = offers;
                final String failure = error;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText(failure == null
                        ? (result.isEmpty() ? "没有待处理的文件请求" : "待处理文件请求：" + result.size())
                        : "读取文件请求失败：" + failure);
                    renderOffers(result);
                });
            });
        } catch (RejectedExecutionException stopped) {
            if (!isFinishing()) status.setText("文件界面正在关闭，请重新打开");
        }
    }

    private void renderOffers(List<LanTransferStore.Transfer> offers) {
        offerList.removeAllViews();
        for (LanTransferStore.Transfer transfer : offers) {
            TextView detail = new TextView(this);
            detail.setPadding(0, 12, 0, 4);
            detail.setText(transfer.offer.filename + "\n发送方：" + transfer.offer.senderPeerId
                + "\n大小：" + android.text.format.Formatter.formatFileSize(this, transfer.offer.sizeBytes)
                + "\n状态：" + transfer.state);
            offerList.addView(detail);
            LinearLayout actions = new LinearLayout(this);
            Button accept = new Button(this);
            accept.setText("接受");
            Button reject = new Button(this);
            reject.setText("拒绝");
            actions.addView(accept, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(reject, new LinearLayout.LayoutParams(0, -2, 1));
            offerList.addView(actions);
            accept.setOnClickListener(v -> submitTransferAction("ACTION_LAN_TRANSFER_ACCEPT", transfer.offer.transferId));
            reject.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("确定拒绝来自 " + transfer.offer.senderPeerId + " 的文件请求？")
                .setNegativeButton("取消", null)
                .setPositiveButton("拒绝", (dialog, which) ->
                    submitTransferAction("ACTION_LAN_TRANSFER_REJECT", transfer.offer.transferId))
                .show());
        }
    }

    private void submitTransferAction(String action, String transferId) {
        Intent intent = new Intent(this, TrackingService.class)
            .setAction(action).putExtra("transfer_id", transferId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            status.setText("已提交操作，等待服务端账本确认…");
        } catch (Exception failure) {
            Toast.makeText(this, "文件操作提交失败：" + failure.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshOffers();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(updates); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
