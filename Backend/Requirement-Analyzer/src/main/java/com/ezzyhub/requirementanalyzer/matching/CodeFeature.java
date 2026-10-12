package com.ezzyhub.requirementanalyzer.matching;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A single semantic unit extracted from the code analysis result.
 * Each CodeFeature represents either:
 *   - A whole file (filePath set, methodName null)
 *   - A specific method (filePath + methodName both set)
 *
 * keywords and embedding are populated by the Python NLP service.
 */
@Data
@Builder
public class CodeFeature {
    private String       filePath;
    private String       methodName;      // null for file-level features
    private String       semanticText;    // human-readable description from LLM
    private List<String> keywords;        // NLP-extracted keywords
    private List<Float>  embedding;       // SBERT sentence embedding (384-dim or 768-dim)
}
