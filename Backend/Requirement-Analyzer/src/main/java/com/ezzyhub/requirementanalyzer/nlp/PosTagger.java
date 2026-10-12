package com.ezzyhub.requirementanalyzer.nlp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * PHASE 3 - STEP 9: Part-of-Speech (POS) Tagging + Action Verb Detection
 *
 * Tags each preprocessed token with a POS category:
 *   NN  - Noun (singular)
 *   NNS - Noun (plural)
 *   VB  - Verb (base form)
 *   VBG - Verb (gerund/present participle)
 *   VBD - Verb (past tense)
 *   JJ  - Adjective
 *
 * Uses a rule-based approach + curated lexicons for software domain accuracy.
 * For production, swap with OpenNLP MaxEnt POS tagger (model: en-pos-maxent.bin).
 */
@Slf4j
@Component
public class PosTagger {

    // --- Software domain verb lexicon ---
    // FIX: "enable", "disable", "read", "write" added here (removed from KNOWN_ADJECTIVES)
    //      so they are always tagged VB regardless of context, which matches
    //      how they appear in functional requirements text.
    private static final Set<String> KNOWN_VERBS = new HashSet<>(Arrays.asList(
            // Auth
            "login", "logout", "authenticate", "authorize", "register", "signup",
            "verify", "validate", "confirm", "reset", "change", "update",
            // CRUD
            "create", "read", "retrieve", "fetch", "get", "list", "search", "find",
            "delete", "remove", "add", "insert", "save", "store", "persist", "load",
            "edit", "modify", "patch", "submit", "cancel", "restore", "write",
            // System actions
            "send", "receive", "upload", "download", "import", "export", "process",
            "generate", "calculate", "compute", "analyze", "display", "show",
            "render", "format", "convert", "transform", "notify", "alert", "log",
            "monitor", "track", "report", "filter", "sort", "paginate",
            // Access / control
            "allow", "deny", "restrict", "limit", "enable", "disable", "configure",
            "manage", "handle", "support", "provide", "ensure", "expose", "map",
            "connect", "integrate", "deploy", "start", "stop", "run", "execute"
    ));

    // --- Software domain noun lexicon ---
    private static final Set<String> KNOWN_NOUNS = new HashSet<>(Arrays.asList(
            "user", "admin", "role", "permission", "token", "session", "credential",
            "password", "email", "username", "profile", "account", "address",
            "product", "item", "order", "cart", "payment", "invoice", "report",
            "category", "tag", "comment", "review", "rating", "notification",
            "message", "response", "request", "endpoint", "api", "service",
            "database", "repository", "entity", "record", "field", "attribute",
            "form", "input", "output", "file", "image", "document", "page",
            "dashboard", "view", "controller", "module", "component", "interface",
            "system", "application", "platform", "server", "client", "browser",
            "error", "exception", "log", "event", "query", "result", "data",
            "list", "detail", "summary", "search", "filter", "pagination", "limit",
            "date", "time", "status", "state", "type", "format", "configuration"
    ));

    // --- Software domain adjective lexicon ---
    // FIX: "enabled", "disabled", "read", "write" removed from KNOWN_ADJECTIVES.
    //      In software requirements they almost always appear as VERBS
    //      ("the system shall enable/disable X", "users can read/write Y").
    //      They are already present in KNOWN_VERBS so they will be tagged VB.
    //      "required" is also a verb in requirements context ("required fields").
    private static final Set<String> KNOWN_ADJECTIVES = new HashSet<>(Arrays.asList(
            "valid", "invalid", "active", "inactive",
            "mandatory", "secure", "encrypted",
            "authenticated", "authorized", "unique", "duplicate", "existing",
            "new", "old", "current", "previous", "latest", "expired",
            "successful", "failed", "pending", "approved", "rejected",
            "public", "private", "protected", "full", "partial",
            "real", "virtual", "online", "offline", "available", "unavailable",
            "correct", "incorrect", "complete", "incomplete", "empty", "blank",
            "optional", "required"
    ));

    public record TaggedWord(String word, String tag) {}

    /**
     * Tags each token with a POS label.
     */
    public List<TaggedWord> tag(List<String> tokens) {
        List<TaggedWord> result = new ArrayList<>();
        for (String token : tokens) {
            result.add(new TaggedWord(token, classifyToken(token)));
        }
        log.debug("POS tagged: {}", result);
        return result;
    }

    private String classifyToken(String word) {
        // Check curated lexicons first
        if (KNOWN_VERBS.contains(word)) return "VB";
        if (KNOWN_NOUNS.contains(word)) return "NN";
        if (KNOWN_ADJECTIVES.contains(word)) return "JJ";

        // Rule-based morphological heuristics
        if (word.endsWith("ing")) return "VBG";
        if (word.endsWith("ed"))  return "VBD";
        if (word.endsWith("er") || word.endsWith("est")) return "JJ";
        if (word.endsWith("tion") || word.endsWith("sion") ||
                word.endsWith("ment") || word.endsWith("ness") ||
                word.endsWith("ity") || word.endsWith("ism") ||
                word.endsWith("ist")) return "NN";
        if (word.endsWith("al") || word.endsWith("ous") ||
                word.endsWith("ful") || word.endsWith("less") ||
                word.endsWith("ive") || word.endsWith("able") ||
                word.endsWith("ible")) return "JJ";
        if (word.endsWith("ize") || word.endsWith("ise") ||
                word.endsWith("ate") || word.endsWith("ify")) return "VB";

        // Default: noun (most tokens in requirements are nouns)
        return "NN";
    }

    /**
     * Extracts only action verbs (VB, VBD, VBG tags) from tagged list.
     */
    public List<String> extractActionVerbs(List<TaggedWord> tagged) {
        return tagged.stream()
                .filter(tw -> tw.tag().startsWith("VB"))
                .map(TaggedWord::word)
                .distinct()
                .toList();
    }
}
