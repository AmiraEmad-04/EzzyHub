package com.ezzyhub.requirementanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EzzyHub Requirement Analyzer Module
 *
 * Entry point for the Requirement Analysis microservice.
 * This module handles:
 *   - Phase 1: Validation & ID Extraction
 *   - Phase 2: Text Preprocessing (Tokenization, Stopwords, Lemmatization)
 *   - Phase 3: Keyword Extraction (POS Tagging, Synonym Expansion)
 *   - Phase 4: Semantic Embedding (Sentence-BERT)
 */
@SpringBootApplication
public class RequirementAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RequirementAnalyzerApplication.class, args);
    }
}
