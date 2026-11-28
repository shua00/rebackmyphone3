package com.example.devicefinder.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes reports to a caller-selected destination (for Android, use the Storage Access Framework). */
public final class DiagnosticReportExporter {
    public enum Format {
        JSON("application/json","json"), MARKDOWN("text/markdown","md"), HTML("text/html","html");
        public final String mimeType, extension;
        Format(String mimeType,String extension){this.mimeType=mimeType;this.extension=extension;}
    }
    private DiagnosticReportExporter(){}
    public static void write(DiagnosticRun run,Format format,OutputStream output)throws IOException{
        if(run==null||format==null||output==null)throw new IllegalArgumentException("Run, format, and output stream are required");
        String content;
        switch(format){
            case JSON: content=DiagnosticJsonReport.toJson(run); break;
            case MARKDOWN: content=DiagnosticReportRenderer.toMarkdown(run); break;
            case HTML: content=DiagnosticReportRenderer.toHtml(run); break;
            default: throw new IllegalArgumentException("Unsupported report format");
        }
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
