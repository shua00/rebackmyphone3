package com.example.devicefinder.diagnostics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local diagnostic history, detail, share, and SAF export screen. */
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
    private DiagnosticRun pendingExport;
    private DiagnosticReportExporter.Format pendingFormat;

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
        description.setText("报告仅保存在本机独立诊断数据库中。最多保留 100 份，超过 30 天自动清理。导出或分享前可查看完整内容。");
        description.setTextSize(13f);
        root.addView(description);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> loadHistory());
        Button clear = new Button(this);
        clear.setText("清空历史");
        clear.setOnClickListener(v -> confirmClear());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1f));
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

    private void loadHistory() {
        status.setText("正在读取诊断报告…");
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
        status.setText("共 " + reports.size() + " 份报告");
        if (reports.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无诊断报告。完成诊断测试并保存结果后，报告会显示在这里。");
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
        String detail = DiagnosticReportRenderer.toMarkdown(report);
        new AlertDialog.Builder(this)
                .setTitle(report.metadata.featureId + " · "
                        + (report.isCompleteAndPassing() ? "PASS" : "未通过"))
                .setMessage(detail)
                .setItems(new String[]{"分享给 AI / 其他应用", "导出 JSON", "导出 Markdown",
                        "导出 HTML", "删除此报告"}, (dialog, which) -> {
                    if (which == 0) share(report);
                    else if (which >= 1 && which <= 3) {
                        DiagnosticReportExporter.Format format = which == 1
                                ? DiagnosticReportExporter.Format.JSON
                                : which == 2 ? DiagnosticReportExporter.Format.MARKDOWN
                                : DiagnosticReportExporter.Format.HTML;
                        save(report, format);
                    } else if (which == 4) confirmDelete(report);
                }).show();
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
                main.post(() -> Toast.makeText(this, "导出失败：" + safeMessage(error),
                        Toast.LENGTH_LONG).show());
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
        return message == null || message.trim().isEmpty()
                ? "未知错误" : message;
    }
}
