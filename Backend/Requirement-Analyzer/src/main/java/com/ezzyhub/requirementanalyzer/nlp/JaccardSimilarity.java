package com.ezzyhub.requirementanalyzer.nlp;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Jaccard Similarity Calculator
 *
 * Used to measure keyword-set overlap between a requirement and code elements.
 * Formula: |A ∩ B| / |A ∪ B|
 *
 * Score: 1.0 = identical sets, 0.0 = completely different sets.
 *
 * Used alongside cosine similarity in the RequirementMatcher module
 * for combined requirement-to-code matching.
 */
@Component
public class JaccardSimilarity {

    /**
     * Computes Jaccard similarity between two keyword lists.
     */
    public double compute(List<String> keywordsA, List<String> keywordsB) {
        if (keywordsA == null || keywordsB == null ||
                (keywordsA.isEmpty() && keywordsB.isEmpty())) {
            return 0.0;
        }

        Set<String> setA = new HashSet<>(keywordsA);
        Set<String> setB = new HashSet<>(keywordsB);

        // Intersection
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        // Union
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / union.size();
    }

    /**
     * Computes Jaccard similarity between two strings (tokenized on whitespace).
     * Convenience overload for quick comparisons.
     */
    public double compute(String textA, String textB) {
        if (textA == null || textB == null) return 0.0;
        return compute(
                List.of(textA.toLowerCase().split("\\s+")),
                List.of(textB.toLowerCase().split("\\s+"))
        );
    }
}
