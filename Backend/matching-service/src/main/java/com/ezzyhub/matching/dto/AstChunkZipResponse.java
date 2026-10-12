package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Full response from Python POST /ast-chunk-zip.
 *
 * Maps to Python AstChunkZipResponse:
 *   total_files  → totalFiles
 *   total_chunks → totalChunks
 *   chunks       → chunks
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AstChunkZipResponse {

    @JsonProperty("total_files")
    private int totalFiles;

    @JsonProperty("total_chunks")
    private int totalChunks;

    private List<CodeChunkDto> chunks;
}
