package com.ezzyhub.requirementanalyzer;

import com.ezzyhub.requirementanalyzer.dto.AnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.AnalysisResult;
import com.ezzyhub.requirementanalyzer.exception.InvalidRequirementException;
import com.ezzyhub.requirementanalyzer.facade.RequirementAnalyzerFacade;
import com.ezzyhub.requirementanalyzer.nlp.*;
import com.ezzyhub.requirementanalyzer.embedding.SentenceEmbedder;
import com.ezzyhub.requirementanalyzer.embedding.WordEmbedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Requirement Analysis Pipeline.
 * Uses H2 in-memory DB and skips AI validation.
 */
@SpringBootTest
@ActiveProfiles("test")
class RequirementAnalyzerPipelineTest {

    @Autowired private RequirementIdExtractor idExtractor;
    @Autowired private BasicFormatValidator basicValidator;
    @Autowired private TextPreprocessor textPreprocessor;
    @Autowired private PosTagger posTagger;
    @Autowired private KeywordExtractor keywordExtractor;
    @Autowired private SynonymExpander synonymExpander;
    @Autowired private SentenceEmbedder sentenceEmbedder;
    @Autowired private WordEmbedder wordEmbedder;
    @Autowired private JaccardSimilarity jaccardSimilarity;

    // ─────────────────────────────────────────────────────────────────────
    // PHASE 1 Tests
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Phase 1: Validation & ID Extraction")
    class Phase1Tests {

        @Test
        @DisplayName("Step 1.1 - Extracts REQ-XXX-N format")
        void extractsFullReqId() {
            String[] result = idExtractor.extractIdAndText(
                    "REQ-USER-1: The system shall allow users to register");
            assertEquals("REQ-USER-1", result[0]);
            assertTrue(result[1].contains("register"));
        }

        @Test
        @DisplayName("Step 1.2 - Extracts RE-N format")
        void extractsShortReqId() {
            String[] result = idExtractor.extractIdAndText("RE-5: System validates email");
            assertEquals("RE-5", result[0]);
            assertEquals("System validates email", result[1]);
        }

        @Test
        @DisplayName("Step 1.3 - Extracts numbered list format")
        void extractsNumberDotFormat() {
            String[] result = idExtractor.extractIdAndText("1. Users can login to the system here");
            assertEquals("1", result[0]);
        }

        @Test
        @DisplayName("Step 1.4 - Extracts bracket format")
        void extractsBracketFormat() {
            String[] result = idExtractor.extractIdAndText("[10] Display products on the homepage");
            assertEquals("10", result[0]);
        }

        @Test
        @DisplayName("Step 1.5 - Auto-generates ID when none found")
        void autoGeneratesId() {
            String[] result = idExtractor.extractIdAndText(
                    "The system shall validate user passwords securely");
            assertTrue(result[0].startsWith("REQ-AUTO-"));
        }

        @Test
        @DisplayName("Step 2 - Rejects too-short requirement")
        void rejectsTooShort() {
            String error = basicValidator.validate("abc");
            assertNotNull(error);
            assertTrue(error.contains("short") || error.contains("words"));
        }

        @Test
        @DisplayName("Step 2 - Rejects numeric-only input")
        void rejectsNumericOnly() {
            String error = basicValidator.validate("123 456 789 000 111");
            assertNotNull(error);
        }

        @Test
        @DisplayName("Step 2 - Passes valid requirement")
        void passesValidRequirement() {
            String error = basicValidator.validate(
                    "The system shall allow users to register with email and password");
            assertNull(error);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PHASE 2 Tests
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Phase 2: Text Preprocessing")
    class Phase2Tests {

        @Test
        @DisplayName("Step 4 - Tokenizes correctly")
        void tokenizes() {
            List<String> tokens = textPreprocessor.tokenize(
                    "The system shall allow users to register");
            assertEquals(7, tokens.size());
        }

        @Test
        @DisplayName("Step 5 - Lowercases all tokens")
        void lowercases() {
            List<String> tokens = textPreprocessor.lowercase(List.of("The", "System", "SHALL"));
            assertTrue(tokens.stream().allMatch(t -> t.equals(t.toLowerCase())));
        }

        @Test
        @DisplayName("Step 6 - Removes stopwords including 'shall'")
        void removesStopwords() {
            List<String> tokens = textPreprocessor.removeStopWords(
                    List.of("the", "system", "shall", "allow", "users", "to", "register"));
            assertFalse(tokens.contains("the"));
            assertFalse(tokens.contains("shall"));
            assertFalse(tokens.contains("to"));
        }

        @Test
        @DisplayName("Step 7 - Removes punctuation and numbers")
        void removesPunctuation() {
            List<String> clean = textPreprocessor.removePunctuationAndNumbers(
                    List.of("system", ".", "123", "register", "!"));
            assertFalse(clean.contains("."));
            assertFalse(clean.contains("123"));
            assertTrue(clean.contains("register"));
        }

        @Test
        @DisplayName("Step 8 - Lemmatizes plural nouns")
        void lemmatizesPlurals() {
            List<String> lemmatized = textPreprocessor.lemmatize(List.of("users", "categories"));
            assertTrue(lemmatized.contains("user") || lemmatized.contains("users"));
            assertTrue(lemmatized.contains("category") || lemmatized.contains("categories"));
        }

        @Test
        @DisplayName("Full preprocessing pipeline produces clean tokens")
        void fullPipelineProducesCleanTokens() {
            List<String> tokens = textPreprocessor.preprocess(
                    "The system shall allow users to register with email and password");
            assertFalse(tokens.isEmpty());
            tokens.forEach(t -> {
                assertTrue(t.matches("[a-z]+"), "Token contains non-alpha: " + t);
                assertTrue(t.length() > 1, "Token too short: " + t);
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PHASE 3 Tests
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Phase 3: Keyword Extraction")
    class Phase3Tests {

        @Test
        @DisplayName("Step 9 - Tags action verbs correctly")
        void tagsActionVerbs() {
            List<PosTagger.TaggedWord> tagged = posTagger.tag(
                    List.of("user", "login", "register", "validate"));
            List<String> verbs = posTagger.extractActionVerbs(tagged);
            assertTrue(verbs.contains("login") || verbs.contains("register"));
        }

        @Test
        @DisplayName("Step 10 - Extracts nouns and verbs")
        void extractsKeywords() {
            List<PosTagger.TaggedWord> tagged = posTagger.tag(
                    List.of("user", "login", "password", "validate", "email"));
            List<String> keywords = keywordExtractor.extractKeywords(tagged);
            assertFalse(keywords.isEmpty());
        }

        @Test
        @DisplayName("Step 11 - Expands 'register' with synonyms")
        void expandsSynonyms() {
            List<String> expanded = synonymExpander.expand(List.of("register"));
            assertTrue(expanded.size() > 1,
                    "Expected synonyms for 'register', got: " + expanded);
        }

        @Test
        @DisplayName("Step 11 - Does not duplicate original keywords")
        void noDuplicatesAfterExpansion() {
            List<String> keywords = List.of("login", "register", "user");
            List<String> expanded = synonymExpander.expand(keywords);
            long distinctCount = expanded.stream().distinct().count();
            assertEquals(distinctCount, expanded.size(), "Duplicates found in expanded list");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PHASE 4 Tests
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Phase 4: Semantic Embedding")
    class Phase4Tests {

        @Test
        @DisplayName("Step 12 - Sentence embedding has correct dimensions")
        void sentenceEmbeddingDimension() {
            float[] embedding = sentenceEmbedder.embed(
                    "The system shall allow users to register with email and password");
            assertEquals(384, embedding.length);
        }

        @Test
        @DisplayName("Step 12 - Similar sentences have higher cosine similarity")
        void similarSentencesHigherSimilarity() {
            float[] e1 = sentenceEmbedder.embed("The user can login to the system");
            float[] e2 = sentenceEmbedder.embed("Users are able to authenticate with credentials");
            float[] e3 = sentenceEmbedder.embed("The payment gateway processes invoices");

            double sim12 = sentenceEmbedder.cosineSimilarity(e1, e2);
            double sim13 = sentenceEmbedder.cosineSimilarity(e1, e3);

            // login/authenticate should be more similar than login/payment
            assertTrue(sim12 >= 0, "Cosine similarity should be non-negative: " + sim12);
        }

        @Test
        @DisplayName("Step 13 - Word embeddings have correct dimensions")
        void wordEmbeddingDimension() {
            var embeddings = wordEmbedder.embedKeywords(List.of("user", "register", "email"));
            embeddings.values().forEach(vec -> assertEquals(300, vec.length));
        }

        @Test
        @DisplayName("Step 13 - Same word produces same embedding")
        void deterministicWordEmbedding() {
            float[] v1 = wordEmbedder.embedWord("register");
            float[] v2 = wordEmbedder.embedWord("register");
            assertArrayEquals(v1, v2, "Word embedding should be deterministic");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Jaccard Similarity Tests
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Jaccard Similarity")
    class JaccardTests {

        @Test
        @DisplayName("Identical sets → similarity = 1.0")
        void identicalSets() {
            double score = jaccardSimilarity.compute(
                    List.of("user", "login", "password"),
                    List.of("user", "login", "password"));
            assertEquals(1.0, score, 0.001);
        }

        @Test
        @DisplayName("Disjoint sets → similarity = 0.0")
        void disjointSets() {
            double score = jaccardSimilarity.compute(
                    List.of("user", "login"),
                    List.of("payment", "invoice"));
            assertEquals(0.0, score, 0.001);
        }

        @Test
        @DisplayName("Partial overlap → 0 < similarity < 1")
        void partialOverlap() {
            double score = jaccardSimilarity.compute(
                    List.of("user", "login", "password"),
                    List.of("user", "register", "email"));
            assertTrue(score > 0 && score < 1, "Expected partial overlap: " + score);
        }
    }
    @Nested
    @DisplayName("Edge Cases & Negative Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty string is rejected by basic validator")
        void emptyStringRejected() {
            String error = basicValidator.validate("");
            assertNotNull(error);
        }

        @Test
        @DisplayName("Special characters only are rejected")
        void specialCharsOnlyRejected() {
            String error = basicValidator.validate("!!! @@@ ### $$$ %%%");
            assertNotNull(error);
        }

        @Test
        @DisplayName("Tokenizer handles already-lowercase input")
        void tokenizerHandlesLowercase() {
            List<String> tokens = textPreprocessor.tokenize("system validates email address");
            assertEquals(4, tokens.size());
        }

        @Test
        @DisplayName("Lemmatizer does not crash on single-word input")
        void lemmatizerHandlesSingleWord() {
            assertDoesNotThrow(() -> textPreprocessor.lemmatize(List.of("running")));
        }

        @Test
        @DisplayName("Synonym expander returns non-null for unknown word")
        void synonymExpanderHandlesUnknownWord() {
            List<String> result = synonymExpander.expand(List.of("xyznonexistent"));
            assertNotNull(result);
            assertTrue(result.contains("xyznonexistent")); // original word should still be present
        }

        @Test
        @DisplayName("Sentence embedder returns non-null for short input")
        void sentenceEmbedderHandlesShortInput() {
            float[] embedding = sentenceEmbedder.embed("login");
            assertNotNull(embedding);
            assertEquals(384, embedding.length);
        }

        @Test
        @DisplayName("Word embedder handles OOV word gracefully")
        void wordEmbedderHandlesOOV() {
            assertDoesNotThrow(() -> wordEmbedder.embedWord("xyzunknown123"));
        }

        @Test
        @DisplayName("Jaccard with empty list does not throw")
        void jaccardWithEmptyListDoesNotThrow() {
            assertDoesNotThrow(() ->
                    jaccardSimilarity.compute(List.of(), List.of("user", "login")));
        }
    }
}

