package com.example.devicefinder.diagnostics;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import static org.junit.Assert.*;

public class DiagnosticReportRendererTest {
    private DiagnosticRun sample(String feature,String device,String testId){
        DiagnosticRun.Metadata m=DiagnosticRun.Metadata.createForFeature(feature,1700000000000L,
                "1.2.3","deadbeef",device,34,"wifi","SECURE","sender");
        DiagnosticRun.CaseResult c=new DiagnosticRun.CaseResult(testId,DiagnosticRun.Status.FAIL,
                125,"TIMEOUT",Collections.singletonMap("rttP95Ms",900L));
        return new DiagnosticRun(m,Collections.singletonList(c),1);
    }
    @Test public void markdownIncludesFeatureEnvironmentFailureAndMetrics(){
        String md=DiagnosticReportRenderer.toMarkdown(sample("location","Pixel 8","gps_probe"));
        assertTrue(md.contains("Feature: location"));assertTrue(md.contains("NOT PASSED"));
        assertTrue(md.contains("Pixel 8"));assertTrue(md.contains("TIMEOUT"));assertTrue(md.contains("rttP95Ms=900"));
    }
    @Test public void htmlEscapesUntrustedMetadataAndCaseNames(){
        String html=DiagnosticReportRenderer.toHtml(sample("network","<img src=x onerror=alert(1)>","case<script>"));
        assertFalse(html.contains("<img src=x"));assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;img"));assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("viewport"));
    }
    @Test public void exporterWritesJsonMarkdownAndHtmlAsUtf8()throws Exception{
        DiagnosticRun run=sample("device_management","Device","health_check");
        for(DiagnosticReportExporter.Format f:DiagnosticReportExporter.Format.values()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();DiagnosticReportExporter.write(run,f,out);
            String value=out.toString("UTF-8");assertFalse(value.isEmpty());
            if(f==DiagnosticReportExporter.Format.JSON)assertTrue(value.startsWith("{"));
            if(f==DiagnosticReportExporter.Format.MARKDOWN)assertTrue(value.startsWith("# Diagnostic"));
            if(f==DiagnosticReportExporter.Format.HTML)assertTrue(value.startsWith("<!doctype html>"));
        }
    }
    @Test(expected=IllegalArgumentException.class) public void exporterRejectsMissingOutput()throws Exception{
        DiagnosticReportExporter.write(sample("network","Device","probe"),DiagnosticReportExporter.Format.JSON,null);
    }
}
