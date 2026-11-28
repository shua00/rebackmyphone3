package com.example.devicefinder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicit device-owner UI for importing and revoking Rescue2 administrator identities. */
public final class Rescue2AdminTrustActivity extends Activity {
    private static final int REQUEST_IMPORT_IDENTITY = 7412;
    private static final int MAX_DOCUMENT_BYTES = 8 * 1024;
    private Rescue2AdminTrustStore trustStore;
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        trustStore = new Rescue2AdminTrustStore(this);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        setContentView(scroll);
        render();
    }

    private void render() {
        content.removeAllViews();
        TextView description = new TextView(this);
        description.setText("从 Rescue2 的 G 菜单导入管理员公钥文件。导入前会显示 SHA-256 指纹供核对；这里只认证 Rescue2 管理端身份，不设置命令白名单或业务权限等级。");
        content.addView(description);
        Button importButton = new Button(this);
        importButton.setText("导入 Rescue2 公钥信任文件");
        importButton.setOnClickListener(v -> openIdentityDocument());
        content.addView(importButton);

        TextView heading = new TextView(this);
        heading.setText("当前可信 Rescue2 管理端");
        heading.setTextSize(18f);
        heading.setPadding(0, pad(20), 0, pad(8));
        content.addView(heading);
        List<String> fingerprints = trustStore.listTrustedFingerprints();
        if (fingerprints.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚未信任任何 Rescue2 管理端");
            content.addView(empty);
            return;
        }
        for (String fingerprint : fingerprints) {
            TextView value = new TextView(this);
            value.setText(fingerprint);
            value.setTextIsSelectable(true);
            value.setPadding(0, pad(8), 0, 0);
            content.addView(value);
            Button revoke = new Button(this);
            revoke.setText("撤销此管理员信任");
            revoke.setOnClickListener(v -> confirmRevoke(fingerprint));
            content.addView(revoke);
        }
    }

    private int pad(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void openIdentityDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_IDENTITY);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_IDENTITY || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            String document = readBounded(data.getData());
            String fingerprint = trustStore.previewExportedIdentity(document);
            new AlertDialog.Builder(this)
                    .setTitle("确认信任 Rescue2 管理端")
                    .setMessage("请通过可信渠道核对以下完整 SHA-256 指纹后再确认：\n\n" + fingerprint
                            + "\n\n信任此公钥只确认 Rescue2 管理端身份，不会改变 Android 系统权限或 Root 授权边界。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认信任", (dialog, which) -> {
                        try {
                            trustStore.trustExportedIdentity(document);
                            Toast.makeText(this, "Rescue2 管理端已加入信任", Toast.LENGTH_LONG).show();
                            render();
                        } catch (Exception failure) {
                            showError(failure);
                        }
                    }).show();
        } catch (Exception failure) {
            showError(failure);
        }
    }

    private String readBounded(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new java.io.IOException("无法读取所选文件");
            byte[] buffer = new byte[1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_DOCUMENT_BYTES) throw new java.io.IOException("公钥文件超过 8KB 限制");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void confirmRevoke(String fingerprint) {
        new AlertDialog.Builder(this)
                .setTitle("撤销 Rescue2 管理端信任？")
                .setMessage("撤销后，此管理员签发的新任务将无法通过身份验证。\n\n" + fingerprint)
                .setNegativeButton("保留信任", null)
                .setPositiveButton("确认撤销", (dialog, which) -> {
                    try {
                        trustStore.revoke(fingerprint);
                        Toast.makeText(this, "管理员信任已撤销", Toast.LENGTH_SHORT).show();
                        render();
                    } catch (Exception failure) {
                        showError(failure);
                    }
                }).show();
    }

    private void showError(Exception failure) {
        new AlertDialog.Builder(this).setTitle("管理员公钥处理失败")
                .setMessage(failure.getClass().getSimpleName() + ": "
                        + (failure.getMessage() == null ? "未知错误" : failure.getMessage()))
                .setPositiveButton("知道了", null).show();
    }
}
