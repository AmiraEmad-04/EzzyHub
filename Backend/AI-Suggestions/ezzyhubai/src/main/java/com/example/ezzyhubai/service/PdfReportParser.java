package com.example.ezzyhubai.service;

import com.example.ezzyhubai.dto.MatchReportDto;
import com.example.ezzyhubai.dto.MatchedFileDto;
import com.example.ezzyhubai.dto.RequirementMatchDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfReportParser {

    // Matches: [REQ-AUTO-8] REQ-08: <wrapped text...> 37% MISSING exampleresource.java
    // Stops at the next [REQ-AUTO-n] marker or end of the table section.
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "\\[(REQ-AUTO-\\d+)]\\s*(REQ-\\d+:.*?)(\\d{1,3})%\\s*" +
                    "(MISSING|PARTIAL|IMPLEMENTED)\\s*(.*?)(?=\\[REQ-AUTO-\\d+]|\\z)",
            Pattern.DOTALL);

    public MatchReportDto parse(InputStream pdfStream) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(pdfStream.readAllBytes())) {
            text = new PDFTextStripper().getText(document);
        }

        int cutoff = text.indexOf("Missing / Unimplemented Requirements");
        String tableSection = cutoff > 0 ? text.substring(0, cutoff) : text;

        List<RequirementMatchDto> requirements = new ArrayList<>();
        Matcher matcher = ROW_PATTERN.matcher(tableSection);

        while (matcher.find()) {
            RequirementMatchDto dto = new RequirementMatchDto();
            dto.setReqId(matcher.group(1).trim());
            dto.setRequirementText(normalizeWhitespace(matcher.group(2)));
            dto.setScore(Double.parseDouble(matcher.group(3)) / 100.0);
            dto.setStatus(matcher.group(4).trim());
            dto.setMatchedFiles(toMatchedFiles(normalizeWhitespace(matcher.group(5))));

            requirements.add(dto);
        }

        MatchReportDto report = new MatchReportDto();
        report.setRequirements(requirements);
        return report;
    }

    // "Patient.setPassword()" -> "Patient.java"
    // "exampleresource.java"  -> "exampleresource.java" (already a filename)
    private List<MatchedFileDto> toMatchedFiles(String matchedCodeRaw) {
        List<MatchedFileDto> files = new ArrayList<>();
        if (matchedCodeRaw == null || matchedCodeRaw.isBlank()) {
            return files;
        }

        String ref = matchedCodeRaw;
        String fileName;

        if (ref.toLowerCase().endsWith(".java")) {
            fileName = ref;
        } else {
            int dot = ref.indexOf('.');
            String className = (dot > 0) ? ref.substring(0, dot) : ref;
            fileName = className + ".java";
        }

        MatchedFileDto file = new MatchedFileDto();
        file.setFilePath(fileName);
        files.add(file);
        return files;
    }

    private String normalizeWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
