package com.ezzyhub.requirementanalyzer.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Python NLP microservice (port 8000).
 *
 * v2 adds:
 *   rerank()   — calls POST /rerank  (cross-encoder re-ranking)
 *   validate() — calls POST /validate (HF Inference API validation)
 *
 * Security: X-API-Key header sent when NLP_SERVICE_KEY env var is set.
 */
@Slf4j
@Service
public class PythonNlpClient {

    private final WebClient webClient;

    public PythonNlpClient(
            @Value("${python.nlp.base-url:http://localhost:8000}") String baseUrl,
            @Value("${nlp.service.key:}") String serviceKey) {

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024));

        // Add service key header if configured
        if (serviceKey != null && !serviceKey.isBlank()) {
            builder.defaultHeader("X-API-Key", serviceKey);
        }

        this.webClient = builder.build();
        log.info("PythonNlpClient configured → {} (key={})", baseUrl,
                (serviceKey != null && !serviceKey.isBlank()) ? "SET" : "NOT SET");
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record PreprocessResult(
            List<String> tokens, List<String> keywords,
            List<String> actionVerbs, List<String> warnings) {}

    public record EmbedResult(
            float[] sentenceEmbedding,
            Map<String, float[]> wordEmbeddings,
            List<String> oovKeywords) {}

    public record PreprocessEmbedResult(
            List<String> tokens, List<String> keywords,
            List<String> actionVerbs, List<String> warnings,
            float[] sentenceEmbedding,
            Map<String, float[]> wordEmbeddings,
            List<String> oovKeywords) {}

    /** Result for one (requirement, candidate) pair from the cross-encoder. */
    public record RerankResult(int index, double score, String text) {}

    /** Result for requirement validation. */
    public record ValidateResult(
            boolean valid, double confidence,
            String reason, boolean multipleReqs) {}

    // ── Internal request/response records ────────────────────────────────────
    private record PreprocessRequest(String text) {}
    private record EmbedRequest(String sentence, List<String> keywords, String mode) {}
    private record PreprocessEmbedRequest(String text, String mode) {}

    private record RerankRequest(String requirement, List<String> candidates, int top_k) {}

    private record PreprocessResponse(
            List<String> tokens, List<String> keywords,
            @JsonProperty("action_verbs") List<String> actionVerbs,
            List<String> warnings) {}

    private record EmbedResponse(
            @JsonProperty("sentence_embedding")   List<Float> sentenceEmbedding,
            @JsonProperty("word_embeddings")       Map<String, List<Float>> wordEmbeddings,
            @JsonProperty("oov_keywords")          List<String> oovKeywords) {}

    private record PreprocessEmbedResponse(
            List<String> tokens, List<String> keywords,
            @JsonProperty("action_verbs")          List<String> actionVerbs,
            List<String> warnings,
            @JsonProperty("sentence_embedding")    List<Float> sentenceEmbedding,
            @JsonProperty("word_embeddings")       Map<String, List<Float>> wordEmbeddings,
            @JsonProperty("oov_keywords")          List<String> oovKeywords) {}

    private record RerankResultRaw(int index, double score, String text) {}

    private record RerankResponse(@JsonProperty("ranked") List<RerankResultRaw> ranked) {}

    private record ValidateResponse(
            boolean valid, double confidence,
            String reason,
            @JsonProperty("multiple_reqs") boolean multipleReqs) {}

    private record ValidateRequest(String text) {}

    // ── Public methods ────────────────────────────────────────────────────────

    public PreprocessResult preprocess(String text) {
        try {
            PreprocessResponse resp = post("/preprocess",
                    new PreprocessRequest(text), PreprocessResponse.class, 30);
            if (resp == null) return fallbackPreprocess();
            return new PreprocessResult(resp.tokens(), resp.keywords(),
                    resp.actionVerbs(), resp.warnings());
        } catch (Exception e) {
            log.error("Python /preprocess error: {}", e.getMessage());
            return fallbackPreprocess();
        }
    }

    public PreprocessEmbedResult preprocessAndEmbed(String text) {
        return preprocessAndEmbed(text, "requirement");
    }

    public PreprocessEmbedResult preprocessAndEmbed(String text, String mode) {
        try {
            PreprocessEmbedResponse resp = post("/preprocess-and-embed",
                    new PreprocessEmbedRequest(text, mode),
                    PreprocessEmbedResponse.class, 300);
            if (resp == null) return fallbackPreprocessAndEmbed();

            float[] sentVec = toFloatArray(resp.sentenceEmbedding());
            Map<String, float[]> wordVecs = toFloatMap(resp.wordEmbeddings());
            return new PreprocessEmbedResult(
                    resp.tokens(), resp.keywords(), resp.actionVerbs(), resp.warnings(),
                    sentVec, wordVecs, resp.oovKeywords());
        } catch (Exception e) {
            log.error("Python /preprocess-and-embed error: {}", e.getMessage());
            return fallbackPreprocessAndEmbed();
        }
    }

    public EmbedResult embed(String sentence, List<String> keywords) {
        return embed(sentence, keywords, "requirement");
    }

    public EmbedResult embed(String sentence, List<String> keywords, String mode) {
        try {
            EmbedResponse resp = post("/embed",
                    new EmbedRequest(sentence, keywords, mode),
                    EmbedResponse.class, 300);
            if (resp == null) return fallbackEmbed(keywords);
            return new EmbedResult(
                    toFloatArray(resp.sentenceEmbedding()),
                    toFloatMap(resp.wordEmbeddings()),
                    resp.oovKeywords());
        } catch (Exception e) {
            log.error("Python /embed error: {}", e.getMessage());
            return fallbackEmbed(keywords);
        }
    }

    /**
     * Cross-encoder re-ranking (NEW — Hugging Face).
     *
     * Sends (requirement, candidate[]) to Python /rerank endpoint.
     * Returns candidates ordered by cross-encoder relevance score (descending).
     *
     * Falls back gracefully — returns empty list if Python service is down.
     * MatchingService treats an empty list as "skip reranking, use bi-encoder order".
     */
    public List<RerankResult> rerank(String requirement, List<String> candidates, int topK) {
        try {
            RerankResponse resp = post("/rerank",
                    new RerankRequest(requirement, candidates, topK),
                    RerankResponse.class, 30);
            if (resp == null || resp.ranked() == null) return List.of();
            return resp.ranked().stream()
                    .map(r -> new RerankResult(r.index(), r.score(), r.text()))
                    .toList();
        } catch (Exception e) {
            log.warn("Python /rerank failed (non-fatal): {}", e.getMessage());
            return List.of();  // caller falls back to bi-encoder ranking
        }
    }

    /**
     * Requirement validation via HF Inference API (NEW).
     * Called by AiSemanticValidator when USE_HF_VALIDATOR=true.
     */
    public ValidateResult validateViaHF(String text) {
        try {
            ValidateResponse resp = post("/validate",
                    new ValidateRequest(text), ValidateResponse.class, 30);
            if (resp == null) return new ValidateResult(true, 0.5, "HF validator unavailable", false);
            return new ValidateResult(resp.valid(), resp.confidence(), resp.reason(), resp.multipleReqs());
        } catch (Exception e) {
            log.warn("Python /validate failed: {}", e.getMessage());
            return new ValidateResult(true, 0.5, "Validation skipped (service unavailable)", false);
        }
    }

    // ── Generic POST helper ───────────────────────────────────────────────────

    private <T> T post(String path, Object body, Class<T> type, int timeoutSeconds) {
        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(type)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    private PreprocessResult fallbackPreprocess() {
        return new PreprocessResult(List.of(), List.of(), List.of(),
                List.of("Python NLP service unavailable — start it on port 8000"));
    }

    private EmbedResult fallbackEmbed(List<String> keywords) {
        Map<String, float[]> zeros = new java.util.LinkedHashMap<>();
        for (String kw : keywords) zeros.put(kw, new float[384]);
        return new EmbedResult(new float[384], zeros, keywords);
    }

    private PreprocessEmbedResult fallbackPreprocessAndEmbed() {
        return new PreprocessEmbedResult(List.of(), List.of(), List.of(),
                List.of("Python NLP service unavailable"),
                new float[384], new java.util.LinkedHashMap<>(), List.of());
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    private float[] toFloatArray(List<Float> list) {
        if (list == null) return new float[0];
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private Map<String, float[]> toFloatMap(Map<String, List<Float>> map) {
        if (map == null) return new java.util.LinkedHashMap<>();
        Map<String, float[]> result = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> result.put(k, toFloatArray(v)));
        return result;
    }
}
