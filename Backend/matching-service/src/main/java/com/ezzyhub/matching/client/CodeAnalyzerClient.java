package com.ezzyhub.matching.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for Code-Analyzer (:8080).
 *
 * Calls POST /api/v1/analyze and turns each file's semantic description
 * (semanticRole + dependency names) into a small set of extra keywords,
 * keyed by file name. Matching uses these to ENRICH the jaccard side of the
 * score with behaviour words instead of identifiers alone.
 *
 * This is strictly best-effort: ANY failure (Code-Analyzer down, slow,
 * empty output, parse error) returns an empty map and the matching pipeline
 * proceeds exactly as it did before. It can never break a working match.
 */
@Slf4j
@Component
public class CodeAnalyzerClient {

    private final WebClient webClient;

    public CodeAnalyzerClient(@Qualifier("codeAnalyzerWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * @return map of lowercased fileName -> description keywords; empty map on any failure.
     */
    public Map<String, List<String>> fetchFileKeywords(MultipartFile zip) {
        try {
            byte[] bytes = zip.getBytes();   // getBytes() is buffered & repeatable — safe to reuse after Python already read it
            String filename = zip.getOriginalFilename() != null ? zip.getOriginalFilename() : "code.zip";

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            });

            AnalyzeResponse resp = webClient.post()
                    .uri("/api/v1/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(AnalyzeResponse.class)
                    .block();

            if (resp == null || resp.fileAnalyses == null || resp.fileAnalyses.isEmpty()) {
                log.warn("Code-Analyzer returned no file analyses — matching continues with Python keywords only");
                return Collections.emptyMap();
            }

            Map<String, List<String>> out = new HashMap<>();
            for (FileAnalysis fa : resp.fileAnalyses) {
                if (fa.fileName == null || fa.fileName.isBlank()) continue;
                List<String> kws = new ArrayList<>();
                addWords(kws, fa.semanticRole);
                if (fa.capabilities != null) {
                    for (String c : fa.capabilities) addWords(kws, c);
                }
                if (fa.dependencies != null) {
                    for (String d : fa.dependencies) addWords(kws, lastSegment(d));
                }
                if (!kws.isEmpty()) out.put(fa.fileName.toLowerCase(), kws);
            }
            log.info("Code-Analyzer enrichment ✓ description keywords for {} files", out.size());
            return out;

        } catch (Exception e) {
            log.warn("Code-Analyzer call failed ({}) — matching continues with Python keywords only",
                    e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Like fetchFileKeywords, but returns the natural-language description text per file
     * (semanticRole + capabilities joined), suitable for embedding. Empty map on failure.
     */
    public Map<String, String> fetchFileDescriptions(MultipartFile zip) {
        try {
            byte[] bytes = zip.getBytes();
            String filename = zip.getOriginalFilename() != null ? zip.getOriginalFilename() : "code.zip";
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            });
            AnalyzeResponse resp = webClient.post()
                    .uri("/api/v1/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(AnalyzeResponse.class)
                    .block();
            if (resp == null || resp.fileAnalyses == null) return Collections.emptyMap();
            Map<String, String> out = new HashMap<>();
            for (FileAnalysis fa : resp.fileAnalyses) {
                if (fa.fileName == null || fa.fileName.isBlank()) continue;
                StringBuilder sb = new StringBuilder();
                if (fa.semanticRole != null) sb.append(fa.semanticRole).append(". ");
                if (fa.capabilities != null) sb.append(String.join(". ", fa.capabilities));
                String text = sb.toString().trim();
                if (!text.isEmpty()) out.put(fa.fileName.toLowerCase(), text);
            }
            log.info("Code-Analyzer descriptions ✓ {} files", out.size());
            return out;
        } catch (Exception e) {
            log.warn("Code-Analyzer descriptions failed ({})", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Returns structured facts (role, capabilities, limitations) per file, keyed by
     * lowercased fileName. Used by the rule-based verdict. Empty map on failure.
     */
    public Map<String, FileFacts> fetchFileFacts(MultipartFile zip) {
        try {
            byte[] bytes = zip.getBytes();
            String filename = zip.getOriginalFilename() != null ? zip.getOriginalFilename() : "code.zip";
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            });
            AnalyzeResponse resp = webClient.post()
                    .uri("/api/v1/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(AnalyzeResponse.class)
                    .block();
            if (resp == null || resp.fileAnalyses == null) return Collections.emptyMap();
            Map<String, FileFacts> out = new HashMap<>();
            for (FileAnalysis fa : resp.fileAnalyses) {
                if (fa.fileName == null || fa.fileName.isBlank()) continue;
                out.put(fa.fileName.toLowerCase(),
                        new FileFacts(fa.semanticRole, fa.capabilities, fa.limitations));
            }
            log.info("Code-Analyzer facts ✓ {} files", out.size());
            return out;
        } catch (Exception e) {
            log.warn("Code-Analyzer facts failed ({})", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static void addWords(List<String> acc, String text) {
        if (text == null) return;
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() > 2) acc.add(w);
        }
    }

    private static String lastSegment(String dep) {
        if (dep == null) return null;
        int i = dep.lastIndexOf('.');
        return (i >= 0 && i < dep.length() - 1) ? dep.substring(i + 1) : dep;
    }

    // ── minimal view of Code-Analyzer's AnalysisResult (only fields we use) ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AnalyzeResponse {
        public List<FileAnalysis> fileAnalyses;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FileAnalysis {
        public String fileName;
        public String semanticRole;
        public List<String> dependencies;
        public List<String> capabilities;
        public List<String> limitations;
    }

    /** Structured per-file facts for the rule-based verdict in matching. */
    public static class FileFacts {
        public final String semanticRole;
        public final List<String> capabilities;
        public final List<String> limitations;
        public FileFacts(String role, List<String> caps, List<String> lims) {
            this.semanticRole = role != null ? role : "";
            this.capabilities = caps != null ? caps : Collections.emptyList();
            this.limitations  = lims != null ? lims : Collections.emptyList();
        }
    }
}