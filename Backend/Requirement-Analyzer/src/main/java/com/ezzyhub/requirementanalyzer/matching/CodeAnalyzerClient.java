package com.ezzyhub.requirementanalyzer.matching;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

/**
 * HTTP client that calls the Code Analyzer microservice (port 8082).
 *
 * Endpoint consumed:
 *   POST /api/v1/analyze  (multipart/form-data, field "file")
 *   → returns AnalysisResult JSON
 *
 * FIX — Streaming upload (no full file in RAM):
 *   The original code called file.getBytes() to build a ByteArrayResource.
 *   For a 1 GB ZIP this allocates 1 GB of heap on the Requirement Analyzer's
 *   JVM just to forward the bytes to the Code Analyzer.
 *
 *   The fix wraps the MultipartFile's InputStream in an InputStreamResource
 *   and supplies the known content-length from file.getSize(). Spring's WebClient
 *   streams the bytes in chunks without copying them into a byte array.
 *
 *   For very large uploads (close to JVM heap) this is the difference between
 *   OutOfMemoryError and a successful request.
 */
@Slf4j
@Service
public class CodeAnalyzerClient {

    private final WebClient webClient;

    public CodeAnalyzerClient(
            @Value("${code.analyzer.base-url:http://localhost:8082}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                // 64 MB buffer for the JSON response from the Code Analyzer.
                // The actual ZIP is NOT buffered here — it is streamed.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                .build();
        log.info("CodeAnalyzerClient configured → {}", baseUrl);
    }

    // ── Response mirror classes ────────────────────────────────────────────────

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnalysisResult {
        private String id;
        private String status;
        private String projectName;
        @JsonProperty("processingTimeMs") private long processingTimeMs;
        @JsonProperty("llmProvider")      private String llmProvider;
        @JsonProperty("llmModel")         private String llmModel;
        @JsonProperty("totalSourceFiles") private int totalSourceFiles;
        @JsonProperty("analyzedFileCount") private int analyzedFileCount;
        @JsonProperty("fileAnalyses")     private List<FileSemanticAnalysis> fileAnalyses;
        private String error;
    }

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileSemanticAnalysis {
        private String filePath;
        private String fileName;
        private String language;
        @JsonProperty("semanticRole") private String semanticRole;
        private List<String> imports;
        private List<ClassSemantic> classes;
        @JsonProperty("moduleFunctions") private List<MethodSemantic> moduleFunctions;
    }

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClassSemantic {
        private String name;
        private String kind;
        @JsonProperty("semanticMeaning") private String semanticMeaning;
        private List<FieldSemantic> fields;
        private List<MethodSemantic> methods;
    }

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MethodSemantic {
        private String name;
        private String signature;
        private String visibility;
        private boolean isAsync;
        private boolean isStatic;
        private String returnType;
        @JsonProperty("semanticMeaning") private String semanticMeaning;
    }

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldSemantic {
        private String name;
        private String type;
        private String visibility;
        @JsonProperty("semanticMeaning") private String semanticMeaning;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Streams the ZIP file to the code analyzer and returns the analysis result.
     *
     * FIX: Uses InputStreamResource so the bytes flow directly from the
     *      client's upload buffer to the downstream HTTP request without
     *      being copied into a byte array first.
     *
     * @throws RuntimeException if the code analyzer is unreachable or returns an error
     */
    public AnalysisResult analyze(MultipartFile file) {
        log.info("Calling code-analyzer /api/v1/analyze — file: {} ({} bytes)",
                file.getOriginalFilename(), file.getSize());

        try {
            String filename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "upload.zip";

            /*
             * FIX: Stream from the MultipartFile's InputStream.
             *
             * InputStreamResource.contentLength() returns file.getSize() so Spring
             * can set the correct Content-Length header, avoiding chunked encoding
             * for servers that require a known length.
             *
             * If file.getSize() returns -1 (unknown length), Spring falls back to
             * chunked transfer encoding automatically — still no byte[] in memory.
             */
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file",
                    new InputStreamResource(file.getInputStream()) {
                        @Override public String getFilename()   { return filename; }
                        @Override public long   contentLength() { return file.getSize(); }
                    },
                    MediaType.APPLICATION_OCTET_STREAM);

            AnalysisResult result = webClient.post()
                    .uri("/api/v1/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(AnalysisResult.class)
                    // LLM calls for large projects can be slow — allow up to 15 minutes
                    .timeout(Duration.ofMinutes(15))
                    .block();

            if (result == null) {
                throw new RuntimeException("Code Analyzer returned null response");
            }
            if ("error".equals(result.getStatus())) {
                throw new RuntimeException("Code Analyzer error: " + result.getError());
            }

            log.info("Code-analyzer returned {} file analyses, status={}",
                    result.getFileAnalyses() != null ? result.getFileAnalyses().size() : 0,
                    result.getStatus());
            return result;

        } catch (WebClientResponseException e) {
            log.error("Code-analyzer HTTP error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Code Analyzer HTTP " + e.getStatusCode()
                    + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Code-analyzer call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Code Analyzer unreachable: " + e.getMessage());
        }
    }
}
