package com.ezzyhub.requirementanalyzer.nlp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PHASE 3 - STEP 11: Synonym Expansion
 *
 * FIX: WordNet synonym lookup is completely removed.
 * It was producing semantically incorrect synonyms that corrupted the
 * keyword set used in FR-7 matching:
 *   "log"  → ["logarithm", "lumber", "record", "cash register"]
 *   "user" → ["drug user", "substance abuser", "exploiter"]
 *   "store"→ ["computer storage", "hive away", "lay in", "salt away"]
 *
 * The Python microservice (nlp_pipeline.py) also has WordNet disabled for
 * the same reason — this class is kept consistent with it.
 *
 * Only the curated domain synonym map is used. It is identical to the
 * DOMAIN_SYNONYMS dict in the Python nlp_pipeline.py so both pipelines
 * produce the same keyword expansions.
 *
 * NOTE: This class is a fallback only. The main pipeline delegates to
 * the Python microservice via PythonNlpClient. This class may be invoked
 * if Python is unavailable (future fallback mode).
 */
@Slf4j
@Component
public class SynonymExpander {

    /**
     * Domain-specific synonym map — identical to DOMAIN_SYNONYMS in nlp_pipeline.py.
     * Keys are lemmatized base forms; values are known domain synonyms.
     */
    private static final Map<String, List<String>> DOMAIN_SYNONYMS = new HashMap<>();

    static {
        // Authentication / User management
        DOMAIN_SYNONYMS.put("register", List.of("signup", "enroll", "create", "join", "subscribe"));
        DOMAIN_SYNONYMS.put("login",    List.of("authenticate", "signin", "access", "logon"));
        DOMAIN_SYNONYMS.put("logout",   List.of("signout", "logoff", "exit", "disconnect"));
        DOMAIN_SYNONYMS.put("password", List.of("credential", "secret", "passphrase", "pin"));
        DOMAIN_SYNONYMS.put("user",     List.of("member", "account", "customer", "client", "profile"));

        // CRUD
        DOMAIN_SYNONYMS.put("create",   List.of("add", "insert", "generate", "post"));
        DOMAIN_SYNONYMS.put("read",     List.of("retrieve", "fetch", "get", "view", "display", "list"));
        DOMAIN_SYNONYMS.put("update",   List.of("edit", "modify", "change", "patch", "put"));
        DOMAIN_SYNONYMS.put("delete",   List.of("remove", "destroy", "purge", "erase"));
        DOMAIN_SYNONYMS.put("search",   List.of("find", "query", "lookup", "filter", "browse"));

        // Authorization
        DOMAIN_SYNONYMS.put("allow",    List.of("permit", "enable", "grant", "authorize"));
        DOMAIN_SYNONYMS.put("deny",     List.of("restrict", "block", "reject", "forbid", "disallow"));
        DOMAIN_SYNONYMS.put("role",     List.of("permission", "authority", "privilege", "access"));

        // Data
        DOMAIN_SYNONYMS.put("send",     List.of("submit", "post", "dispatch", "transmit", "push"));
        DOMAIN_SYNONYMS.put("receive",  List.of("get", "accept", "retrieve", "pull", "consume"));
        DOMAIN_SYNONYMS.put("store",    List.of("save", "persist", "record", "write"));
        DOMAIN_SYNONYMS.put("validate", List.of("verify", "check", "confirm", "ensure"));

        // Notifications / Reporting
        DOMAIN_SYNONYMS.put("notify",   List.of("alert", "inform", "email", "message", "remind"));
        DOMAIN_SYNONYMS.put("report",   List.of("summary", "dashboard", "overview", "statistics"));

        // GitHub / Files
        DOMAIN_SYNONYMS.put("import",   List.of("upload", "download", "load", "fetch", "pull"));
        DOMAIN_SYNONYMS.put("extract",  List.of("parse", "read", "unzip", "decompress"));
        DOMAIN_SYNONYMS.put("analyze",  List.of("process", "inspect", "evaluate", "assess"));
        DOMAIN_SYNONYMS.put("generate", List.of("create", "produce", "build", "output"));
    }

    /**
     * Expands a keyword list using only the curated domain synonym map.
     * WordNet is intentionally NOT used (see class-level Javadoc).
     *
     * @param keywords lemmatized keywords from Phase 3 keyword extraction
     * @return deduplicated combined keyword list (insertion order preserved)
     */
    public List<String> expand(List<String> keywords) {
        Set<String> seen = new LinkedHashSet<>(keywords);
        List<String> result = new ArrayList<>(keywords);

        for (String keyword : keywords) {
            List<String> synonyms = DOMAIN_SYNONYMS.getOrDefault(keyword, Collections.emptyList());
            for (String syn : synonyms) {
                if (seen.add(syn)) {          // add returns false if already present
                    result.add(syn);
                }
            }
        }

        log.debug("Step 11 Synonym expansion (domain map only): {} → {} terms",
                keywords.size(), result.size());
        return result;
    }
}