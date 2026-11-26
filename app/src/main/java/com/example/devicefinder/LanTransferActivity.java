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
import java.security.MessageDigest;
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
import java.util.Set;
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
    private LanTransferPeerDirectory.Candidate selectedDestination;

    private String lastPairingPromptSessionId;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if ("ACTION_LAN_TRANSFER_PAIRING_REQUESTED".equals(action)) {
                showPairingPrompt(intent);
            } else if ("ACTION_LAN_TRANSFER_PAIRING_UPDATED".equals(action)) {
                String state = intent.getStringExtra("state");
                if (status != null && state != null)
                    status.setText("配对状态：" + state + " · " + intent.getStringExtra("remote_peer_id"));
            } else {
                refreshOffers();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!LanTransferFeature.isEnabled(this)) {
            Toast.makeText(this, "文件传输已关闭，请先在聊天设置中开启", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_lan_transfer);
        TextView localId = findViewById(R.id.lanTransferLocalPeer);
        status = findViewById(R.id.lanTransferStatus);
        offerList = findViewById(R.id.lanTransferOfferList);
        Button pairing = findViewById(R.id.lanTransferPairing);
        refreshButton = findViewById(R.id.lanTransferRefresh);
        localId.setText("本机 Peer ID：" + TrackingService.getUniqueDeviceId(this));
        pairing.setOnClickListener(v -> showPairingDialog(null));
        Button send = findViewById(R.id.lanTransferSendFile);
        send.setOnClickListener(v -> showDestinationPicker());
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
        filter.addAction("ACTION_LAN_TRANSFER_PEERS_UPDATED");
        filter.addAction("ACTION_LAN_TRANSFER_PAIRING_REQUESTED");
        filter.addAction("ACTION_LAN_TRANSFER_PAIRING_UPDATED");
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

    private void showPairingDialog(String suggestedPeerId) {
        if (!TextUtils.isEmpty(suggestedPeerId)) {
            LanTransferPeerDirectory.Candidate candidate = LanTransferPeerDirectory.findCurrent(
                    this, suggestedPeerId, System.currentTimeMillis());
            if (candidate == null || !candidate.isPairingConnectable()) {
                Toast.makeText(this, "设备发现记录已过期，请刷新设备列表", Toast.LENGTH_LONG).show();
                refreshPeerList();
                return;
            }
            startPairing(candidate);
            return;
        }
        List<LanTransferPeerDirectory.Candidate> candidates =
                LanTransferPeerDirectory.loadCurrent(this, System.currentTimeMillis());
        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("暂未发现可配对设备")
                    .setMessage("请确认对端已开启文件传输并处于同一可互通局域网，然后刷新设备列表。")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("清除全部配对", (d, w) -> confirmClearPairing())
                    .show();
            return;
        }
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            LanTransferPeerDirectory.Candidate item = candidates.get(i);
            labels[i] = item.peerId + " · " + item.host
                    + (LanTransferPeerDirectory.SOURCE_CHAT_HINT.equals(item.source)
                        ? " · 对讲发现提示" : " · 文件传输发现");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要安全配对的设备")
                .setItems(labels, (dialog, which) -> startPairing(candidates.get(which)))
                .setNeutralButton("清除全部配对", (dialog, which) -> confirmClearPairing())
                .setNegativeButton("取消", null)
                .show();
    }

    private void startPairing(LanTransferPeerDirectory.Candidate candidate) {
        if (candidate == null || !candidate.isPairingConnectable()) return;
        Intent intent = new Intent(this, TrackingService.class)
                .setAction("ACTION_LAN_TRANSFER_PAIRING_START")
                .putExtra("peer_id", candidate.peerId)
                .putExtra("peer_host", candidate.host);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            status.setText("正在与 " + candidate.peerId + " 建立安全配对握手…");
        } catch (Exception failure) {
            Toast.makeText(this, "无法启动配对：" + failure.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showPairingPrompt(Intent event) {
        if (isFinishing() || isDestroyed()) return;
        String sessionId = event.getStringExtra("session_id");
        String peerId = event.getStringExtra("remote_peer_id");
        String sas = event.getStringExtra("sas");
        String role = event.getStringExtra("role");
        if (TextUtils.isEmpty(sessionId) || TextUtils.isEmpty(peerId)
                || TextUtils.isEmpty(sas) || sessionId.equals(lastPairingPromptSessionId)) return;
        lastPairingPromptSessionId = sessionId;
        String roleText = "initiator".equals(role) ? "你发起的配对" : "收到对端配对请求";
        String message = roleText + "\n对端设备 ID：" + peerId
                + "\n双方设备应显示相同的六位安全码：\n\n" + sas
                + "\n\n请通过可信方式核对两台设备上的安全码。只有完全一致才确认。";
        new AlertDialog.Builder(this)
                .setTitle("LAN 文件传输安全配对")
                .setMessage(message)
                .setPositiveButton("安全码一致，确认", (dialog, which) ->
                        submitPairingDecision("ACTION_LAN_TRANSFER_PAIRING_CONFIRM",
                                sessionId, peerId, sas, role))
                .setNegativeButton("拒绝", (dialog, which) ->
                        submitPairingDecision("ACTION_LAN_TRANSFER_PAIRING_REJECT",
                                sessionId, peerId, sas, role))
                .setNeutralButton("取消本次配对", (dialog, which) ->
                        submitPairingDecision("ACTION_LAN_TRANSFER_PAIRING_CANCEL",
                                sessionId, peerId, sas, role))
                .setOnCancelListener(dialog -> {
                    lastPairingPromptSessionId = null;
                    submitPairingDecision("ACTION_LAN_TRANSFER_PAIRING_CANCEL",
                            sessionId, peerId, sas, role);
                })
                .show();
    }

    private void submitPairingDecision(String action, String sessionId,
            String peerId, String sas, String role) {
        Intent intent = new Intent(this, TrackingService.class).setAction(action)
                .putExtra("session_id", sessionId)
                .putExtra("remote_peer_id", peerId)
                .putExtra("sas", sas)
                .putExtra("role", role);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            status.setText("已提交配对决定，等待对端确认…");
        } catch (Exception failure) {
            Toast.makeText(this, "提交配对决定失败：" + failure.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClearPairing() {
        if (LanTransferSenderService.hasPendingJob(this)) {
            new AlertDialog.Builder(this)
                .setTitle("暂不能清除配对")
                .setMessage("当前仍有待处理的文件发送任务。请先完成或取消本机发送，再清除全部配对，避免活动任务继续使用旧凭据。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("清除全部文件传输配对")
            .setMessage("这会清除本机保存的共享密钥和全部可信设备名单，并关闭文件传输监听。不会删除已收发文件、传输历史，也不会擦除或停用对端设备。确定继续？")
            .setNegativeButton("保留配对", null)
            .setPositiveButton("清除全部配对", (dialog, which) -> {
                try {
                    LanTransferCredentials.clear(this);
                    status.setText("全部文件传输配对已清除；重新配对后才能发送或接收文件");
                    refreshOffers();
                } catch (Exception failure) {
                    status.setText("清除配对失败：" + failure.getClass().getSimpleName());
                }
            })
            .show();
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
        if (requestCode == REQUEST_PICK_TRANSFER_FILE && resultCode != RESULT_OK) {
            selectedDestination = null;
            return;
        }
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
        LanTransferPeerDirectory.Candidate destination = selectedDestination;
        selectedDestination = null;
        if (destination == null) {
            Toast.makeText(this, "请先从在线设备列表选择接收设备", Toast.LENGTH_LONG).show();
            return;
        }
        LanTransferPeerDirectory.Candidate fresh = LanTransferPeerDirectory.findCurrent(
                getApplicationContext(), destination.peerId, System.currentTimeMillis());
        if (fresh == null || !fresh.isConnectable()) {
            status.setText("目标设备端点已过期或暂不可连接，请刷新设备列表后重试");
            refreshPeerList();
            return;
        }
        createOutgoingTask(uri, fresh);
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
                    boolean destinationOpened = false;
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        long copied = 0;
                        try (InputStream input = new FileInputStream(source);
                             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                            if (output == null) throw new java.io.IOException("Unable to open destination document");
                            destinationOpened = true;
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                output.write(buffer, 0, count);
                                digest.update(buffer, 0, count);
                                copied += count;
                            }
                            output.flush();
                        }
                        StringBuilder actualHash = new StringBuilder();
                        for (byte value : digest.digest())
                            actualHash.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
                        if (copied != transfer.offer.sizeBytes || !transfer.offer.sha256.equals(actualHash.toString()))
                            throw new SecurityException("Completed file size or SHA-256 no longer matches its ledger");
                    } catch (Exception failure) {
                        if (destinationOpened) {
                            try { getContentResolver().delete(destination, null, null); }
                            catch (Exception ignored) {}
                        }
                        throw new RuntimeException(failure);
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

    private void showDestinationPicker() {
        try {
            LanTransferCredentials.Value credentials = LanTransferCredentials.load(this);
            if (credentials == null || credentials.trustedPeerIds.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("尚无可信设备")
                    .setMessage("请先在两台设备上完成配对。设备发现只用于显示候选设备，不会自动建立信任。")
                    .setPositiveButton("去配对", (d, w) -> showPairingDialog(null))
                    .setNegativeButton("取消", null).show();
                return;
            }
            List<LanTransferPeerDirectory.Candidate> candidates =
                    LanTransferPeerDirectory.loadCurrent(this, System.currentTimeMillis());
            java.util.ArrayList<LanTransferPeerDirectory.Candidate> trusted = new java.util.ArrayList<>();
            for (LanTransferPeerDirectory.Candidate candidate : candidates)
                if (candidate.isConnectable() && credentials.trustedPeerIds.contains(candidate.peerId))
                    trusted.add(candidate);
            if (trusted.isEmpty()) {
                status.setText("当前局域网尚未发现可连接的可信设备；请确认对端已开启文件传输并刷新");
                refreshPeerList();
                return;
            }
            String[] labels = new String[trusted.size()];
            for (int i = 0; i < trusted.size(); i++) {
                LanTransferPeerDirectory.Candidate c = trusted.get(i);
                labels[i] = c.peerId + " · " + c.host + " · 在线";
            }
            new AlertDialog.Builder(this).setTitle("选择接收设备")
                .setItems(labels, (dialog, which) -> {
                    selectedDestination = trusted.get(which);
                    pickFileForSending();
                }).setNegativeButton("取消", null).show();
        } catch (Exception failure) {
            status.setText("读取可信设备失败：" + failure.getClass().getSimpleName());
        }
    }

    private void createOutgoingTask(Uri uri, LanTransferPeerDirectory.Candidate destination) {
        status.setText("正在生成应用私有文件快照…");
        try {
            io.execute(() -> {
                try {
                    LanTransferPeerDirectory.Candidate fresh = LanTransferPeerDirectory.findCurrent(
                            getApplicationContext(), destination.peerId, System.currentTimeMillis());
                    if (fresh == null || !fresh.isConnectable())
                        throw new java.io.IOException("目标设备发现记录已过期");
                    LanTransferCredentials.Value credentials = LanTransferCredentials.load(getApplicationContext());
                    if (credentials == null || !credentials.trustedPeerIds.contains(fresh.peerId))
                        throw new SecurityException("目标设备尚未配对或不在可信列表中");
                    LanTransferSnapshot.Result snapshot = LanTransferSnapshot.create(getApplicationContext(), uri);
                    String local = TrackingService.getUniqueDeviceId(getApplicationContext());
                    LanTransferStore.Offer offer = new LanTransferStore.Offer(snapshot.transferId, local,
                            fresh.peerId, snapshot.filename, snapshot.sizeBytes, snapshot.sha256,
                            System.currentTimeMillis(), snapshot.mimeType);
                    LanTransferSenderService.enqueue(getApplicationContext(), offer,
                            snapshot.file.getCanonicalPath());
                    runOnUiThread(() -> status.setText("发送任务已创建：" + snapshot.filename
                            + " → " + fresh.peerId + "；后台将按当前局域网端点连接"));
                } catch (Exception failure) {
                    runOnUiThread(() -> status.setText("创建发送任务失败：" + failure.getClass().getSimpleName()));
                }
            });
        } catch (RejectedExecutionException stopped) {
            status.setText("文件界面正在关闭，请重新打开");
        }
    }

    private void refreshPeerList() {
        if (offerList == null || isFinishing() || isDestroyed()) return;
        LinearLayout peerList = findViewById(R.id.lanTransferPeerList);
        if (peerList == null) return;
        peerList.removeAllViews();
        List<LanTransferPeerDirectory.Candidate> candidates =
                LanTransferPeerDirectory.loadCurrent(this, System.currentTimeMillis());
        LanTransferCredentials.Value credentials = null;
        try { credentials = LanTransferCredentials.load(this); } catch (Exception ignored) {}
        Set<String> trusted = credentials == null ? Collections.emptySet() : credentials.trustedPeerIds;
        if (candidates.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂未发现设备。使用独立文件传输发现通道；对讲发现不是必需条件。");
            empty.setPadding(0, 8, 0, 12);
            peerList.addView(empty);
            return;
        }
        for (LanTransferPeerDirectory.Candidate candidate : candidates) {
            boolean isTrusted = trusted.contains(candidate.peerId);
            TextView item = new TextView(this);
            item.setPadding(0, 10, 0, 4);
            item.setText(candidate.peerId + "\n" + candidate.host + ":"
                    + (candidate.isConnectable() ? candidate.port : "等待监听")
                    + (isTrusted ? "\n已配对 · 可发送" : "\n未配对 · 仅发现，不可信任"));
            peerList.addView(item);
            Button action = new Button(this);
            if (isTrusted && candidate.isConnectable()) {
                action.setText("发送文件到此设备");
                action.setOnClickListener(v -> { selectedDestination = candidate; pickFileForSending(); });
            } else {
                action.setText(isTrusted ? "等待设备开启接收" : "与此设备配对");
                action.setEnabled(!isTrusted);
                action.setOnClickListener(v -> showPairingDialog(candidate.peerId));
            }
            peerList.addView(action);
        }
    }

    private void refreshOffers() {
        if (isFinishing() || isDestroyed() || io.isShutdown()) return;
        refreshPeerList();
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
                + (incoming ? "\n接收自：" : "\n发送给：")
                + (incoming ? transfer.offer.senderPeerId : transfer.offer.targetPeerId)
                + "\n大小：" + android.text.format.Formatter.formatFileSize(this, transfer.offer.sizeBytes)
                + "\n方向：" + (incoming ? "接收" : "发送")
                + "\n状态：" + transfer.state
                + "\n更新时间：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", transfer.updatedAtMs));
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
        if (!LanTransferFeature.isEnabled(this)) {
            Toast.makeText(this, "文件传输已关闭", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        refreshOffers();
        Intent pairingQuery = new Intent(this, TrackingService.class)
                .setAction("ACTION_LAN_TRANSFER_PAIRING_QUERY");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(pairingQuery);
            else startService(pairingQuery);
        } catch (Exception failure) {
            if (status != null) status.setText("查询待处理配对失败：" + failure.getClass().getSimpleName());
        }
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
