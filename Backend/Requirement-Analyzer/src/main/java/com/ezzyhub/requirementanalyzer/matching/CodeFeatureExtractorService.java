package com.ezzyhub.requirementanalyzer.matching;

import com.ezzyhub.requirementanalyzer.service.PythonNlpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Extracts CodeFeature objects from the Code Analyzer's AnalysisResult,
 * then enriches each feature with NLP keywords + SBERT embedding by calling
 * the Python microservice.
 *
 * FIX — Parallel Python NLP calls:
 *
 *   The original code called pythonNlpClient.preprocessAndEmbed() inside a
 *   sequential for-loop. For a project with 50 classes × 10 methods each,
 *   this produces 500 + 50 = 550 features, and the feature-extraction phase
 *   takes 550 × (Python round-trip latency ~200 ms) ≈ 110 seconds just for
 *   HTTP overhead, before any LLM work.
 *
 *   The fix submits all embedding calls to a bounded thread pool
 *   (default: 8 threads, configurable via FEATURE_EMBED_THREADS env var).
 *   This reduces wall-clock time from O(n × latency) to O(n/threads × latency).
 *   For 550 features with 8 threads: ~14 seconds.
 *
 *   The thread count is capped to avoid overwhelming the Python service.
 *   The Python FastAPI server handles concurrent requests fine; Word2Vec
 *   and SBERT models are loaded into memory once and accessed without GIL
 *   contention for inference.
 *
 * Thread-safety:
 *   Results are collected into a CopyOnWriteArrayList and the executor is
 *   shut down with a generous timeout. Each submitted task is independent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeFeatureExtractorService {

    private final PythonNlpClient pythonNlpClient;

    /** Maximum concurrent calls to the Python NLP service. */
    @Value("${feature.embed.threads:8}")
    private int embedThreads;

    public List<CodeFeature> extract(CodeAnalyzerClient.AnalysisResult analysisResult) {
        if (analysisResult.getFileAnalyses() == null) {
            log.warn("Code analyzer returned no fileAnalyses — nothing to extract");
            return Collections.emptyList();
        }

        // Step 1: Collect all (filePath, methodName, semanticText) tuples from the result
        List<FeatureTuple> tuples = collectTuples(analysisResult);
        if (tuples.isEmpty()) {
            log.warn("No semantic text found in any file analysis");
            return Collections.emptyList();
        }

        log.info("Embedding {} code features using {} parallel threads", tuples.size(), embedThreads);

        // Step 2: Submit all embedding calls in parallel
        int threads = Math.max(1, Math.min(embedThreads, tuples.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<CodeFeature>> futures = new ArrayList<>(tuples.size());

        for (FeatureTuple t : tuples) {
            futures.add(executor.submit(() -> buildFeature(t)));
        }

        executor.shutdown();
        try {
            // Allow up to 10 minutes total for all embedding calls
            if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                log.warn("Feature embedding timed out after 10 minutes — collecting partial results");
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Feature embedding interrupted");
        }

        // Step 3: Collect results, skip failed futures
        List<CodeFeature> features = new ArrayList<>(tuples.size());
        int failed = 0;
        for (Future<CodeFeature> f : futures) {
            try {
                CodeFeature cf = f.get(0, TimeUnit.MILLISECONDS); // already done, no wait
                if (cf != null) features.add(cf);
            } catch (Exception e) {
                failed++;
                log.debug("Feature embedding future error: {}", e.getMessage());
            }
        }

        log.info("Extracted {} code features ({} failed) from {} files",
                features.size(), failed, analysisResult.getFileAnalyses().size());

        if (features.isEmpty() && !tuples.isEmpty()) {
            log.error("CRITICAL: All {} feature embeddings failed. " +
                    "Check that the Python NLP service is running on port 8000 and " +
                    "responding to POST /preprocess-and-embed", tuples.size());
        }

        return features;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Collects all (filePath, methodLabel, semanticText) tuples from the analysis
     * result without calling Python yet. This separates the collection step from
     * the parallel embedding step for clarity and testability.
     */
    private List<FeatureTuple> collectTuples(CodeAnalyzerClient.AnalysisResult analysisResult) {
        List<FeatureTuple> tuples = new ArrayList<>();

        for (CodeAnalyzerClient.FileSemanticAnalysis fsa : analysisResult.getFileAnalyses()) {
            if (fsa == null) continue;

            // File-level feature
            String fileText = fsa.getSemanticRole();
            if (fileText != null && !fileText.isBlank() && !fileText.startsWith("Analysis failed")) {
                tuples.add(new FeatureTuple(fsa.getFilePath(), null, fileText));
            }

            // Method-level features (per class)
            if (fsa.getClasses() != null) {
                for (CodeAnalyzerClient.ClassSemantic cls : fsa.getClasses()) {
                    if (cls == null || cls.getMethods() == null) continue;
                    for (CodeAnalyzerClient.MethodSemantic m : cls.getMethods()) {
                        if (m == null) continue;
                        String mText = m.getSemanticMeaning();
                        if (mText == null || mText.isBlank()) continue;
                        tuples.add(new FeatureTuple(
                                fsa.getFilePath(),
                                cls.getName() + "." + m.getName(),
                                mText));
                    }
                }
            }

            // Module-level functions (top-level, outside classes)
            if (fsa.getModuleFunctions() != null) {
                for (CodeAnalyzerClient.MethodSemantic m : fsa.getModuleFunctions()) {
                    if (m == null) continue;
                    String mText = m.getSemanticMeaning();
                    if (mText == null || mText.isBlank()) continue;
                    tuples.add(new FeatureTuple(fsa.getFilePath(), m.getName(), mText));
                }
            }
        }

        return tuples;
    }

    private CodeFeature buildFeature(FeatureTuple t) {
        try {
            PythonNlpClient.PreprocessEmbedResult result =
                    pythonNlpClient.preprocessAndEmbed(t.semanticText());

            List<Float> embeddingList = new ArrayList<>(result.sentenceEmbedding().length);
            for (float v : result.sentenceEmbedding()) embeddingList.add(v);

            log.debug("Feature: {} | {} | {} keywords | {}d embedding",
                    t.filePath(),
                    t.methodName() != null ? t.methodName() : "(file-level)",
                    result.keywords().size(),
                    embeddingList.size());

            return CodeFeature.builder()
                    .filePath(t.filePath())
                    .methodName(t.methodName())
                    .semanticText(t.semanticText())
                    .keywords(result.keywords())
                    .embedding(embeddingList)
                    .build();
        } catch (Exception e) {
            log.warn("buildFeature failed for {} {}: {}", t.filePath(), t.methodName(), e.getMessage());
            return null; // filtered out in collect step
        }
    }

    /** Lightweight tuple to carry feature data before embedding. */
    private record FeatureTuple(String filePath, String methodName, String semanticText) {}
}
