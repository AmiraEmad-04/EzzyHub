package com.codeanalyzer.service;

import com.codeanalyzer.config.AppConfig;
import com.codeanalyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    @Autowired private ZipExtractorService     zipExtractorService;
    @Autowired private ProjectStructureService projectStructureService;
    @Autowired private LLMService              llmService;
    @Autowired private AppConfig               appConfig;

    public AnalysisResult analyze(MultipartFile file) {
        long   start = System.currentTimeMillis();
        String id    = UUID.randomUUID().toString();
        Path   tmp   = null;

        log.info("[{}] Starting: {} ({} bytes)", id, file.getOriginalFilename(), file.getSize());

        AnalysisResult result = new AnalysisResult();
        result.setId(id);

        try {
            // Validate size
            long maxMB = appConfig.getLong("MAX_ZIP_SIZE_MB", 500);
            if (file.getSize() > maxMB * 1024 * 1024)
                throw new IllegalArgumentException("ZIP exceeds " + maxMB + " MB limit");

            String rawName   = file.getOriginalFilename();
            String projName  = rawName != null
                    ? rawName.replaceAll("\\.(zip|tar\\.gz|tgz)$","").replaceAll("[_-]+"," ").trim()
                    : "Unnamed Project";
            result.setProjectName(projName);

            // Step 1 — save to temp
            log.info("[{}] Step 1/4: Saving ZIP", id);
            tmp = zipExtractorService.saveToTempFile(file);

            // Step 2 — extract
            log.info("[{}] Step 2/4: Extracting", id);
            Map<String, String> contents = zipExtractorService.extractContents(tmp);
            if (contents.isEmpty()) throw new IllegalArgumentException("ZIP is empty");

            // Step 3 — structure
            log.info("[{}] Step 3/4: Building structure ({} entries)", id, contents.size());
            ProjectStructure structure = projectStructureService.buildStructure(contents, projName);
            result.setStructure(structure);

            long srcCount = contents.keySet().stream()
                    .filter(p -> p.matches(".*\\.(ts|tsx|js|java|py|go|rs|cs|rb|kt|swift|dart|php|cpp|c)$"))
                    .count();
            result.setTotalSourceFiles((int) srcCount);

            String[] pInfo = llmService.getProviderInfo().split("/", 2);
            result.setLlmProvider(pInfo[0]);
            result.setLlmModel(pInfo.length > 1 ? pInfo[1] : "unknown");

            // Step 4 — semantic analysis (summary + file batches)
            log.info("[{}] Step 4/4: Semantic analysis", id);

            // 4a — project summary
            try {
                SemanticAnalysis summary = llmService.summarizeProject(structure, contents);
                result.setProjectSummary(summary);
            } catch (Exception e) {
                log.warn("[{}] Project summary failed: {}", id, e.getMessage());
                // Generic message only — the underlying exception (which may embed a raw
                // third-party LLM API error body) is logged above, not returned to the client.
                SemanticAnalysis fallback = new SemanticAnalysis();
                fallback.setSummary("Project summary is unavailable.");
                result.setProjectSummary(fallback);
            }

            // 4b — file semantic analysis (batched)
            List<FileSemanticAnalysis> fileAnalyses = llmService.analyzeFiles(contents);
            result.setFileAnalyses(fileAnalyses);
            result.setAnalyzedFileCount(fileAnalyses.size());

            result.setStatus(fileAnalyses.isEmpty() ? "partial" : "success");
            log.info("[{}] Done — {} files analyzed", id, fileAnalyses.size());

        } catch (Exception e) {
            log.error("[{}] Fatal: {}", id, e.getMessage(), e);
            result.setStatus(result.getStructure() != null ? "partial" : "error");
            // IllegalArgumentException messages here are our own deliberate, safe
            // validation text (e.g. size/entry-count/empty-zip checks) - safe to echo back.
            // Anything else (IOException, provider errors, etc.) may carry internal details
            // (file paths, raw API bodies) and is logged above rather than returned.
            result.setError(e instanceof IllegalArgumentException
                    ? e.getMessage()
                    : "Analysis failed due to an internal error while processing the file.");
        } finally {
            zipExtractorService.cleanup(tmp);
            result.setProcessingTimeMs(System.currentTimeMillis() - start);
            log.info("[{}] Total time: {}ms", id, result.getProcessingTimeMs());
        }

        return result;
    }
}