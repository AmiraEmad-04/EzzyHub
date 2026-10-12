package com.ezzyhub.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EzzyHub Matching Service — Entry Point
 *
 * FR-7: Requirement-to-Code Matching
 * Port: 8083
 *
 * Implements the professor's recommendation:
 *   1. AST-based chunking  (method / class level, via Python ast_chunker.py)
 *   2. Many-to-Many cosine similarity matrix (requirement embeddings × code chunk embeddings)
 *   3. Jaccard keyword similarity as a secondary signal
 *   4. Combined weighted score → IMPLEMENTED / PARTIAL / MISSING classification
 *
 * Pipeline:
 *   POST /api/v1/match
 *     │
 *     ├── Python :8000 /ast-chunk-zip      → code chunks + 384-dim embeddings + keywords
 *     ├── Python :8000 /preprocess-and-embed → requirement embeddings + keywords
 *     │
 *     └── MatchingEngine
 *           cosine(req_emb, chunk_emb) × 0.65
 *         + jaccard(req_kws, chunk_kws) × 0.35
 *         = combined score
 *         → classify → coverage report
 */
@SpringBootApplication
public class MatchingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchingServiceApplication.class, args);
    }
}
