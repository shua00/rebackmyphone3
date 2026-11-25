package com.example.devicefinder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.text.TextUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int REQUEST_PICK_TRANSFER_FILE = 7401;
    private static final int REQUEST_SAVE_RECEIVED_FILE = 7402;
    private LanTransferStore.Transfer pendingSaveTransfer;

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
        Button send = findViewById(R.id.lanTransferSendFile);
        send.setOnClickListener(v -> pickFileForSending());
        Button cancelSend = findViewById(R.id.lanTransferCancelSend);
        cancelSend.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setMessage("只取消本机后续发送，不会远程删除对端已经收到的文件或数据。确定继续？")
                .setNegativeButton("返回", null)
                .setPositiveButton("取消本机发送", (dialog, which) -> {
                    LanTransferSenderService.cancelLocal(getApplicationContext());
                    status.setText("已请求取消本机发送；当前 TCP 分块会话结束后生效");
                }).show();
        });
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
        LanTransferSenderService.resumeIfPending(this);
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


    private void pickFileForSending() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, REQUEST_PICK_TRANSFER_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_RECEIVED_FILE) {
            LanTransferStore.Transfer transfer = pendingSaveTransfer;
            pendingSaveTransfer = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && transfer != null)
                copyCompletedFileToUserLocation(transfer, data.getData());
            return;
        }
        if (requestCode != REQUEST_PICK_TRANSFER_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int grants = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, grants);
        } catch (Exception ignored) {
            // The private snapshot below is independent of the provider after it completes.
        }
        showOutgoingDestinationDialog(uri);
    }

    private void requestSaveCompletedFile(LanTransferStore.Transfer transfer) {
        if (transfer == null || !"completed".equals(transfer.state)
                || TextUtils.isEmpty(transfer.publishedPath)) {
            Toast.makeText(this, "完成文件路径不可用", Toast.LENGTH_LONG).show();
            return;
        }
        pendingSaveTransfer = transfer;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(TextUtils.isEmpty(transfer.offer.mimeType) ? "application/octet-stream" : transfer.offer.mimeType);
        create.putExtra(Intent.EXTRA_TITLE, transfer.offer.filename);
        startActivityForResult(create, REQUEST_SAVE_RECEIVED_FILE);
    }

    private void copyCompletedFileToUserLocation(LanTransferStore.Transfer transfer, Uri destination) {
        try {
            io.execute(() -> {
                try {
                    File receivedRoot = new File(getFilesDir(), "lan-transfer-received").getCanonicalFile();
                    File source = new File(transfer.publishedPath).getCanonicalFile();
                    if (!source.isFile() || !receivedRoot.equals(source.getParentFile()))
                        throw new SecurityException("Published file is outside the private received directory");
                    try (InputStream input = new FileInputStream(source);
                         OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) throw new java.io.IOException("Unable to open destination document");
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        output.flush();
                    }
                    runOnUiThread(() -> Toast.makeText(this, "文件已保存到所选位置：" + transfer.offer.filename,
                            Toast.LENGTH_LONG).show());
                } catch (Exception failure) {
                    runOnUiThread(() -> Toast.makeText(this, "保存失败：" + failure.getClass().getSimpleName(),
                            Toast.LENGTH_LONG).show());
                }
            });
        } catch (RejectedExecutionException stopped) {
            Toast.makeText(this, "文件界面正在关闭，请重新打开", Toast.LENGTH_LONG).show();
        }
    }

    private void showOutgoingDestinationDialog(Uri uri) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, 0);
        EditText peer = new EditText(this); peer.setSingleLine(true); peer.setHint("已配对的对端 Peer ID"); form.addView(peer);
        EditText host = new EditText(this); host.setSingleLine(true); host.setHint("对端当前 LAN IP / 主机名"); form.addView(host);
        EditText port = new EditText(this); port.setSingleLine(true); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setHint("对端 TCP 端口"); form.addView(port);
        new AlertDialog.Builder(this).setTitle("发送到可信设备")
            .setMessage("必须填写已配对设备的精确 Peer ID，以及对端当前可达的 LAN 地址和监听端口。不会自动猜测或广播文件内容。")
            .setView(form).setNegativeButton("取消", null)
            .setPositiveButton("创建发送任务", (d, w) -> {
                String peerId = peer.getText().toString().trim();
                String remoteHost = host.getText().toString().trim();
                int remotePort;
                try { remotePort = Integer.parseInt(port.getText().toString().trim()); }
                catch (Exception invalid) { Toast.makeText(this, "端口无效", Toast.LENGTH_LONG).show(); return; }
                if (peerId.isEmpty() || remoteHost.isEmpty() || remotePort < 1 || remotePort > 65535
                        || remoteHost.contains("/") || remoteHost.contains(" ")) {
                    Toast.makeText(this, "请填写有效 Peer ID、主机地址和 1–65535 端口", Toast.LENGTH_LONG).show(); return;
                }
                status.setText("正在生成应用私有文件快照…");
                try {
                    io.execute(() -> {
                        try {
                            LanTransferCredentials.Value credentials = LanTransferCredentials.load(getApplicationContext());
                            if (credentials == null || !credentials.trustedPeerIds.contains(peerId))
                                throw new SecurityException("目标设备尚未配对或不在可信列表中");
                            LanTransferSnapshot.Result snapshot = LanTransferSnapshot.create(getApplicationContext(), uri);
                            String local = TrackingService.getUniqueDeviceId(getApplicationContext());
                            LanTransferStore.Offer offer = new LanTransferStore.Offer(snapshot.transferId, local,
                                peerId, snapshot.filename, snapshot.sizeBytes, snapshot.sha256,
                                System.currentTimeMillis(), snapshot.mimeType);
                            LanTransferSenderService.enqueue(getApplicationContext(), offer, remoteHost, remotePort,
                                snapshot.file.getCanonicalPath());
                            runOnUiThread(() -> status.setText("发送任务已持久化并交由后台服务执行：" + snapshot.filename));
                        } catch (Exception failure) {
                            runOnUiThread(() -> status.setText("创建发送任务失败：" + failure.getClass().getSimpleName()));
                        }
                    });
                } catch (RejectedExecutionException stopped) {
                    status.setText("文件界面正在关闭，请重新打开");
                }
            }).show();
    }

    private void refreshOffers() {
        if (isFinishing() || isDestroyed() || io.isShutdown()) return;
        try {
            io.execute(() -> {
                List<LanTransferStore.Transfer> transfers = Collections.emptyList();
                String error = null;
                LanTransferStore store = null;
                try {
                    store = new LanTransferStore(getApplicationContext());
                    transfers = store.loadRecentTransfers(
                            TrackingService.getUniqueDeviceId(getApplicationContext()), 50);
                } catch (Exception failure) {
                    error = failure.getClass().getSimpleName();
                } finally {
                    if (store != null) store.closeStore();
                }
                final List<LanTransferStore.Transfer> result = transfers;
                final String failure = error;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText(failure == null
                        ? (result.isEmpty() ? "暂无文件传输任务" : "最近文件传输任务：" + result.size())
                        : "读取文件传输任务失败：" + failure);
                    renderOffers(result);
                });
            });
        } catch (RejectedExecutionException stopped) {
            if (!isFinishing()) status.setText("文件界面正在关闭，请重新打开");
        }
    }

    private void renderOffers(List<LanTransferStore.Transfer> transfers) {
        offerList.removeAllViews();
        String localPeerId = TrackingService.getUniqueDeviceId(this);
        for (LanTransferStore.Transfer transfer : transfers) {
            boolean incoming = localPeerId.equals(transfer.offer.targetPeerId);
            TextView detail = new TextView(this);
            detail.setPadding(0, 12, 0, 4);
            detail.setText(transfer.offer.filename
                + (incoming ? "\\n接收自：" : "\\n发送给：")
                + (incoming ? transfer.offer.senderPeerId : transfer.offer.targetPeerId)
                + "\\n大小：" + android.text.format.Formatter.formatFileSize(this, transfer.offer.sizeBytes)
                + "\\n方向：" + (incoming ? "接收" : "发送")
                + "\\n状态：" + transfer.state
                + "\\n更新时间：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", transfer.updatedAtMs));
            offerList.addView(detail);
            if (incoming && "offered".equals(transfer.state)) {
                LinearLayout actions = new LinearLayout(this);
                Button accept = new Button(this);
                accept.setText("接受");
                Button reject = new Button(this);
                reject.setText("拒绝");
                actions.addView(accept, new LinearLayout.LayoutParams(0, -2, 1));
                actions.addView(reject, new LinearLayout.LayoutParams(0, -2, 1));
                offerList.addView(actions);
                accept.setOnClickListener(v -> submitTransferAction(
                        "ACTION_LAN_TRANSFER_ACCEPT", transfer.offer.transferId));
                reject.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("确定拒绝来自 " + transfer.offer.senderPeerId + " 的文件请求？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("拒绝", (dialog, which) ->
                        submitTransferAction("ACTION_LAN_TRANSFER_REJECT", transfer.offer.transferId))
                    .show());
            } else if (incoming && "completed".equals(transfer.state)
                    && !TextUtils.isEmpty(transfer.publishedPath)) {
                Button save = new Button(this);
                save.setText("保存到设备文件夹…");
                offerList.addView(save);
                save.setOnClickListener(v -> requestSaveCompletedFile(transfer));
            }
            View separator = new View(this);
            separator.setBackgroundColor(0x22334455);
            offerList.addView(separator, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * getResources().getDisplayMetrics().density)));
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
