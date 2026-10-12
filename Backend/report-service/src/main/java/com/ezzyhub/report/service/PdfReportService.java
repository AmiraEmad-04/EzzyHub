package com.ezzyhub.report.service;

import com.ezzyhub.report.model.AnalysisRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * Generates a professional PDF verification report from a stored AnalysisRecord.
 * Uses iText 8 (AGPL community edition).
 */
@Service
public class PdfReportService {

    private static final Logger log = LoggerFactory.getLogger(PdfReportService.class);

    // ── Brand colours (dark gold theme to match frontend) ─────────────────
    private static final DeviceRgb GOLD       = new DeviceRgb(0xFB, 0xBF, 0x24);  // --primary
    private static final DeviceRgb DARK_BG    = new DeviceRgb(0x16, 0x16, 0x16);  // --card
    private static final DeviceRgb LIGHT_GREY = new DeviceRgb(0xF3, 0xF4, 0xF6);
    private static final DeviceRgb SUCCESS     = new DeviceRgb(0x10, 0xB9, 0x81);
    private static final DeviceRgb WARNING     = new DeviceRgb(0xF5, 0x9E, 0x0B);
    private static final DeviceRgb DANGER      = new DeviceRgb(0xEF, 0x44, 0x44);
    private static final DeviceRgb INFO        = new DeviceRgb(0x3B, 0x82, 0xF6);
    private static final DeviceRgb MUTED       = new DeviceRgb(0x6B, 0x72, 0x80);
    private static final DeviceRgb TEXT        = new DeviceRgb(0x11, 0x18, 0x27);

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private final ObjectMapper mapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    public byte[] generatePdf(AnalysisRecord record) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter  writer  = new PdfWriter(baos);
            PdfDocument pdf    = new PdfDocument(writer);
            Document   doc     = new Document(pdf, PageSize.A4);
            doc.setMargins(40, 50, 40, 50);

            PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            PdfFont bold    = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont mono    = PdfFontFactory.createFont(StandardFonts.COURIER);

            buildCoverPage(doc, record, bold, regular);
            buildSummarySection(doc, record, bold, regular);

            if (record.getMatchResultJson() != null)
                buildMatchingSection(doc, record, bold, regular, mono);

            if (record.getCodeAnalysisJson() != null)
                buildCodeAnalysisSection(doc, record, bold, regular, mono);

            if (record.getSonarResultJson() != null)
                buildSonarSection(doc, record, bold, regular, mono);

            if (record.getAiSuggestionsJson() != null)
                buildAiSuggestionsSection(doc, record, bold, regular, mono);

            buildFooter(doc, record, regular);

            doc.close();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate PDF for record {}: {}", record.getId(), e.getMessage(), e);
            throw new RuntimeException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cover page
    // ─────────────────────────────────────────────────────────────────────

    private void buildCoverPage(Document doc, AnalysisRecord r, PdfFont bold, PdfFont regular) throws Exception {
        // Header bar
        Table header = new Table(UnitValue.createPercentArray(new float[]{1}))
                .setWidth(UnitValue.createPercentValue(100))
                .setBackgroundColor(DARK_BG)
                .setMarginBottom(24);
        Cell hCell = new Cell()
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .setPadding(20);
        hCell.add(new Paragraph("EzzyHub")
                .setFont(bold).setFontSize(28).setFontColor(GOLD));
        hCell.add(new Paragraph("AI Code Verification Platform")
                .setFont(regular).setFontSize(12).setFontColor(new DeviceRgb(0xA0,0xA0,0xA0)));
        header.addCell(hCell);
        doc.add(header);

        doc.add(new Paragraph("Verification Report")
                .setFont(bold).setFontSize(22).setFontColor(TEXT)
                .setMarginBottom(6));

        doc.add(new Paragraph(safe(r.getProjectName(), "Unnamed Project"))
                .setFont(bold).setFontSize(16).setFontColor(new DeviceRgb(0x1E,0x40,0xAF))
                .setMarginBottom(4));

        doc.add(new Paragraph("Generated: " + DT_FMT.format(r.getCreatedAt()))
                .setFont(regular).setFontSize(10).setFontColor(MUTED)
                .setMarginBottom(2));

        doc.add(new Paragraph("Analysis type: " + safe(r.getAnalysisType(), "—"))
                .setFont(regular).setFontSize(10).setFontColor(MUTED)
                .setMarginBottom(20));

        addRule(doc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Executive summary
    // ─────────────────────────────────────────────────────────────────────

    private void buildSummarySection(Document doc, AnalysisRecord r, PdfFont bold, PdfFont regular) {
        doc.add(sectionHeader("Executive Summary", bold));

        Table t = summaryTable(bold, regular);

        if (r.getCoveragePercentage() != null)
            addSummaryRow(t, "Requirement Coverage", String.format("%.1f%%", r.getCoveragePercentage()),
                    coverageColor(r.getCoveragePercentage()), bold, regular);

        if (r.getImplementedCount() != null)
            addSummaryRow(t, "Implemented Requirements", String.valueOf(r.getImplementedCount()),
                    SUCCESS, bold, regular);

        if (r.getPartialCount() != null)
            addSummaryRow(t, "Partially Implemented", String.valueOf(r.getPartialCount()),
                    WARNING, bold, regular);

        if (r.getMissingCount() != null)
            addSummaryRow(t, "Missing / Not Implemented", String.valueOf(r.getMissingCount()),
                    DANGER, bold, regular);

        if (r.getTotalSourceFiles() != null)
            addSummaryRow(t, "Total Source Files", String.valueOf(r.getTotalSourceFiles()),
                    INFO, bold, regular);

        if (r.getAnalyzedFileCount() != null)
            addSummaryRow(t, "Files Analyzed by AI", String.valueOf(r.getAnalyzedFileCount()),
                    INFO, bold, regular);

        addSummaryRow(t, "Overall Status", safe(r.getStatus(), "unknown"),
                "success".equalsIgnoreCase(r.getStatus()) ? SUCCESS :
                        "partial".equalsIgnoreCase(r.getStatus()) ? WARNING : DANGER,
                bold, regular);

        doc.add(t.setMarginBottom(20));
        addRule(doc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Requirement ↔ Code matching
    // ─────────────────────────────────────────────────────────────────────

    private void buildMatchingSection(Document doc, AnalysisRecord r,
                                      PdfFont bold, PdfFont regular, PdfFont mono) throws Exception {
        doc.add(sectionHeader("Requirement ↔ Code Matching", bold));
        JsonNode root = parseJson(r.getMatchResultJson());
        if (root == null) { doc.add(noData(regular)); return; }

        // NEW: matching-service's real field is `requirementMatches`
        // (com.ezzyhub.matching.dto.MatchReport) — this used to read a
        // `results` field that never existed, so this whole section
        // silently rendered "No data available" even when a match had run.
        JsonNode results = root.path("requirementMatches");
        if (!results.isArray() || results.size() == 0) {
            doc.add(noData(regular));
            addRule(doc);
            return;
        }

        // Table header
        Table table = new Table(UnitValue.createPercentArray(new float[]{4, 1.2f, 1.4f, 3}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(16);

        addTableHeader(table, bold, "Requirement", "Score", "Status", "Matched Code");

        int rowNum = 0;
        for (JsonNode req : results) {
            String reqId   = textOf(req, "reqId", "#");
            String reqText = textOf(req, "reqText", "");
            double score   = req.path("score").asDouble(0);
            int    pct     = (int) Math.round(score * 100);
            String status  = textOf(req, "status", "UNKNOWN");
            // NEW: there's no matchedCode/matchedFeatures field on
            // RequirementMatchResult — the real evidence is topChunks[0]
            // (filePath / className / methodName).
            String code    = describeTopChunk(req);

            DeviceRgb rowBg = rowNum % 2 == 0 ? new DeviceRgb(0xFF, 0xFF, 0xFF) : LIGHT_GREY;
            DeviceRgb statusColor = "IMPLEMENTED".equals(status) ? SUCCESS :
                    "PARTIAL".equals(status)      ? WARNING : DANGER;

            addDataCell(table, "[" + reqId + "] " + truncate(reqText, 120), regular, 9, rowBg, TEXT);
            addDataCell(table, pct + "%", bold, 9, rowBg,
                    pct >= 80 ? SUCCESS : pct >= 40 ? WARNING : DANGER);
            addDataCell(table, status, bold, 8, rowBg, statusColor);
            addDataCell(table, truncate(code, 80), mono, 8, rowBg, MUTED);
            rowNum++;
        }
        doc.add(table);

        // Missing requirements detail
        buildMissingSection(doc, results, bold, regular, mono);
        addRule(doc);
    }

    /**
     * Best short label for the "matched code" column, mirroring the
     * frontend's describeTopChunk() — e.g. "UserController.login()".
     */
    private String describeTopChunk(JsonNode req) {
        JsonNode chunks = req.path("topChunks");
        if (!chunks.isArray() || chunks.size() == 0) return "—";
        JsonNode top = chunks.get(0);
        String className = textOf(top, "className", "");
        String methodName = textOf(top, "methodName", "");
        if (!className.isEmpty() && !methodName.isEmpty()) return className + "." + methodName + "()";
        if (!className.isEmpty()) return className;
        return textOf(top, "filePath", "—");
    }

    private void buildMissingSection(Document doc, JsonNode results,
                                     PdfFont bold, PdfFont regular, PdfFont mono) {
        // Count how many are missing
        int missingCount = 0;
        for (JsonNode r : results) if ("MISSING".equals(textOf(r,"status",""))) missingCount++;
        if (missingCount == 0) return;

        doc.add(new Paragraph("Missing / Unimplemented Requirements (" + missingCount + ")")
                .setFont(bold).setFontSize(12).setFontColor(DANGER)
                .setMarginTop(12).setMarginBottom(6));

        int shown = 0;
        for (JsonNode req : results) {
            if (!"MISSING".equals(textOf(req, "status", ""))) continue;
            if (shown >= 50) { // guard against enormous lists
                doc.add(new Paragraph("… and " + (missingCount - shown) + " more.")
                        .setFont(regular).setFontSize(9).setFontColor(MUTED));
                break;
            }
            String reqText = textOf(req, "reqText", textOf(req, "requirementText", "(no text)"));
            String reqId   = textOf(req, "reqId", "?");
            Cell cell = new Cell()
                    .setBorder(new SolidBorder(DANGER, 1))
                    .setBackgroundColor(new DeviceRgb(0xFE, 0xF2, 0xF2))
                    .setPadding(8).setMarginBottom(4);
            cell.add(new Paragraph("[" + reqId + "] " + reqText)
                    .setFont(regular).setFontSize(9).setFontColor(TEXT));

            // Suggested code (AI field) if present
            JsonNode suggestedCode = req.path("suggestedCode");
            if (!suggestedCode.isMissingNode() && !suggestedCode.asText().isBlank()) {
                cell.add(new Paragraph("Suggested implementation:")
                        .setFont(bold).setFontSize(8).setFontColor(new DeviceRgb(0x1E,0x40,0xAF))
                        .setMarginTop(4));
                cell.add(new Paragraph(truncate(suggestedCode.asText(), 500))
                        .setFont(mono).setFontSize(8).setFontColor(TEXT));
            }

            Table wrap = new Table(UnitValue.createPercentArray(new float[]{1}))
                    .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(6);
            wrap.addCell(cell);
            doc.add(wrap);
            shown++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Code analysis
    // ─────────────────────────────────────────────────────────────────────

    private void buildCodeAnalysisSection(Document doc, AnalysisRecord r,
                                          PdfFont bold, PdfFont regular, PdfFont mono) throws Exception {
        doc.add(sectionHeader("Code Structure Analysis", bold));
        JsonNode root = parseJson(r.getCodeAnalysisJson());
        if (root == null) { doc.add(noData(regular)); return; }

        // Project summary
        JsonNode summary = root.path("projectSummary");
        if (!summary.isMissingNode()) {
            String overview = textOf(summary, "overview", textOf(summary, "description", ""));
            if (!overview.isEmpty()) {
                doc.add(new Paragraph(overview)
                        .setFont(regular).setFontSize(10).setFontColor(TEXT)
                        .setMarginBottom(10));
            }
        }

        // Per-file semantic analysis table
        JsonNode files = root.path("fileAnalyses");
        if (files.isArray() && files.size() > 0) {
            doc.add(new Paragraph("File Semantic Analysis")
                    .setFont(bold).setFontSize(12).setFontColor(TEXT).setMarginBottom(6));

            Table table = new Table(UnitValue.createPercentArray(new float[]{3, 1.2f, 4}))
                    .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(16);
            addTableHeader(table, bold, "File", "Language", "Semantic Role");

            int rowNum = 0;
            for (JsonNode file : files) {
                DeviceRgb bg = rowNum % 2 == 0 ? new DeviceRgb(0xFF, 0xFF, 0xFF) : LIGHT_GREY;
                addDataCell(table, textOf(file,"fileName",textOf(file,"filePath","?")), mono, 8, bg, new DeviceRgb(0x1D,0x4E,0xD8));
                addDataCell(table, textOf(file,"language",""), regular, 9, bg, MUTED);
                addDataCell(table, truncate(textOf(file,"semanticRole",""), 200), regular, 9, bg, TEXT);
                rowNum++;
            }
            doc.add(table);

            // Class/method details — top 20 files only to keep PDF manageable
            int fileLimit = Math.min(files.size(), 20);
            for (int i = 0; i < fileLimit; i++) {
                JsonNode file = files.get(i);
                JsonNode classes = file.path("classes");
                if (!classes.isArray() || classes.size() == 0) continue;

                doc.add(new Paragraph(textOf(file,"fileName",textOf(file,"filePath","?")))
                        .setFont(bold).setFontSize(10).setFontColor(new DeviceRgb(0x1D,0x4E,0xD8))
                        .setMarginTop(8).setMarginBottom(4));

                for (JsonNode cls : classes) {
                    String clsName    = textOf(cls,"name","?");
                    String clsMeaning = textOf(cls,"semanticMeaning","");
                    doc.add(new Paragraph("  Class: " + clsName)
                            .setFont(bold).setFontSize(9).setFontColor(TEXT));
                    if (!clsMeaning.isEmpty())
                        doc.add(new Paragraph("  " + truncate(clsMeaning, 300))
                                .setFont(regular).setFontSize(9).setFontColor(MUTED).setMarginBottom(3));

                    JsonNode methods = cls.path("methods");
                    if (methods.isArray()) {
                        for (JsonNode m : methods) {
                            String sig     = textOf(m,"signature",textOf(m,"name","?"));
                            String meaning = textOf(m,"semanticMeaning","");
                            doc.add(new Paragraph("    • " + truncate(sig, 120))
                                    .setFont(mono).setFontSize(8).setFontColor(new DeviceRgb(0x92,0x40,0xFB)));
                            if (!meaning.isEmpty())
                                doc.add(new Paragraph("      " + truncate(meaning, 200))
                                        .setFont(regular).setFontSize(8).setFontColor(MUTED).setMarginBottom(2));
                        }
                    }
                }
            }
            if (files.size() > fileLimit) {
                doc.add(new Paragraph("(Showing " + fileLimit + " of " + files.size() + " files. "
                        + "Full data available via JSON export.)")
                        .setFont(regular).setFontSize(9).setFontColor(MUTED).setMarginTop(8));
            }
        }
        addRule(doc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sonar analysis
    // ─────────────────────────────────────────────────────────────────────

    private void buildSonarSection(Document doc, AnalysisRecord r,
                                   PdfFont bold, PdfFont regular, PdfFont mono) throws Exception {
        doc.add(sectionHeader("Sonar Analysis", bold));
        JsonNode root = parseJson(r.getSonarResultJson());
        if (root == null) { doc.add(noData(regular)); return; }

        // NEW: this custom Sonar-Analyzer service (com.sonaranalyzer.model.
        // AnalysisReport) returns its own flat summary fields directly —
        // bugs, vulnerabilities, codeSmells, coverage as a string like
        // "82.5%", etc. — not a raw SonarQube-style `measures` array. That
        // field never existed here, so this metrics table was always empty.
        doc.add(new Paragraph("Quality Metrics").setFont(bold).setFontSize(11).setFontColor(TEXT).setMarginBottom(6));
        Table t = summaryTable(bold, regular);
        String qualityGate = textOf(root, "qualityGateStatus", "UNKNOWN");
        addSummaryRow(t, "Quality Gate", qualityGate,
                "OK".equalsIgnoreCase(qualityGate) ? SUCCESS
                        : "ERROR".equalsIgnoreCase(qualityGate) ? DANGER : WARNING,
                bold, regular);
        addSummaryRow(t, "Bugs", textOf(root, "bugs", "0"), DANGER, bold, regular);
        addSummaryRow(t, "Vulnerabilities", textOf(root, "vulnerabilities", "0"), DANGER, bold, regular);
        addSummaryRow(t, "Code Smells", textOf(root, "codeSmells", "0"), WARNING, bold, regular);
        addSummaryRow(t, "Security Hotspots", textOf(root, "securityHotspots", "0"), WARNING, bold, regular);
        addSummaryRow(t, "Coverage", textOf(root, "coverage", "—"), INFO, bold, regular);
        addSummaryRow(t, "Duplications", textOf(root, "duplications", "—"), INFO, bold, regular);
        addSummaryRow(t, "Lines of Code", textOf(root, "linesOfCode", "—"), MUTED, bold, regular);
        doc.add(t.setMarginBottom(12));

        // Issues by file
        JsonNode issuesByFile = root.path("issuesByFile");
        if (issuesByFile.isMissingNode()) issuesByFile = root.path("issues");

        if (!issuesByFile.isMissingNode()) {
            doc.add(new Paragraph("Issues Found").setFont(bold).setFontSize(11).setFontColor(TEXT).setMarginBottom(6));

            Table table = new Table(UnitValue.createPercentArray(new float[]{3, 1.2f, 1.2f, 4}))
                    .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(12);
            addTableHeader(table, bold, "File", "Severity", "Type", "Message");

            int rowNum = 0;
            int shown  = 0;
            Iterator<Map.Entry<String, JsonNode>> fields = issuesByFile.fields();
            while (fields.hasNext() && shown < 100) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fileName = entry.getKey();
                JsonNode issues = entry.getValue();
                if (!issues.isArray()) continue;
                for (JsonNode issue : issues) {
                    if (shown >= 100) break;
                    DeviceRgb bg = rowNum % 2 == 0 ? new DeviceRgb(0xFF, 0xFF, 0xFF) : LIGHT_GREY;
                    String sev = textOf(issue,"severity","INFO");
                    DeviceRgb sevColor = "BLOCKER".equals(sev)||"CRITICAL".equals(sev) ? DANGER :
                            "MAJOR".equals(sev) ? WARNING :
                                    "MINOR".equals(sev) ? INFO : MUTED;
                    addDataCell(table, truncate(fileName,60), mono, 8, bg, new DeviceRgb(0x1D,0x4E,0xD8));
                    addDataCell(table, sev, bold, 8, bg, sevColor);
                    addDataCell(table, textOf(issue,"type",""), regular, 8, bg, MUTED);
                    addDataCell(table, truncate(textOf(issue,"message",textOf(issue,"msg","")),120), regular, 8, bg, TEXT);
                    rowNum++; shown++;
                }
            }
            doc.add(table);
            if (shown >= 100)
                doc.add(new Paragraph("(First 100 issues shown. Full data available via JSON export.)")
                        .setFont(regular).setFontSize(9).setFontColor(MUTED));
        }
        addRule(doc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AI Suggestions
    // ─────────────────────────────────────────────────────────────────────

    private void buildAiSuggestionsSection(Document doc, AnalysisRecord r,
                                           PdfFont bold, PdfFont regular, PdfFont mono) throws Exception {
        doc.add(sectionHeader("AI Suggestions", bold));
        JsonNode root = parseJson(r.getAiSuggestionsJson());
        if (root == null) { doc.add(noData(regular)); return; }

        // Flexible: handle both array and object with "suggestions" field
        JsonNode suggestions = root.isArray() ? root : root.path("suggestions");
        if (!suggestions.isArray() || suggestions.size() == 0) {
            String text = root.asText("");
            if (!text.isEmpty()) {
                doc.add(new Paragraph(truncate(text, 2000)).setFont(regular).setFontSize(10).setFontColor(TEXT));
            } else {
                doc.add(noData(regular));
            }
            addRule(doc);
            return;
        }

        int idx = 1;
        for (JsonNode s : suggestions) {
            String reqId   = textOf(s,"reqId","");
            String reqText = textOf(s,"requirement",textOf(s,"reqText",""));
            String code    = textOf(s,"suggestedCode",textOf(s,"code",""));
            String explain = textOf(s,"explanation","");

            doc.add(new Paragraph((reqId.isEmpty() ? "" : "[" + reqId + "] ") + reqText)
                    .setFont(bold).setFontSize(10).setFontColor(TEXT)
                    .setMarginTop(10).setMarginBottom(3));

            if (!explain.isEmpty())
                doc.add(new Paragraph(truncate(explain, 400))
                        .setFont(regular).setFontSize(9).setFontColor(MUTED).setMarginBottom(4));

            if (!code.isEmpty()) {
                doc.add(new Paragraph("Suggested Code:")
                        .setFont(bold).setFontSize(9).setFontColor(INFO).setMarginBottom(2));
                Table codeBlock = new Table(UnitValue.createPercentArray(new float[]{1}))
                        .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(6);
                Cell cc = new Cell()
                        .setBackgroundColor(new DeviceRgb(0xF8,0xF9,0xFA))
                        .setBorder(new SolidBorder(INFO, 0.5f))
                        .setPadding(8);
                cc.add(new Paragraph(truncate(code, 1000)).setFont(mono).setFontSize(8).setFontColor(TEXT));
                codeBlock.addCell(cc);
                doc.add(codeBlock);
            }

            if (idx >= 50) {
                doc.add(new Paragraph("(Showing 50 of " + suggestions.size() + " suggestions.)")
                        .setFont(regular).setFontSize(9).setFontColor(MUTED));
                break;
            }
            idx++;
        }
        addRule(doc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Footer
    // ─────────────────────────────────────────────────────────────────────

    private void buildFooter(Document doc, AnalysisRecord r, PdfFont regular) {
        doc.add(new Paragraph(
                "EzzyHub AI Verification Platform  •  Report ID: " + r.getId() + "  •  " + DT_FMT.format(r.getCreatedAt()))
                .setFont(regular).setFontSize(8).setFontColor(MUTED)
                .setTextAlignment(TextAlignment.CENTER).setMarginTop(16));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private Paragraph sectionHeader(String title, PdfFont bold) {
        return new Paragraph(title)
                .setFont(bold).setFontSize(14)
                .setFontColor(TEXT)
                .setBackgroundColor(LIGHT_GREY)
                .setPadding(6)
                .setMarginTop(16).setMarginBottom(10);
    }

    private Table summaryTable(PdfFont bold, PdfFont regular) {
        return new Table(UnitValue.createPercentArray(new float[]{3, 2}))
                .setWidth(UnitValue.createPercentValue(100));
    }

    private void addSummaryRow(Table t, String label, String value, DeviceRgb valueColor,
                               PdfFont bold, PdfFont regular) {
        t.addCell(new Cell().add(new Paragraph(label).setFont(regular).setFontSize(10).setFontColor(TEXT))
                .setBorder(new SolidBorder(LIGHT_GREY, 0.5f)).setPadding(6));
        t.addCell(new Cell().add(new Paragraph(value).setFont(bold).setFontSize(10).setFontColor(valueColor))
                .setBorder(new SolidBorder(LIGHT_GREY, 0.5f)).setPadding(6));
    }

    private void addTableHeader(Table t, PdfFont bold, String... headers) {
        for (String h : headers) {
            t.addHeaderCell(new Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(9).setFontColor(new DeviceRgb(0xFF, 0xFF, 0xFF)))
                    .setBackgroundColor(DARK_BG)
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                    .setPadding(6));
        }
    }

    private void addDataCell(Table t, String text, PdfFont font, float size,
                             DeviceRgb bg, DeviceRgb fg) {
        t.addCell(new Cell()
                .add(new Paragraph(text == null ? "" : text).setFont(font).setFontSize(size).setFontColor(fg))
                .setBackgroundColor(bg)
                .setBorder(new SolidBorder(LIGHT_GREY, 0.3f))
                .setPadding(5));
    }

    private void addRule(Document doc) {
        LineSeparator ls = new LineSeparator(new SolidLine(0.5f));
        ls.setStrokeColor(new DeviceRgb(0xE5, 0xE7, 0xEB));
        ls.setMarginTop(8);
        ls.setMarginBottom(8);
        doc.add(ls);
    }

    private Paragraph noData(PdfFont regular) {
        return new Paragraph("No data available for this section.")
                .setFont(regular).setFontSize(10).setFontColor(MUTED).setMarginBottom(10);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readTree(json); }
        catch (Exception e) { log.warn("Could not parse JSON: {}", e.getMessage()); return null; }
    }

    private String textOf(JsonNode node, String field, String fallback) {
        JsonNode n = node.path(field);
        return (n.isMissingNode() || n.isNull()) ? fallback : n.asText(fallback);
    }

    private String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private DeviceRgb coverageColor(Double pct) {
        if (pct == null) return MUTED;
        if (pct >= 80) return SUCCESS;
        if (pct >= 50) return WARNING;
        return DANGER;
    }
}