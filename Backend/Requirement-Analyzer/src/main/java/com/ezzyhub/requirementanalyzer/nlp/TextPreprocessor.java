package com.ezzyhub.requirementanalyzer.nlp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PHASE 2: TEXT PREPROCESSING
 *
 * Steps executed in order:
 *   Step 4:  Tokenization       - split text into words (NLTK-equivalent)
 *   Step 5:  Lowercasing        - normalize case
 *   Step 6:  Stop Word Removal  - remove function words + domain stopwords
 *   Step 7:  Remove Punctuation & Numbers
 *   Step 8:  Lemmatization      - reduce to base form (rule-based)
 *
 * FIXES applied:
 *  - tokenize() now splits on ALL punctuation boundaries (not just whitespace/commas/semicolons)
 *    so periods, colons, parentheses, and quotes are handled like NLTK word_tokenize.
 *  - "allow", "deny", "enable" removed from DOMAIN_STOPWORDS — they are key action verbs.
 *  - Phrasal verb normalisation ("log in" → "login") applied before tokenisation,
 *    matching the Python nlp_pipeline.py behaviour.
 *  - Rule-based lemmatizer: "-ed" rule no longer strips blindly — guards against
 *    over-stemming words like "password" → "passwor" or "method" → "meth".
 *    Minimum stem length increased to 4.
 *  - Irregular map extended with more common software-domain forms.
 */
@Slf4j
@Component
public class TextPreprocessor {

    // --- NLTK-equivalent English stopwords ---
    private static final Set<String> ENGLISH_STOPWORDS = new HashSet<>(Arrays.asList(
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your",
            "yours", "yourself", "yourselves", "he", "him", "his", "himself", "she",
            "her", "hers", "herself", "it", "its", "itself", "they", "them", "their",
            "theirs", "themselves", "what", "which", "who", "whom", "this", "that",
            "these", "those", "am", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "doing", "a", "an",
            "the", "and", "but", "if", "or", "because", "as", "until", "while", "of",
            "at", "by", "for", "with", "about", "against", "between", "into", "through",
            "during", "before", "after", "above", "below", "to", "from", "up", "down",
            "in", "out", "on", "off", "over", "under", "again", "further", "then",
            "once", "here", "there", "when", "where", "why", "how", "all", "both",
            "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not",
            "only", "own", "same", "so", "than", "too", "very", "s", "t", "can",
            "just", "don", "should", "now", "d", "ll", "m", "o", "re", "ve", "y",
            "ain", "aren", "couldn", "didn", "doesn", "hadn", "hasn", "haven",
            "isn", "ma", "mightn", "mustn", "needn", "shan", "shouldn", "wasn",
            "weren", "won", "wouldn"
    ));

    // --- Domain-specific stopwords for requirements engineering ---
    // FIX: "allow", "deny", "enable" removed — they ARE action verbs per spec
    //      and must not be silently dropped before POS tagging.
    private static final Set<String> DOMAIN_STOPWORDS = new HashSet<>(Arrays.asList(
            "shall", "must", "will", "should", "may", "need", "needs", "able",
            "system", "application", "app", "software", "platform", "module",
            "functionality", "feature", "behavior", "provide", "support",
            "give", "make", "use", "used", "using"
    ));

    // FIX: Phrasal verbs normalised before tokenisation (matches Python nlp_pipeline.py)
    private static final Map<String, String> PHRASAL_VERBS = new LinkedHashMap<>();
    static {
        PHRASAL_VERBS.put("log in",  "login");
        PHRASAL_VERBS.put("log out", "logout");
        PHRASAL_VERBS.put("sign in", "signin");
        PHRASAL_VERBS.put("sign up", "signup");
        PHRASAL_VERBS.put("sign out","signout");
        PHRASAL_VERBS.put("set up",  "setup");
        PHRASAL_VERBS.put("opt in",  "optin");
        PHRASAL_VERBS.put("opt out", "optout");
    }

    // FIX: Irregular map — extended with more software-domain forms so the
    //      naive "-ed" stripping rule is never reached for these words.
    private static final Map<String, String> IRREGULARS = new HashMap<>();
    static {
        IRREGULARS.put("users",      "user");
        IRREGULARS.put("files",      "file");
        IRREGULARS.put("queries",    "query");
        IRREGULARS.put("entries",    "entry");
        IRREGULARS.put("libraries",  "library");
        IRREGULARS.put("data",       "data");
        IRREGULARS.put("criteria",   "criterion");
        // FIX: guard common words that the -ed rule would mangle
        IRREGULARS.put("password",   "password");
        IRREGULARS.put("method",     "method");
        IRREGULARS.put("failed",     "fail");
        IRREGULARS.put("denied",     "deny");
        IRREGULARS.put("verified",   "verify");
        IRREGULARS.put("stored",     "store");
        IRREGULARS.put("enabled",    "enable");
        IRREGULARS.put("disabled",   "disable");
        IRREGULARS.put("displayed",  "display");
        IRREGULARS.put("validated",  "validate");
        IRREGULARS.put("registered", "register");
        IRREGULARS.put("logged",     "log");
        IRREGULARS.put("allowed",    "allow");
        IRREGULARS.put("denied",     "deny");
    }

    // FIX: Punctuation split pattern now matches ALL non-alphanumeric boundaries
    //      (like NLTK word_tokenize) instead of only whitespace/commas/semicolons.
    //      Hyphenated terms (e.g. "re-validate") are preserved as a unit.
    private static final Pattern TOKENIZE_SPLIT = Pattern.compile("[\\s.,;:!?()\\[\\]{}\"'`]+");
    private static final Pattern NON_ALPHA      = Pattern.compile("[^a-z]");

    // ─── Public API ──────────────────────────────────────────────────────────

    public List<String> preprocess(String text) {
        // FIX: normalise phrasal verbs before any other step
        String normalized = normalizePhrасalVerbs(text.toLowerCase());

        List<String> tokens = tokenize(normalized);
        log.debug("Step 4 Tokenized: {}", tokens);

        tokens = lowercase(tokens);
        log.debug("Step 5 Lowercased: {}", tokens);

        tokens = removeStopWords(tokens);
        log.debug("Step 6 Stopwords removed: {}", tokens);

        tokens = removePunctuationAndNumbers(tokens);
        log.debug("Step 7 Cleaned: {}", tokens);

        tokens = lemmatize(tokens);
        log.debug("Step 8 Lemmatized: {}", tokens);

        return tokens;
    }

    // ─── Step implementations ─────────────────────────────────────────────────

    /**
     * Step 4: Tokenisation — split on ALL punctuation/whitespace boundaries.
     * FIX: old split("[\\s,;]+") missed periods, colons, parentheses; the new
     * pattern matches NLTK word_tokenize's boundary set for ASCII text.
     */
    public List<String> tokenize(String text) {
        return Arrays.stream(TOKENIZE_SPLIT.split(text))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    /** Step 5: Lowercase */
    public List<String> lowercase(List<String> tokens) {
        return tokens.stream().map(String::toLowerCase).collect(Collectors.toList());
    }

    /** Step 6: Stop Word Removal (English + domain-specific) */
    public List<String> removeStopWords(List<String> tokens) {
        return tokens.stream()
                .filter(t -> !ENGLISH_STOPWORDS.contains(t))
                .filter(t -> !DOMAIN_STOPWORDS.contains(t))
                .collect(Collectors.toList());
    }

    /** Step 7: Remove Punctuation & Numbers — keep only alphabetic tokens ≥ 2 chars */
    public List<String> removePunctuationAndNumbers(List<String> tokens) {
        return tokens.stream()
                .map(t -> NON_ALPHA.matcher(t).replaceAll(""))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toList());
    }

    /** Step 8: Rule-Based Lemmatization */
    public List<String> lemmatize(List<String> tokens) {
        return tokens.stream().map(this::lemmatizeWord).collect(Collectors.toList());
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String normalizePhrасalVerbs(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : PHRASAL_VERBS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String lemmatizeWord(String word) {
        // Irregular forms take absolute priority — checked before any suffix rule
        if (IRREGULARS.containsKey(word)) return IRREGULARS.get(word);

        // Rule: -ies → -y  (categories → category)
        if (word.endsWith("ies") && word.length() > 4)
            return word.substring(0, word.length() - 3) + "y";

        // Rule: -ing → base  (logging → log, running → run)
        if (word.endsWith("ing") && word.length() > 5) {
            String stem = word.substring(0, word.length() - 3);
            // Handle consonant doubling: running → run
            if (stem.length() >= 2 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2))
                return stem.substring(0, stem.length() - 1);
            return stem.length() > 3 ? stem : word;
        }

        // FIX: -ed → base — minimum stem length raised to 4 to prevent over-stemming.
        //      "password" (8 chars) → stem="passwor" (7) is now blocked because
        //      it would strip a real word.  Most valid -ed verbs have stems ≥ 4 chars.
        if (word.endsWith("ed") && word.length() > 5) {
            String stem = word.substring(0, word.length() - 2);
            // Handle consonant doubling: logged → log
            if (stem.length() >= 2 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2))
                return stem.substring(0, stem.length() - 1);
            // Only apply if the resulting stem is at least 4 characters
            if (stem.length() >= 4) return stem;
        }

        // Rule: -s plural  (registers → register) — skip -ss endings
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3)
            return word.substring(0, word.length() - 1);

        // Rule: -er comparative  (larger → large, faster → fast)
        if (word.endsWith("er") && word.length() > 4)
            return word.substring(0, word.length() - 2);

        // Rule: -est superlative
        if (word.endsWith("est") && word.length() > 5)
            return word.substring(0, word.length() - 3);

        return word;
    }
}
