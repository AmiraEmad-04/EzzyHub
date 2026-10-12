package com.ezzyhub.requirementanalyzer.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import reactor.util.retry.Retry;
import reactor.core.Exceptions;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * HTTP client that calls the Python FastAPI NLP microservice.
 *
 * Endpoints consumed:
 *   POST http://localhost:8000/preprocess  → tokenize, keywords, POS, synonyms
 *   POST http://localhost:8000/embed       → SBERT sentence + Word2Vec word embeddings
 *
 * Falls back gracefully if the Python service is unavailable:
 *   - preprocess: returns empty collections + warning
 *   - embed: returns zero vectors + warning
 */
@Slf4j
@Service
public class PythonNlpClient {

    private final WebClient webClient;

    public PythonNlpClient(@Value("${python.nlp.base-url:http://localhost:8000}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                // FIX: long requirements produce 30-50 keywords after synonym expansion.
                // Python /embed returns a 384-float BGE vector per keyword → the JSON
                // response easily exceeds Spring's default 256 KB codec buffer (e.g.
                // 42 keywords × 384 floats × ~19 chars ≈ 307 KB > 262,144 bytes).
                // DataBufferLimitException was caught as a generic Exception and silently
                // routed to fallbackEmbed(), returning new float[384] (all zeros).
                // Raising the limit to 16 MB (matching AppConfig) prevents the overflow.
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        log.info("PythonNlpClient configured → {}", baseUrl);
    }

    // ─── ZERO-VECTOR FIX: retry + warm-up configuration ───────────────────────

    /** Per-attempt timeout for embedding calls (seconds). Cold model load can be slow. */
    private static final long EMBED_TIMEOUT_SECONDS = 300;

    /** How many times to retry a transient embedding failure before falling back. */
    private static final long EMBED_MAX_RETRIES = 3;

    /** Initial backoff between retries; doubles each attempt (0.5s → 1s → 2s). */
    private static final Duration EMBED_RETRY_BACKOFF = Duration.ofMillis(500);

    /**
     * Retry policy for embedding calls. Retries ONLY transient faults —
     * timeouts, connection resets, and 5xx server errors — the exact failures
     * that previously caused fallbackEmbed() to return an all-zero vector and
     * silently poison a requirement's score. A 4xx (bad request) is NOT retried
     * because retrying won't help. After the retries are exhausted the original
     * error propagates and the caller's catch block falls back.
     */
    private Retry embedRetrySpec(String label) {
        return Retry.backoff(EMBED_MAX_RETRIES, EMBED_RETRY_BACKOFF)
                .filter(this::isTransient)
                .doBeforeRetry(sig -> log.warn("Retrying Python {} (attempt {} of {}) after transient error: {}",
                        label, sig.totalRetries() + 1, EMBED_MAX_RETRIES,
                        sig.failure() != null ? sig.failure().getMessage() : "unknown"))
                .onRetryExhaustedThrow((spec, sig) -> sig.failure());
    }

    /** True if an error is worth retrying (timeout / connection / 5xx). */
    private boolean isTransient(Throwable t) {
        Throwable e = Exceptions.unwrap(t);
        if (e instanceof TimeoutException) return true;
        if (e instanceof WebClientRequestException) return true;   // connection reset/refused mid-flight
        if (e instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();        // server hiccup — retry
        }
        return false;
    }

    /** True if a sentence vector is null, empty, or entirely zeros (a failed embedding). */
    private static boolean isAllZeroOrEmpty(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float f : v) if (f != 0.0f) return false;
        return true;
    }

    /**
     * Warm the Python models on startup so the FIRST real embedding request is
     * fast instead of paying the cold model-load cost (which was what tripped the
     * timeout → zero-vector path). Best-effort and fully asynchronous: it never
     * blocks or fails application startup. If Python isn't up yet, the normal
     * retry logic on the first real call still covers us.
     */
    @PostConstruct
    public void warmUpAsync() {
        new Thread(() -> {
            try {
                log.info("Warm-up ▶ priming Python NLP models with a throwaway embed call...");
                long t0 = System.currentTimeMillis();
                webClient.post()
                        .uri("/embed")
                        .bodyValue(new EmbedRequest("warm up the embedding model", List.of("warm", "up")))
                        .retrieve()
                        .bodyToMono(EmbedResponse.class)
                        .timeout(Duration.ofSeconds(EMBED_TIMEOUT_SECONDS))
                        .retryWhen(embedRetrySpec("/embed(warmup)"))
                        .block();
                log.info("Warm-up ✓ Python models ready in {}ms — first real embed will be fast",
                        System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.warn("Warm-up skipped (Python not ready yet: {}). "
                        + "First real request will warm the model via normal retry.", e.getMessage());
            }
        }, "python-nlp-warmup").start();
    }

    // ─── Records (response DTOs) ──────────────────────────────────────────────

    public record PreprocessResult(
            List<String> tokens,
            List<String> keywords,
            List<String> actionVerbs,
            List<String> warnings
    ) {}

    public record EmbedResult(
            float[] sentenceEmbedding,
            Map<String, float[]> wordEmbeddings,
            List<String> oovKeywords
    ) {}

    // FIX BUG-3: Combined result — returned by preprocessAndEmbed() so callers
    // (CodeFeatureExtractorService) need only one HTTP round-trip per feature.
    public record PreprocessEmbedResult(
            List<String> tokens,
            List<String> keywords,
            List<String> actionVerbs,
            List<String> warnings,
            float[] sentenceEmbedding,
            Map<String, float[]> wordEmbeddings,
            List<String> oovKeywords
    ) {}

    // ─── Internal request POJOs ───────────────────────────────────────────────

    record PreprocessRequest(String text) {}

    record EmbedRequest(
            String sentence,
            List<String> keywords
    ) {}

    // FIX BUG-3: Request and response records for the combined endpoint
    record PreprocessEmbedRequest(String text) {}

    // Jackson response mapping (snake_case from Python)
    record PreprocessResponse(
            List<String> tokens,
            List<String> keywords,
            @JsonProperty("action_verbs") List<String> actionVerbs,
            List<String> warnings
    ) {}

    // FIX: Use List<Float> instead of List<Double> to avoid precision loss and
    // redundant boxing. Python JSON numbers for 32-bit floats fit in Java Float.
    // The old Double→floatValue() conversion silently lost precision on boundary values.
    record EmbedResponse(
            @JsonProperty("sentence_embedding") List<Float> sentenceEmbedding,
            @JsonProperty("word_embeddings")    Map<String, List<Float>> wordEmbeddings,
            @JsonProperty("oov_keywords")       List<String> oovKeywords
    ) {}

    // FIX BUG-3: Combined response — mirrors PreprocessEmbedResponse from Python
    record PreprocessEmbedResponse(
            List<String> tokens,
            List<String> keywords,
            @JsonProperty("action_verbs")       List<String> actionVerbs,
            List<String> warnings,
            @JsonProperty("sentence_embedding") List<Float> sentenceEmbedding,
            @JsonProperty("word_embeddings")    Map<String, List<Float>> wordEmbeddings,
            @JsonProperty("oov_keywords")       List<String> oovKeywords
    ) {}

    // ─── preprocess ──────────────────────────────────────────────────────────

    public PreprocessResult preprocess(String text) {
        try {
            PreprocessResponse resp = webClient.post()
                    .uri("/preprocess")
                    .bodyValue(new PreprocessRequest(text))
                    .retrieve()
                    .bodyToMono(PreprocessResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (resp == null) {
                log.error("Python /preprocess returned null response");
                return fallbackPreprocess();
            }

            log.debug("Python /preprocess OK: {} tokens, {} keywords, {} action verbs",
                    resp.tokens().size(), resp.keywords().size(), resp.actionVerbs().size());

            return new PreprocessResult(
                    resp.tokens(),
                    resp.keywords(),
                    resp.actionVerbs(),
                    resp.warnings()
            );

        } catch (WebClientResponseException e) {
            log.error("Python /preprocess HTTP error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return fallbackPreprocess();
        } catch (Exception e) {
            log.error("Python /preprocess unreachable: {} — is the Python microservice running on port 8000?", e.getMessage());
            return fallbackPreprocess();
        }
    }

    // FIX BUG-3: Combined preprocess + embed in a single HTTP call.
    // Replaces the pattern of calling preprocess() then embed() separately,
    // cutting HTTP round-trips per code feature from 2 → 1.
    // CodeFeatureExtractorService uses this instead of the two separate calls.
    public PreprocessEmbedResult preprocessAndEmbed(String text) {
        try {
            PreprocessEmbedResponse resp = webClient.post()
                    .uri("/preprocess-and-embed")
                    .bodyValue(new PreprocessEmbedRequest(text))
                    .retrieve()
                    .bodyToMono(PreprocessEmbedResponse.class)
                    .timeout(Duration.ofSeconds(EMBED_TIMEOUT_SECONDS))
                    // ZERO-VECTOR FIX: retry transient failures before falling back to zeros.
                    .retryWhen(embedRetrySpec("/preprocess-and-embed"))
                    .block();

            if (resp == null) {
                log.error("Python /preprocess-and-embed returned null response");
                return fallbackPreprocessAndEmbed();
            }

            float[] sentVec = toFloatArray(resp.sentenceEmbedding());

            // GUARD: a 200 OK with an all-zero/empty vector is a silent server-side
            // failure — retry once before accepting it, never store zeros as valid.
            if (isAllZeroOrEmpty(sentVec)) {
                log.warn("Python /preprocess-and-embed returned all-zero/empty vector — retrying once");
                PreprocessEmbedResponse retry = webClient.post()
                        .uri("/preprocess-and-embed")
                        .bodyValue(new PreprocessEmbedRequest(text))
                        .retrieve()
                        .bodyToMono(PreprocessEmbedResponse.class)
                        .timeout(Duration.ofSeconds(EMBED_TIMEOUT_SECONDS))
                        .retryWhen(embedRetrySpec("/preprocess-and-embed(retry)"))
                        .block();
                if (retry != null && !isAllZeroOrEmpty(toFloatArray(retry.sentenceEmbedding()))) {
                    resp = retry;
                    sentVec = toFloatArray(resp.sentenceEmbedding());
                    log.info("Python /preprocess-and-embed retry succeeded with a real vector");
                } else {
                    log.error("Python /preprocess-and-embed still zeros after retry — using fallback");
                    return fallbackPreprocessAndEmbed();
                }
            }

            Map<String, float[]> wordVecs = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<Float>> entry : resp.wordEmbeddings().entrySet()) {
                wordVecs.put(entry.getKey(), toFloatArray(entry.getValue()));
            }

            log.debug("Python /preprocess-and-embed OK: {} keywords, {}-dim sentence vector",
                    resp.keywords().size(), sentVec.length);

            return new PreprocessEmbedResult(
                    resp.tokens(),
                    resp.keywords(),
                    resp.actionVerbs(),
                    resp.warnings(),
                    sentVec,
                    wordVecs,
                    resp.oovKeywords()
            );

        } catch (WebClientResponseException e) {
            log.error("Python /preprocess-and-embed HTTP error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return fallbackPreprocessAndEmbed();
        } catch (Exception e) {
            log.error("Python /preprocess-and-embed unreachable after retries: {}", e.getMessage());
            return fallbackPreprocessAndEmbed();
        }
    }

    // ─── embed ───────────────────────────────────────────────────────────────

    public EmbedResult embed(String sentence, List<String> keywords) {
        try {
            EmbedResponse resp = webClient.post()
                    .uri("/embed")
                    .bodyValue(new EmbedRequest(sentence, keywords))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    // FIX: increased from 60s to 300s.
                    // Word2Vec google-news-300 (~1.6 GB) is downloaded and loaded on first
                    // call to the Python service. On a cold start this can take 2-5 minutes.
                    // Subsequent calls are fast (model is cached in memory) but the very
                    // first request must not time out and force a fallback to zero vectors.
                    .timeout(Duration.ofSeconds(EMBED_TIMEOUT_SECONDS))
                    // ZERO-VECTOR FIX: retry transient failures (timeout / 5xx / connection
                    // reset) BEFORE giving up. The single biggest source of exact-zero
                    // embeddings was the first embed call after idle hitting the timeout
                    // while the SBERT model warmed up, then fallbackEmbed() silently
                    // returning new float[384]. Retrying with backoff lets that slow first
                    // call succeed on attempt 2/3 instead of poisoning the requirement with
                    // a zero vector. Only genuinely transient errors are retried.
                    .retryWhen(embedRetrySpec("/embed"))
                    .block();

            if (resp == null) {
                log.error("Python /embed returned null response");
                return fallbackEmbed(keywords);
            }

            float[] sentVec = toFloatArray(resp.sentenceEmbedding());
            Map<String, float[]> wordVecs = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<Float>> entry : resp.wordEmbeddings().entrySet()) {
                wordVecs.put(entry.getKey(), toFloatArray(entry.getValue()));
            }

            // GUARD: even a 200 OK can carry an all-zero / empty vector if the model
            // silently failed server-side. Treat that as a failure and retry once more
            // synchronously via a direct re-call, rather than accepting zeros.
            if (isAllZeroOrEmpty(sentVec)) {
                log.warn("Python /embed returned an all-zero/empty sentence vector — "
                        + "retrying once before falling back");
                EmbedResponse retry = webClient.post()
                        .uri("/embed")
                        .bodyValue(new EmbedRequest(sentence, keywords))
                        .retrieve()
                        .bodyToMono(EmbedResponse.class)
                        .timeout(Duration.ofSeconds(EMBED_TIMEOUT_SECONDS))
                        .retryWhen(embedRetrySpec("/embed(retry)"))
                        .block();
                if (retry != null) {
                    float[] retryVec = toFloatArray(retry.sentenceEmbedding());
                    if (!isAllZeroOrEmpty(retryVec)) {
                        Map<String, float[]> rWordVecs = new java.util.LinkedHashMap<>();
                        for (Map.Entry<String, List<Float>> entry : retry.wordEmbeddings().entrySet())
                            rWordVecs.put(entry.getKey(), toFloatArray(entry.getValue()));
                        log.info("Python /embed retry succeeded with a real vector");
                        return new EmbedResult(retryVec, rWordVecs, retry.oovKeywords());
                    }
                }
                log.error("Python /embed still returned zeros after retry — using fallback");
                return fallbackEmbed(keywords);
            }

            log.debug("Python /embed OK: {}-dim sentence vector, {} word vectors",
                    sentVec.length, wordVecs.size());

            return new EmbedResult(sentVec, wordVecs, resp.oovKeywords());

        } catch (WebClientResponseException e) {
            log.error("Python /embed HTTP error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return fallbackEmbed(keywords);
        } catch (Exception e) {
            log.error("Python /embed unreachable after retries: {} — is the Python microservice running on port 8000?", e.getMessage());
            return fallbackEmbed(keywords);
        }
    }

    // ─── Fallbacks ────────────────────────────────────────────────────────────

    private PreprocessResult fallbackPreprocess() {
        return new PreprocessResult(
                List.of(),
                List.of(),
                List.of(),
                List.of("Python NLP microservice unavailable — preprocessing skipped. Start the Python service.")
        );
    }

    private EmbedResult fallbackEmbed(List<String> keywords) {
        Map<String, float[]> zeroVecs = new java.util.LinkedHashMap<>();
        for (String kw : keywords) {
            zeroVecs.put(kw, new float[300]);
        }
        return new EmbedResult(
                new float[384],
                zeroVecs,
                keywords  // all are OOV in fallback
        );
    }

    // FIX BUG-3: Fallback for the combined endpoint — mirrors fallbackPreprocess + fallbackEmbed
    private PreprocessEmbedResult fallbackPreprocessAndEmbed() {
        return new PreprocessEmbedResult(
                List.of(),
                List.of(),
                List.of(),
                List.of("Python NLP microservice unavailable — preprocess-and-embed skipped. Start the Python service."),
                new float[384],
                new java.util.LinkedHashMap<>(),
                List.of()
        );
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    // FIX: accepts List<Float> (was List<Double>) — no precision loss, no redundant boxing
    private float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
