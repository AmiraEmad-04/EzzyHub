package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Details of one code chunk that matched a requirement.
 * Included in the top-K list inside RequirementMatchResult.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChunkMatchResult {

    /** Relative file path inside the ZIP (e.g. "com/example/UserController.java") */
    private String filePath;

    /** Java class name (e.g. "UserController") */
    private String className;

    /** Method name — null for class-level chunks */
    private String methodName;

    /**
     * Chunk type: "class" | "method" | "constructor" | "file"
     * Indicates what AST node this chunk represents.
     */
    private String chunkType;

    /**
     * The compact text that was embedded for this chunk.
     * Format: "@Annotation ClassName.methodName(ParamType param)"
     */
    private String chunkText;

    /** Source line where this chunk starts (0 if unknown) */
    private int lineStart;

    // ── Similarity scores ────────────────────────────────────────────────────

    /**
     * Combined weighted score: cosineWeight × cosine + jaccardWeight × jaccard.
     * This is the primary ranking signal.
     */
    private double score;

    /**
     * Cosine similarity between requirement embedding and chunk embedding.
     * Range: [0.0, 1.0]  (we clamp negative values to 0 for classification)
     */
    private double cosineScore;

    /**
     * Jaccard similarity between requirement keywords and chunk keywords.
     * Range: [0.0, 1.0]
     */
    private double jaccardScore;

    /** Keywords extracted from this chunk by the Python NLP pipeline */
    private List<String> chunkKeywords;
}
