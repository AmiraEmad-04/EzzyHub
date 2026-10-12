package com.ezzyhub.requirementanalyzer.nlp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PHASE 3 - STEP 10: Keyword Extraction
 *
 * Filters tagged tokens to keep only meaningful words:
 *   - Nouns      (NN, NNS, NNP)
 *   - Verbs      (VB, VBD, VBG, VBN, VBP, VBZ)
 *   - Adjectives (JJ, JJR, JJS)
 *
 * Also separately captures action verbs for the output's "actionVerbs" field.
 */
@Slf4j
@Component
public class KeywordExtractor {

    private static final Set<String> KEEP_TAGS = Set.of(
            "NN", "NNS", "NNP", "NNPS",      // Nouns
            "VB", "VBD", "VBG", "VBN", "VBP", "VBZ",  // Verbs
            "JJ", "JJR", "JJS"                // Adjectives
    );

    /**
     * Extract all keywords (nouns + verbs + adjectives) from POS-tagged list.
     */
    public List<String> extractKeywords(List<PosTagger.TaggedWord> tagged) {
        List<String> keywords = tagged.stream()
                .filter(tw -> KEEP_TAGS.contains(tw.tag()))
                .map(PosTagger.TaggedWord::word)
                .distinct()
                .collect(Collectors.toList());

        log.debug("Step 10 Keywords extracted: {}", keywords);
        return keywords;
    }
}
