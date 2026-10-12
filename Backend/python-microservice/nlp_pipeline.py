"""
EzzyHub NLP Pipeline — Phases 2 & 3
====================================
Phase 2: Tokenization → Lowercase → Stop-word removal → Punctuation removal → Lemmatization
Phase 3: POS tagging → Keyword extraction → Synonym expansion

IMPROVEMENTS over original:
  1. Uses spaCy as the primary NLP engine (better lemmatization, POS, NER).
     Falls back automatically to NLTK if spaCy is not installed — zero downtime.
  2. Action verb detection runs on the FULL sentence (pre-stop-word-removal) for
     proper POS context. Prevents adjectives like "incorrect" being tagged as VB.
  3. Domain synonym map is expanded with more software engineering terms.
  4. Phrasal verb normalization uses case-insensitive regex (not lowercasing the
     whole string) so proper-noun capitalization is preserved for POS tagging.
  5. WordNet synonym expansion stays DISABLED — it introduces semantic noise
     ("logarithm" for "log", "drug user" for "user"). Domain map only.
  6. add_project_terms() API: callers can inject project-specific terms
     (class names, method names) as additional keyword hints. Used by the
     CodeFeatureExtractorService to boost matching accuracy.
"""

import re
import logging
from typing import List, Dict, Set, Optional

import nltk
from nltk.tokenize import word_tokenize
from nltk.corpus import stopwords, wordnet
from nltk.stem import WordNetLemmatizer
from nltk import pos_tag

logger = logging.getLogger(__name__)

# ── NLTK data (downloaded once at module load) ────────────────────────────────
for resource in [
    "punkt", "punkt_tab",
    "averaged_perceptron_tagger", "averaged_perceptron_tagger_eng",
    "wordnet", "stopwords",
]:
    try:
        nltk.download(resource, quiet=True)
    except Exception:
        pass

# ── spaCy optional import ─────────────────────────────────────────────────────
try:
    import spacy
    _nlp_spacy = spacy.load("en_core_web_sm")
    SPACY_AVAILABLE = True
    logger.info("✓ spaCy en_core_web_sm loaded (primary NLP engine)")
except Exception:
    _nlp_spacy = None
    SPACY_AVAILABLE = False
    logger.info("spaCy not available — using NLTK fallback")

# ── Domain synonym map ────────────────────────────────────────────────────────
DOMAIN_SYNONYMS: Dict[str, List[str]] = {
    # Auth / Identity
    "register":    ["signup", "enroll", "create", "join", "subscribe"],
    "login":       ["authenticate", "signin", "access", "logon"],
    "logout":      ["signout", "logoff", "exit", "disconnect"],
    "password":    ["credential", "secret", "passphrase", "pin"],
    "user":        ["member", "account", "customer", "client", "profile"],
    "authenticate": ["verify", "login", "signin", "authorize"],
    # CRUD
    "create":      ["add", "insert", "generate", "post", "new"],
    "read":        ["retrieve", "fetch", "get", "view", "display", "list", "show"],
    "update":      ["edit", "modify", "change", "patch", "put", "alter"],
    "delete":      ["remove", "destroy", "purge", "erase", "drop"],
    "search":      ["find", "query", "lookup", "filter", "browse", "seek"],
    # Authorization
    "allow":       ["permit", "enable", "grant", "authorize", "accept"],
    "deny":        ["restrict", "block", "reject", "forbid", "disallow", "refuse"],
    "role":        ["permission", "authority", "privilege", "access", "right"],
    # Data / IO
    "send":        ["submit", "post", "dispatch", "transmit", "push", "forward"],
    "receive":     ["get", "accept", "retrieve", "pull", "consume", "ingest"],
    "store":       ["save", "persist", "record", "write", "cache"],
    "validate":    ["verify", "check", "confirm", "ensure", "enforce"],
    "parse":       ["extract", "analyze", "decode", "interpret", "read"],
    # Notifications
    "notify":      ["alert", "inform", "email", "message", "remind", "ping"],
    "report":      ["summary", "dashboard", "overview", "statistics", "log"],
    # File / Project operations
    "upload":      ["import", "load", "ingest", "attach"],
    "download":    ["export", "extract", "pull", "fetch"],
    "import":      ["upload", "load", "fetch", "pull"],
    "extract":     ["parse", "read", "unzip", "decompress", "unpack"],
    "analyze":     ["process", "inspect", "evaluate", "assess", "examine"],
    "generate":    ["create", "produce", "build", "output", "make"],
    "detect":      ["find", "identify", "discover", "locate", "flag"],
    "classify":    ["categorize", "label", "tag", "annotate", "group"],
    # Matching / NLP
    "match":       ["compare", "align", "map", "link", "associate"],
    "score":       ["rank", "rate", "measure", "calculate", "compute"],
    "similarity":  ["relevance", "distance", "closeness", "alignment"],
    "keyword":     ["term", "token", "word", "phrase", "concept"],
    "requirement": ["specification", "feature", "constraint", "rule", "criterion"],
    # Code / Dev
    "implement":   ["code", "develop", "build", "program", "write"],
    "execute":     ["run", "invoke", "call", "trigger", "launch"],
    "deploy":      ["release", "publish", "install", "ship"],
    "test":        ["verify", "validate", "check", "assert", "unit test"],
    "debug":       ["fix", "resolve", "troubleshoot", "repair"],
    "configure":   ["setup", "initialize", "set", "define", "configure"],
    # Error handling
    "error":       ["exception", "failure", "fault", "bug", "issue"],
    "handle":      ["manage", "catch", "process", "deal", "resolve"],
    "display":     ["show", "render", "present", "output", "print"],

    # Added for ISSUE-3 & ISSUE-5 (Alerting / Monitoring)
    "route":       ["dead-letter", "queue", "channel"],
    "malformed":   ["invalid", "corrupt", "bad"],
    "threshold":   ["exceeded", "limit", "boundary"],
    "monitor":     ["track", "observe", "watch", "trigger"],
    "anomaly":     ["detect", "unusual", "outlier", "abnormal"],
    "alert":       ["notify", "warn", "signal", "alarm"],
    "exceed":      ["surpass", "breach", "over"],
}

# English stopwords + domain-specific additions
# NOTE: Action verbs like "allow", "deny", "enable" are intentionally NOT here
_ENGLISH_SW = set(stopwords.words("english"))
_DOMAIN_SW: Set[str] = {
    "shall", "must", "will", "should", "may", "need", "needs", "able",
    "system", "application", "app", "software", "platform", "module",
    "functionality", "feature", "behavior", "provide", "support",
    "give", "make", "use", "used", "using",
}
ALL_STOPWORDS = _ENGLISH_SW | _DOMAIN_SW

# POS tags for keyword extraction
KEEP_POS = {
    "NN", "NNS", "NNP", "NNPS",   # nouns
    "VB", "VBD", "VBG", "VBN", "VBP", "VBZ",  # verbs
    "JJ", "JJR", "JJS",            # adjectives
}

ACTION_VERB_POS = {"VB", "VBP", "VBZ", "VBD", "VBG", "VBN"}

ACTION_VERB_BLOCKLIST = {
    "shall", "must", "will", "should", "may", "need", "needs",
    "be", "is", "are", "was", "were", "been", "being",
    "have", "has", "had", "do", "does", "did",
    "make", "made", "use", "used", "provide", "provided",
    "ensure", "note", "include", "contain",
    "incorrect", "appropriate", "successful", "required",
    "registered", "existing", "missing", "implemented", "divided",
}

PHRASAL_VERBS = {
    "log in":  "login",
    "log out": "logout",
    "sign in": "signin",
    "sign up": "signup",
    "sign out": "signout",
    "set up":  "setup",
    "opt in":  "optin",
    "opt out": "optout",
}

_POS_MAP = {
    "J": wordnet.ADJ,
    "V": wordnet.VERB,
    "N": wordnet.NOUN,
    "R": wordnet.ADV,
}


def _nltk_pos_to_wordnet(tag: str):
    return _POS_MAP.get(tag[0], wordnet.NOUN)


class NLPPipeline:
    def __init__(self):
        self._lemmatizer = WordNetLemmatizer()
        self._extra_terms: Set[str] = set()
        engine = "spaCy" if SPACY_AVAILABLE else "NLTK"
        logger.info("NLPPipeline initialized — engine: %s", engine)

    def add_project_terms(self, terms: List[str]):
        """
        Inject project-specific vocabulary (class names, method names, etc.)
        These are added directly to keywords without going through the NLP pipeline,
        boosting matching accuracy for domain-specific terms.
        """
        self._extra_terms = {t.lower() for t in terms if t and len(t) > 2}

    def process(self, text: str) -> dict:
        """
        Full Phase 2 + 3 NLP pipeline.
        Returns dict with: tokens, keywords, action_verbs, warnings
        """
        warnings = []
        text = text.strip()

        # Step 4: Phrasal verb normalization (case-insensitive, preserves casing elsewhere)
        text_normalized = self._normalize_phrasal_verbs(text)

        if SPACY_AVAILABLE:
            return self._process_spacy(text_normalized, warnings)
        else:
            return self._process_nltk(text_normalized, warnings)

    # ── spaCy pipeline (primary) ──────────────────────────────────────────────

    def _process_spacy(self, text: str, warnings: list) -> dict:
        doc = _nlp_spacy(text)

        # Action verbs: from full sentence with context
        action_verbs = []
        seen_av: Set[str] = set()
        for token in doc:
            if (token.pos_ == "VERB"
                    and token.lemma_.lower() not in ALL_STOPWORDS
                    and token.lemma_.lower() not in ACTION_VERB_BLOCKLIST
                    and re.match(r'^[a-z]{2,}$', token.lemma_.lower())):
                lv = token.lemma_.lower()
                if lv not in seen_av:
                    seen_av.add(lv)
                    action_verbs.append(lv)

        # Tokens: lemmatized, lowercased, alpha-only, no stopwords, no punct
        tokens = []
        seen_t: Set[str] = set()
        for token in doc:
            if (not token.is_stop
                    and not token.is_punct
                    and not token.is_space
                    and token.is_alpha
                    and len(token.text) > 1):
                lemma = token.lemma_.lower()
                if lemma not in ALL_STOPWORDS and lemma not in seen_t:
                    seen_t.add(lemma)
                    tokens.append(lemma)

        # Keywords: nouns, verbs, adjectives from tokens
        doc2 = _nlp_spacy(" ".join(tokens))
        keywords_base = [
            token.text for token in doc2
            if token.pos_ in {"NOUN", "VERB", "ADJ", "PROPN"}
        ]

        if not tokens:
            warnings.append("No meaningful tokens extracted after preprocessing")
        if not action_verbs:
            warnings.append("No action verbs detected — consider adding a clear action verb")

        keywords_expanded = self._expand_synonyms(keywords_base)

        # Inject project-specific terms
        for term in self._extra_terms:
            if term not in set(keywords_expanded):
                keywords_expanded.append(term)

        return {
            "tokens": tokens,
            "keywords": keywords_expanded,
            "action_verbs": action_verbs,
            "warnings": warnings,
        }

    # ── NLTK pipeline (fallback) ──────────────────────────────────────────────

    def _process_nltk(self, text: str, warnings: list) -> dict:
        raw_tokens = word_tokenize(text)

        # Action verbs: detect on full token list (with context)
        full_lower = [t.lower() for t in raw_tokens]
        full_tagged = pos_tag(full_lower)
        action_verbs: List[str] = []
        seen_av: Set[str] = set()
        for word, tag in full_tagged:
            if (tag in ACTION_VERB_POS
                    and re.match(r'^[a-z]{2,}$', word)
                    and word not in ALL_STOPWORDS
                    and word not in ACTION_VERB_BLOCKLIST
                    and word not in seen_av):
                seen_av.add(word)
                action_verbs.append(word)

        # Tokens: lowercase → stop-word removal → alpha filter → lemmatize
        lower_tokens = [t.lower() for t in raw_tokens]
        filtered     = [t for t in lower_tokens if t not in ALL_STOPWORDS]
        alpha_tokens = [t for t in filtered if re.match(r'^[a-z]{2,}$', t)]

        tagged_for_lemma = pos_tag(alpha_tokens)
        lemmatized = [
            self._lemmatizer.lemmatize(w, _nltk_pos_to_wordnet(tag))
            for w, tag in tagged_for_lemma
        ]
        tokens: List[str] = []
        seen_t: Set[str] = set()
        for t in lemmatized:
            if t not in seen_t:
                seen_t.add(t)
                tokens.append(t)

        if not tokens:
            warnings.append("No meaningful tokens extracted after preprocessing")

        tagged = pos_tag(tokens)
        keywords_base = [w for w, tag in tagged if tag in KEEP_POS]

        if not action_verbs:
            warnings.append("No action verbs detected — consider adding a clear action verb")

        keywords_expanded = self._expand_synonyms(keywords_base)

        for term in self._extra_terms:
            if term not in set(keywords_expanded):
                keywords_expanded.append(term)

        return {
            "tokens": tokens,
            "keywords": keywords_expanded,
            "action_verbs": action_verbs,
            "warnings": warnings,
        }

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _normalize_phrasal_verbs(self, text: str) -> str:
        """Case-insensitive phrasal verb replacement, preserving casing of other words."""
        result = text
        for phrase, replacement in PHRASAL_VERBS.items():
            result = re.sub(re.escape(phrase), replacement, result, flags=re.IGNORECASE)
        return result

    def _expand_synonyms(self, keywords: List[str]) -> List[str]:
        """Domain-curated synonym expansion. WordNet disabled (produces noise)."""
        seen = set(keywords)
        expanded = list(keywords)
        for kw in keywords:
            for synonym in DOMAIN_SYNONYMS.get(kw, []):
                if synonym not in seen:
                    seen.add(synonym)
                    expanded.append(synonym)
        return expanded
