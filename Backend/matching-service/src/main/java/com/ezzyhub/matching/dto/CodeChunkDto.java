package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * One method/class chunk returned by Python /ast-chunk-zip.
 *
 * Maps exactly to the Python AstChunkResponse pydantic model:
 *   file_path   → filePath
 *   class_name  → className
 *   method_name → methodName
 *   chunk_type  → chunkType   ("class" | "method" | "constructor" | "file")
 *   chunk_text  → chunkText   (compact text used for embedding)
 *   line_start  → lineStart
 *   keywords    → keywords    (NLP-extracted keywords from Python pipeline)
 *   embedding   → embedding   (384-dim SBERT code embedding)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeChunkDto {

    @JsonProperty("file_path")
    private String filePath;

    @JsonProperty("class_name")
    private String className;

    @JsonProperty("method_name")
    private String methodName;   // null for class-level chunks

    @JsonProperty("chunk_type")
    private String chunkType;    // "class" | "method" | "constructor" | "file"

    @JsonProperty("chunk_text")
    private String chunkText;

    @JsonProperty("line_start")
    private int lineStart;

    /** NLP keywords extracted by Python NLP pipeline from this chunk. */
    private List<String> keywords;

    /** 384-dim SBERT code embedding — compatible with requirement embeddings. */
    private List<Double> embedding;
}
