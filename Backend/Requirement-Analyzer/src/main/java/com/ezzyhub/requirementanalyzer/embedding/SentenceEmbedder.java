package com.ezzyhub.requirementanalyzer.embedding;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * PHASE 4 - STEP 12: Sentence Embedding (Java-side fallback only)
 *
 * FIX (Critical): The PRIMARY sentence embedding path is the Python microservice
 * (SBERT all-MiniLM-L6-v2 via sentence-transformers). Java calls
 * PythonNlpClient.embed() which returns real 384-dim dense SBERT vectors.
 * This class is a LOCAL FALLBACK used only when the Python service is unavailable.
 *
 * The original code attempted to use DJL to load Sentence-BERT but
 * embedWithSentenceBert() unconditionally threw UnsupportedOperationException,
 * meaning EVERY call fell through to TF-IDF. embedding.model.enabled=false
 * in application.properties confirmed DJL was never initialised.
 * The misleading djlAvailable flag has been removed.
 *
 * TF-IDF fallback is clearly labelled as such in logs and in the response
 * warnings so callers are not misled about embedding quality.
 *
 * Cosine similarity utility is kept here for use by the matching layer (FR-7).
 */
@Slf4j
@Component
public class SentenceEmbedder {

    @Value("${embedding.model.enabled:false}")
    private boolean embeddingEnabled;

    /**
     * Public entry point — delegates to embedFallback (TF-IDF).
     * Used directly by integration tests and any caller that does not go
     * through PythonNlpClient (e.g. when the Python service is unavailable).
     */
    public float[] embed(String text) {
        return embedFallback(text);
    }

    /**
     * LOCAL fallback only — called by RequirementAnalyzerFacade when the Python
     * service is unreachable. Returns a TF-IDF hash vector, NOT a real SBERT vector.
     *
     * The facade prefers the real SBERT vector returned by PythonNlpClient.embed().
     */
    public float[] embedFallback(String text) {
        log.warn("SentenceEmbedder.embedFallback() invoked — Python service unavailable. " +
                "Returning TF-IDF vector (not SBERT). Semantic matching quality will be degraded.");
        return embedWithTfIdf(text);
    }

    /**
     * Cosine similarity between two embedding vectors.
     * Used downstream in RequirementMatcher (FR-7) for semantic matching.
     *
     * Score: 1.0 = identical, 0.0 = orthogonal, -1.0 = opposite
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        RealVector va = new ArrayRealVector(toDouble(a));
        RealVector vb = new ArrayRealVector(toDouble(b));
        double dot  = va.dotProduct(vb);
        double norm = va.getNorm() * vb.getNorm();
        return norm == 0 ? 0.0 : dot / norm;
    }

    // ─── TF-IDF fallback ──────────────────────────────────────────────────────

    private static final Map<String, Double> IDF_WEIGHTS = new HashMap<>();
    static {
        IDF_WEIGHTS.put("authenticate", 3.2); IDF_WEIGHTS.put("authorize",    3.1);
        IDF_WEIGHTS.put("register",     2.9); IDF_WEIGHTS.put("login",        2.8);
        IDF_WEIGHTS.put("password",     2.7); IDF_WEIGHTS.put("token",        2.9);
        IDF_WEIGHTS.put("upload",       3.0); IDF_WEIGHTS.put("download",     3.0);
        IDF_WEIGHTS.put("payment",      3.3); IDF_WEIGHTS.put("invoice",      3.4);
        IDF_WEIGHTS.put("notification", 3.1); IDF_WEIGHTS.put("email",        2.8);
        IDF_WEIGHTS.put("search",       2.5); IDF_WEIGHTS.put("filter",       2.6);
        IDF_WEIGHTS.put("delete",       2.7); IDF_WEIGHTS.put("create",       2.4);
        IDF_WEIGHTS.put("update",       2.4); IDF_WEIGHTS.put("retrieve",     2.6);
        IDF_WEIGHTS.put("validate",     2.9); IDF_WEIGHTS.put("report",       2.8);
    }

    private float[] embedWithTfIdf(String text) {
        int dimensions = 384;
        float[] vector = new float[dimensions];
        String[] words = text.toLowerCase().split("\\s+");
        Map<String, Integer> tf = new HashMap<>();
        for (String word : words) tf.merge(word, 1, Integer::sum);
        for (Map.Entry<String, Integer> entry : tf.entrySet()) {
            String word = entry.getKey();
            double idf    = IDF_WEIGHTS.getOrDefault(word, 1.5);
            double tfidf  = (double) entry.getValue() / words.length * idf;
            int    bucket = Math.abs(word.hashCode()) % dimensions;
            vector[bucket] += (float) tfidf;
        }
        return l2Normalize(vector);
    }

    private float[] l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vector;
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) normalized[i] = (float)(vector[i] / norm);
        return normalized;
    }

    private double[] toDouble(float[] floats) {
        double[] doubles = new double[floats.length];
        for (int i = 0; i < floats.length; i++) doubles[i] = floats[i];
        return doubles;
    }
}