package com.example.devicefinder.diagnostics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.example.devicefinder.BuildInfo;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local diagnostic history, manual test execution, detail, share, and SAF export screen. */
public final class DiagnosticHistoryActivity extends Activity {
    private static final int REQUEST_SAVE_REPORT = 7610;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DiagnosticHistory");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView status;
    private Button run;
    private DiagnosticRun pendingExport;
    private DiagnosticReportExporter.Format pendingFormat;
    private volatile boolean running;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("诊断报告历史");
        title.setTextSize(22f);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("本机只读诊断：检查 Android 运行环境、网络连接状态和应用安装信息。不采集聊天/文件内容，不执行 Shell，不修改业务数据。报告保存在独立诊断数据库中。");
        description.setTextSize(13f);
        root.addView(description);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        run = new Button(this);
        run.setText("运行本机诊断");
        run.setOnClickListener(v -> startLocalDiagnostic());
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> loadHistory());
        Button clear = new Button(this);
        clear.setText("清空历史");
        clear.setOnClickListener(v -> confirmClear());
        actions.addView(run, new LinearLayout.LayoutParams(0, -2, 1.4f));
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 0.8f));
        actions.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void startLocalDiagnostic() {
        if (running) return;
        running = true;
        run.setEnabled(false);
        run.setText("诊断执行中…");
        status.setText("正在执行只读诊断探针并保存报告…");
        io.execute(() -> {
            try {
                List<DiagnosticExecutor.TestCase> tests =
                        DiagnosticProbeCatalog.localReadOnlyTests(getApplicationContext());
                DiagnosticRun.Metadata metadata = DiagnosticRun.Metadata.createForFeature(
                        "android_local", System.currentTimeMillis(), BuildInfo.BUILD_VERSION,
                        "unknown", Build.MODEL == null ? "unknown" : Build.MODEL,
                        Build.VERSION.SDK_INT,
                        DiagnosticProbeCatalog.detectNetworkType(getApplicationContext()),
                        "READ_ONLY", "LOCAL");
                DiagnosticRun recorded;
                try (DiagnosticRunHistoryStore history =
                             new DiagnosticRunHistoryStore(getApplicationContext())) {
                    DiagnosticRunRecorder recorder =
                            new DiagnosticRunRecorder(DiagnosticRunRecorder.sqlite(history));
                    recorded = recorder.executeAndRecord(metadata, tests, tests.size(), null, null);
                }
                final DiagnosticRun result = recorded;
                main.post(() -> {
                    status.setText("诊断完成并已保存：PASS " + result.count(DiagnosticRun.Status.PASS)
                            + "，FAIL " + result.count(DiagnosticRun.Status.FAIL)
                            + "，SKIPPED " + result.count(DiagnosticRun.Status.SKIPPED));
                    finishRunUi();
                    loadHistory();
                    showReportActions(result);
                });
            } catch (Exception error) {
                main.post(() -> {
                    status.setText("诊断或报告保存失败：" + safeMessage(error));
                    finishRunUi();
                });
            }
        });
    }

    private void finishRunUi() {
        running = false;
        if (run != null) {
            run.setEnabled(true);
            run.setText("运行本机诊断");
        }
    }

    private void loadHistory() {
        status.setText(running ? "诊断执行中…" : "正在读取诊断报告…");
        io.execute(() -> {
            List<DiagnosticRun> reports;
            try (DiagnosticRunHistoryStore store = new DiagnosticRunHistoryStore(this)) {
                reports = store.list(DiagnosticRunHistoryStore.MAX_HISTORY);
            } catch (Exception error) {
                main.post(() -> status.setText("读取失败：" + safeMessage(error)));
                return;
            }
            main.post(() -> render(reports));
        });
    }

    private void render(List<DiagnosticRun> reports) {
        rows.removeAllViews();
        if (!running) status.setText("共 " + reports.size() + " 份报告");
        if (reports.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无诊断报告。点击“运行本机诊断”生成第一份报告。");
            empty.setPadding(0, dp(18), 0, dp(18));
            rows.addView(empty);
            return;
        }
        for (DiagnosticRun report : reports) {
            Button item = new Button(this);
            String result = report.isCompleteAndPassing() ? "PASS" : "未通过";
            item.setText(report.metadata.featureId + " · " + result + "\n"
                    + report.metadata.runId + " · "
                    + report.count(DiagnosticRun.Status.PASS) + "/"
                    + report.expectedCaseCount + " 项通过");
            item.setAllCaps(false);
            item.setOnClickListener(v -> showReportActions(report));
            rows.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void showReportActions(DiagnosticRun report) {
        // Keep the report body and its actions in one explicit detail surface. A message
        // dialog combined with a choice list made the export actions easy to miss and
        // could cause platform AlertController implementations to prioritize one body.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(4));

        ScrollView detailScroll = new ScrollView(this);
        detailScroll.setFillViewport(false);
        TextView detailText = new TextView(this);
        detailText.setText(DiagnosticReportRenderer.toMarkdown(report));
        detailText.setTextIsSelectable(true);
        detailText.setTextSize(13f);
        detailText.setLineSpacing(dp(2), 1f);
        detailText.setPadding(0, dp(8), 0, dp(12));
        detailScroll.addView(detailText);
        content.addView(detailScroll, new LinearLayout.LayoutParams(-1, dp(260)));

        TextView actionsTitle = new TextView(this);
        actionsTitle.setText("报告操作");
        actionsTitle.setTextSize(14f);
        actionsTitle.setPadding(0, dp(8), 0, dp(4));
        content.addView(actionsTitle);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(report.metadata.featureId + " · "
                        + (report.isCompleteAndPassing() ? "PASS" : "未通过"))
                .setView(content)
                .setNegativeButton("关闭", null)
                .create();

        addReportAction(content, "分享给 AI / 其他应用", () -> {
            dialog.dismiss();
            share(report);
        });
        addReportAction(content, "导出 JSON", () -> {
            dialog.dismiss();
            save(report, DiagnosticReportExporter.Format.JSON);
        });
        addReportAction(content, "导出 Markdown", () -> {
            dialog.dismiss();
            save(report, DiagnosticReportExporter.Format.MARKDOWN);
        });
        addReportAction(content, "导出 HTML", () -> {
            dialog.dismiss();
            save(report, DiagnosticReportExporter.Format.HTML);
        });
        addReportAction(content, "删除此报告", () -> {
            dialog.dismiss();
            confirmDelete(report);
        });
        dialog.show();
    }

    private void addReportAction(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(2);
        parent.addView(button, params);
    }

    private void share(DiagnosticRun report) {
        try {
            startActivity(DiagnosticReportIntents.createShareIntent(report));
        } catch (Exception error) {
            Toast.makeText(this, "无法打开分享应用：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void save(DiagnosticRun report, DiagnosticReportExporter.Format format) {
        pendingExport = report;
        pendingFormat = format;
        try {
            startActivityForResult(DiagnosticReportIntents.createSaveIntent(report, format),
                    REQUEST_SAVE_REPORT);
        } catch (Exception error) {
            pendingExport = null;
            pendingFormat = null;
            Toast.makeText(this, "无法打开系统文件保存界面：" + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_REPORT) return;
        DiagnosticRun report = pendingExport;
        DiagnosticReportExporter.Format format = pendingFormat;
        pendingExport = null;
        pendingFormat = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || report == null || format == null)
            return;
        Uri destination = data.getData();
        io.execute(() -> {
            try {
                DiagnosticReportIntents.writeToUri(this, destination, report, format);
                main.post(() -> Toast.makeText(this, "报告已导出：" + destination.getLastPathSegment(),
                        Toast.LENGTH_LONG).show());
            } catch (IOException | RuntimeException error) {
                main.post(() -> Toast.makeText(this, "导出失败：" + safeMessage(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmDelete(DiagnosticRun report) {
        new AlertDialog.Builder(this).setTitle("删除诊断报告")
                .setMessage("只删除本机这份历史报告，不影响设备端数据、诊断批次收件箱或其他报告。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> io.execute(() -> {
                    try (DiagnosticRunHistoryStore store = new DiagnosticRunHistoryStore(this)) {
                        store.delete(report.metadata.runId);
                    }
                    main.post(this::loadHistory);
                })).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("清空诊断历史")
                .setMessage("仅清空本机诊断报告历史，不会删除设备端日志、批次收件箱或其他业务数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> io.execute(() -> {
                    try (DiagnosticRunHistoryStore store = new DiagnosticRunHistoryStore(this)) {
                        store.clear();
                    }
                    main.post(this::loadHistory);
                })).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}
