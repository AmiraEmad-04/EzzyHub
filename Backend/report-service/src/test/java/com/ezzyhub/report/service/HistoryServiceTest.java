package com.ezzyhub.report.service;

import com.ezzyhub.report.model.AnalysisRecord;
import com.ezzyhub.report.model.SaveRequest;
import com.ezzyhub.report.repository.AnalysisRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HistoryService} with a mocked repository and PDF service.
 */
class HistoryServiceTest {

    private AnalysisRecordRepository repo;
    private PdfReportService pdfService;
    private HistoryService service;

    @BeforeEach
    void setUp() {
        repo = mock(AnalysisRecordRepository.class);
        pdfService = mock(PdfReportService.class);
        service = new HistoryService();
        ReflectionTestUtils.setField(service, "repo", repo);
        ReflectionTestUtils.setField(service, "pdfService", pdfService);
    }

    private static SaveRequest saveRequest() {
        SaveRequest r = new SaveRequest();
        r.setProjectName("demo");
        r.setStatus("success");
        r.setTotalRequirements(10);
        r.setImplementedCount(6);
        r.setCoveragePercentage(60.0);
        return r;
    }

    @Test
    void save_mapsFieldsSerializesPayloads_andReturnsId() {
        SaveRequest req = saveRequest();
        req.setCodeAnalysisResult(Map.of("k", "v"));
        req.setMatchResult(Map.of("coverage", 60));

        AnalysisRecord persisted = recordWithId(42L);
        when(repo.save(any(AnalysisRecord.class))).thenReturn(persisted);

        Long id = service.save("user-1", req);

        assertThat(id).isEqualTo(42L);
        ArgumentCaptor<AnalysisRecord> captor = ArgumentCaptor.forClass(AnalysisRecord.class);
        verify(repo).save(captor.capture());
        AnalysisRecord saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getProjectName()).isEqualTo("demo");
        assertThat(saved.getAnalysisType()).isEqualTo("COMBINED");
        assertThat(saved.getCodeAnalysisJson()).contains("\"k\":\"v\"");
        assertThat(saved.getMatchResultJson()).contains("coverage");
    }

    @Test
    void save_respectsExplicitAnalysisType() {
        SaveRequest req = saveRequest();
        req.setAnalysisType("SONAR");
        when(repo.save(any())).thenReturn(recordWithId(1L));

        service.save("u", req);

        ArgumentCaptor<AnalysisRecord> captor = ArgumentCaptor.forClass(AnalysisRecord.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getAnalysisType()).isEqualTo("SONAR");
    }

    @Test
    void save_infersType_fromPresentPayloads() {
        assertThat(inferTypeFor(r -> r.setCodeAnalysisResult(Map.of("x", 1)))).isEqualTo("CODE_ANALYSIS");
        assertThat(inferTypeFor(r -> r.setMatchResult(Map.of("x", 1)))).isEqualTo("REQUIREMENT_MATCH");
        assertThat(inferTypeFor(r -> r.setSonarResult(Map.of("x", 1)))).isEqualTo("SONAR");
        assertThat(inferTypeFor(r -> { })).isEqualTo("UNKNOWN");
    }

    private String inferTypeFor(java.util.function.Consumer<SaveRequest> mutate) {
        SaveRequest req = new SaveRequest();
        req.setProjectName("p");
        mutate.accept(req);
        when(repo.save(any())).thenReturn(recordWithId(1L));
        service.save("u", req);
        ArgumentCaptor<AnalysisRecord> captor = ArgumentCaptor.forClass(AnalysisRecord.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        return captor.getValue().getAnalysisType();
    }

    @Test
    void list_mapsRecordsToSummaries() {
        when(repo.findByUserIdOrderByCreatedAtDesc("u"))
                .thenReturn(List.of(recordWithId(1L), recordWithId(2L)));
        assertThat(service.list("u")).hasSize(2);
        verify(repo).findByUserIdOrderByCreatedAtDesc("u");
    }

    @Test
    void getRecord_delegatesToRepository() {
        AnalysisRecord rec = recordWithId(5L);
        when(repo.findByIdAndUserId(5L, "u")).thenReturn(Optional.of(rec));
        assertThat(service.getRecord(5L, "u")).contains(rec);
    }

    @Test
    void delete_removesWhenOwnedByUser() {
        AnalysisRecord rec = recordWithId(5L);
        when(repo.findByIdAndUserId(5L, "u")).thenReturn(Optional.of(rec));
        assertThat(service.delete(5L, "u")).isTrue();
        verify(repo).delete(rec);
    }

    @Test
    void delete_returnsFalse_whenNotFound_andDoesNotDelete() {
        when(repo.findByIdAndUserId(9L, "u")).thenReturn(Optional.empty());
        assertThat(service.delete(9L, "u")).isFalse();
        verify(repo, never()).delete(any());
    }

    @Test
    void generatePdf_returnsBytesFromPdfService() {
        AnalysisRecord rec = recordWithId(3L);
        when(repo.findByIdAndUserId(3L, "u")).thenReturn(Optional.of(rec));
        when(pdfService.generatePdf(rec)).thenReturn(new byte[]{1, 2, 3});
        assertThat(service.generatePdf(3L, "u")).containsExactly(1, 2, 3);
    }

    @Test
    void generatePdf_throwsWhenRecordMissing() {
        when(repo.findByIdAndUserId(3L, "u")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generatePdf(3L, "u"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(pdfService, never()).generatePdf(any());
    }

    private static AnalysisRecord recordWithId(Long id) {
        AnalysisRecord r = new AnalysisRecord();
        r.setId(id);
        r.setUserId("u");
        r.setProjectName("p");
        r.setAnalysisType("CODE_ANALYSIS");
        r.setCreatedAt(Instant.now());
        return r;
    }
}
