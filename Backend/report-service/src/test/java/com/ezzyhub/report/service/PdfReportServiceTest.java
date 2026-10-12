package com.ezzyhub.report.service;

import com.ezzyhub.report.model.AnalysisRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link PdfReportService}: a valid record yields a non-empty PDF, and
 * records with missing/edge fields do not crash generation.
 */
class PdfReportServiceTest {

    private final PdfReportService service = new PdfReportService();

    private static boolean isPdf(byte[] bytes) {
        return bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    @Test
    void generatePdf_fullRecord_producesNonEmptyPdf() {
        AnalysisRecord r = new AnalysisRecord();
        r.setId(1L);
        r.setUserId("u");
        r.setProjectName("EzzyHub Demo");
        r.setAnalysisType("COMBINED");
        r.setStatus("success");
        r.setCreatedAt(Instant.now());
        r.setTotalRequirements(10);
        r.setImplementedCount(6);
        r.setPartialCount(2);
        r.setMissingCount(2);
        r.setCoveragePercentage(60.0);
        r.setTotalSourceFiles(20);
        r.setAnalyzedFileCount(18);
        r.setMatchResultJson("{\"requirementMatches\":[{\"reqId\":\"FR-1\",\"status\":\"IMPLEMENTED\"}]}");
        r.setCodeAnalysisJson("{\"fileAnalyses\":[{\"fileName\":\"A.java\",\"semanticRole\":\"does A\"}]}");

        byte[] pdf = service.generatePdf(r);

        assertThat(pdf).isNotEmpty();
        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void generatePdf_minimalRecord_missingFields_doesNotThrow() {
        AnalysisRecord r = new AnalysisRecord();
        r.setId(2L);
        r.setUserId("u");
        r.setProjectName("Minimal");
        r.setAnalysisType("CODE_ANALYSIS");
        r.setStatus("success");
        r.setCreatedAt(Instant.now());
        // counts, coverage and JSON payloads left null

        assertThatCode(() -> {
            byte[] pdf = service.generatePdf(r);
            assertThat(pdf).isNotEmpty();
            assertThat(isPdf(pdf)).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    void generatePdf_malformedJsonPayload_doesNotThrow() {
        AnalysisRecord r = new AnalysisRecord();
        r.setId(3L);
        r.setUserId("u");
        r.setProjectName("Broken JSON");
        r.setAnalysisType("REQUIREMENT_MATCH");
        r.setStatus("partial");
        r.setCreatedAt(Instant.now());
        r.setMatchResultJson("{ this is not valid json ]");

        assertThatCode(() -> assertThat(service.generatePdf(r)).isNotEmpty())
                .doesNotThrowAnyException();
    }
}
