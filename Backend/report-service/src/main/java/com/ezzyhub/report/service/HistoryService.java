package com.ezzyhub.report.service;

// NEW: this import was missing entirely in the original file, which is why
// nothing in this class actually compiled — AnalysisRecord is used
// throughout (new AnalysisRecord(), Optional<AnalysisRecord> getRecord(),
// etc.) but the compiler had no idea what that type was.
import com.ezzyhub.report.model.AnalysisRecord;
import com.ezzyhub.report.model.HistorySummary;
import com.ezzyhub.report.model.SaveRequest;
import com.ezzyhub.report.repository.AnalysisRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    @Autowired private AnalysisRecordRepository repo;
    @Autowired private PdfReportService pdfService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Save a new analysis record. Returns the persisted record's id. */
    public Long save(String userId, SaveRequest req) {
        AnalysisRecord record = new AnalysisRecord();
        record.setUserId(userId);
        record.setProjectName(req.getProjectName());
        record.setAnalysisType(resolveType(req));
        record.setStatus(req.getStatus());

        record.setTotalRequirements(req.getTotalRequirements());
        record.setImplementedCount(req.getImplementedCount());
        record.setPartialCount(req.getPartialCount());
        record.setMissingCount(req.getMissingCount());
        record.setCoveragePercentage(req.getCoveragePercentage());
        record.setTotalSourceFiles(req.getTotalSourceFiles());
        record.setAnalyzedFileCount(req.getAnalyzedFileCount());

        record.setCodeAnalysisJson(toJson(req.getCodeAnalysisResult()));
        record.setMatchResultJson(toJson(req.getMatchResult()));
        record.setSonarResultJson(toJson(req.getSonarResult()));
        record.setAiSuggestionsJson(toJson(req.getAiSuggestions()));

        AnalysisRecord saved = repo.save(record);
        log.info("Saved analysis record id={} type={} user={}", saved.getId(), saved.getAnalysisType(), userId);
        return saved.getId();
    }

    /** List summaries for a user (no payload blobs). */
    public List<HistorySummary> list(String userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId)
                   .stream()
                   .map(HistorySummary::from)
                   .collect(Collectors.toList());
    }

    /** Full record (with JSON payloads). */
    public Optional<AnalysisRecord> getRecord(Long id, String userId) {
        return repo.findByIdAndUserId(id, userId);
    }

    /** Delete a record if it belongs to the user. Returns true if deleted. */
    public boolean delete(Long id, String userId) {
        Optional<AnalysisRecord> opt = repo.findByIdAndUserId(id, userId);
        if (opt.isEmpty()) return false;
        repo.delete(opt.get());
        log.info("Deleted analysis record id={} user={}", id, userId);
        return true;
    }

    /** Generate PDF bytes for a record. */
    public byte[] generatePdf(Long id, String userId) {
        AnalysisRecord record = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Record not found: " + id));
        return pdfService.generatePdf(record);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            // If it's already a string (shouldn't be, but guard against it)
            if (obj instanceof String s) return s.isBlank() ? null : s;
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Could not serialise payload to JSON: {}", e.getMessage());
            return null;
        }
    }

    private String resolveType(SaveRequest req) {
        if (req.getAnalysisType() != null && !req.getAnalysisType().isBlank())
            return req.getAnalysisType();
        // Infer from what data is present
        boolean hasCode  = req.getCodeAnalysisResult() != null;
        boolean hasMatch = req.getMatchResult() != null;
        boolean hasSonar = req.getSonarResult() != null;
        if (hasCode && hasMatch) return "COMBINED";
        if (hasCode)  return "CODE_ANALYSIS";
        if (hasMatch) return "REQUIREMENT_MATCH";
        if (hasSonar) return "SONAR";
        return "UNKNOWN";
    }
}
