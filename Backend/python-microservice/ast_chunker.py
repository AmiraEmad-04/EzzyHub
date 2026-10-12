"""
AST-based Java code chunker for EzzyHub Matching Service — v2.2
================================================================

WHAT CHANGED v2.1 → v2.2:
  - chunk_text now expands camelCase to human-readable words
    importFromGitHub → "import From GitHub" → SBERT sees 3 tokens, not 1
    processZipUpload → "process Zip Upload"
    detectClassType  → "detect Class Type"
  - Keywords also include camelCase-split parts of method/class names
    so Jaccard matching picks up "github", "import", "repository" etc.

WHY THIS MATTERS:
  Before: chunk_text = "Map CodeAcquisitionService.importFromGitHub(String repositoryUrl)"
  SBERT tokenizer sees "importFromGitHub" as one unknown token → bad embedding.

  After:  chunk_text = "Map Code Acquisition Service. import From GitHub ( String repository Url )"
  SBERT tokenizer sees real English words → much better embedding.
"""

import logging
import os
import re
from typing import List, Dict, Any

logger = logging.getLogger(__name__)

# Regex fallback runs backtracking-prone patterns (_METHOD_RE, _CLASS_RE) over
# raw source. Uncapped input length turns a crafted/huge file into a ReDoS.
# Cap the input the regex engine ever sees; javalang (the primary path) has
# no such limit.
MAX_REGEX_FALLBACK_CHARS = int(os.getenv("MAX_REGEX_FALLBACK_CHARS", "200000"))

try:
    import javalang
    _HAS_JAVALANG = True
except ImportError:
    _HAS_JAVALANG = False
    logger.warning("javalang not installed — using regex fallback. "
                   "Install with: pip install javalang")


# ── camelCase splitter ────────────────────────────────────────────────────────

def _split_camel(name: str) -> str:
    """
    Split camelCase / PascalCase identifiers into space-separated words.
    importFromGitHub   → 'import From Git Hub'
    CodeAcquisitionService → 'Code Acquisition Service'
    repositoryUrl      → 'repository Url'
    MAX_ZIP_SIZE_BYTES → 'MAX ZIP SIZE BYTES'
    """
    # Handle SCREAMING_SNAKE_CASE
    if '_' in name:
        return ' '.join(p.capitalize() for p in name.split('_') if p)
    # Insert space before uppercase letters that follow lowercase
    s = re.sub(r'([a-z0-9])([A-Z])', r'\1 \2', name)
    # Insert space before uppercase sequences (acronyms) followed by lowercase
    s = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1 \2', s)
    return s


def _camel_keywords(name: str) -> List[str]:
    """Extract meaningful lowercase keywords from a camelCase identifier."""
    parts = re.sub(r'([a-z0-9])([A-Z])', r'\1 \2', name)
    parts = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1 \2', parts)
    words = re.split(r'[\s_]+', parts)
    stop = {'get', 'set', 'is', 'has', 'do', 'on', 'at', 'by', 'in',
            'of', 'to', 'or', 'and', 'a', 'an', 'the', 'id', 'ids'}
    return [w.lower() for w in words if len(w) > 2 and w.lower() not in stop]


# ── Public API ────────────────────────────────────────────────────────────────

def chunk_java_source(source: str, file_path: str) -> List[Dict[str, Any]]:
    if _HAS_JAVALANG:
        try:
            return _javalang_chunk(source, file_path)
        except Exception as exc:
            logger.warning("javalang parse failed for %s (%s) → regex fallback", file_path, exc)
    return _regex_chunk(source, file_path)


# ── Comment / Javadoc extraction ──────────────────────────────────────────────
#
# WHY THIS EXISTS (v2.3):
#   Wang et al. (QRS 2022) found that code COMMENTS (CMT) are the single most
#   valuable feature for requirements-to-code trace link generation — the
#   "CN_MN_CMT" combination beat every combination without comments, on every
#   dataset with annotation density > 0.2 (iTrust = 0.2283, above threshold).
#
#   javalang strips comments during tokenization — they never reach the AST —
#   so chunk_text was previously built ONLY from identifier names. This meant
#   EzzyHub was effectively running the paper's worst-performing "CN_MN" row.
#
#   This section extracts Javadoc/block/line comments via regex on the raw
#   source (independent of javalang) and attaches the comment immediately
#   preceding each class/method/constructor declaration to its chunk_text.

_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT_RE = re.compile(r"//[^\n]*")

_MAX_COMMENT_WORDS = 60          # keep chunk_text from ballooning on huge doc comments
_MAX_BLANK_LINES_BETWEEN = 2      # tolerate a couple of blank lines between comment and decl


def _clean_comment_text(raw: str) -> str:
    """Strips comment markers, leading '*' continuation, @param/@return doclet
    tag names (keep their content), and collapses whitespace."""
    text = raw
    text = re.sub(r"^/\*+", "", text)
    text = re.sub(r"\*/$", "", text)
    text = re.sub(r"^//", "", text, flags=re.MULTILINE)
    text = re.sub(r"(?m)^\s*\*\s?", "", text)        # strip leading " * " on each line
    text = re.sub(r"@(param|return|throws)\s+(\w+)?", r"\2 ", text)  # keep doclet content, drop tag noise
    text = re.sub(r"\{@\w+\s*", "", text)            # {@link ...} -> drop the tag itself
    text = text.replace("}", " ")
    text = re.sub(r"\s+", " ", text).strip()
    words = text.split()
    if len(words) > _MAX_COMMENT_WORDS:
        words = words[:_MAX_COMMENT_WORDS]
    return " ".join(words)


_ANNOTATION_LINE_RE = re.compile(r"^\s*@\w+")


def _extract_comment_map(source: str) -> Dict[int, str]:
    """
    Scans raw source for all comments and returns a map:
        comment_end_line (1-indexed, the line the comment's last char is on)
        -> cleaned comment text
    """
    spans = []
    comment_map: Dict[int, str] = {}

    for m in _BLOCK_COMMENT_RE.finditer(source):
        start_line = source[: m.start()].count("\n") + 1
        end_line = source[: m.end()].count("\n") + 1
        spans.append((start_line, end_line))
        text = _clean_comment_text(m.group(0))
        if text:
            comment_map[end_line] = text

    block_lines = set()
    for s, e in spans:
        block_lines.update(range(s, e + 1))

    for m in _LINE_COMMENT_RE.finditer(source):
        line_no = source[: m.start()].count("\n") + 1
        if line_no in block_lines:
            continue
        text = _clean_comment_text(m.group(0))
        if text:
            comment_map[line_no] = text

    return comment_map


def _comment_for_line(source_lines: List[str], comment_map: Dict[int, str], decl_line: int) -> str:
    """
    Walks upward from the line directly above decl_line, skipping ONLY blank
    lines and annotation lines (Javadoc can have @Override/@SuppressWarnings
    between it and the declaration). The moment a line is anything else, we
    stop — if that line is a comment end-line, attach it; otherwise the
    declaration has no comment of its own. This is what was missing in the
    first version: line-number proximity alone let a SHORT method's body
    look "close enough" to the previous method's comment and wrongly inherit
    it. Walking the real lines and stopping at the first non-blank,
    non-annotation line fixes that.
    """
    if decl_line <= 1:
        return ""
    line_idx = decl_line - 1  # convert to 0-indexed line directly above decl_line (1-indexed decl_line - 1 - 1 + 1)
    # source_lines is 0-indexed; line number L corresponds to source_lines[L-1]
    cursor = decl_line - 1  # 1-indexed line number to inspect, starting just above declaration
    while cursor >= 1:
        if cursor in comment_map:
            return comment_map[cursor]
        line_text = source_lines[cursor - 1] if cursor - 1 < len(source_lines) else ""
        if line_text.strip() == "" or _ANNOTATION_LINE_RE.match(line_text):
            cursor -= 1
            continue
        break  # hit real code with no comment — stop, no comment attaches
    return ""


# ── javalang-based implementation ─────────────────────────────────────────────

def _javalang_chunk(source: str, file_path: str) -> List[Dict[str, Any]]:
    chunks: List[Dict[str, Any]] = []
    tree = javalang.parse.parse(source)
    comment_map = _extract_comment_map(source)
    source_lines = source.split("\n")

    for _, node in tree.filter(javalang.tree.TypeDeclaration):
        class_name = node.name
        class_line = node.position.line if node.position else 0

        chunks.append({
            "file_path":   file_path,
            "class_name":  class_name,
            "method_name": None,
            "chunk_type":  "class",
            "chunk_text":  _class_text(node, _comment_for_line(source_lines, comment_map, class_line)),
            "line_start":  class_line,
        })

        for method in (node.methods or []):
            method_line = method.position.line if method.position else 0
            chunks.append({
                "file_path":   file_path,
                "class_name":  class_name,
                "method_name": method.name,
                "chunk_type":  "method",
                "chunk_text":  _method_text(class_name, method,
                                            _comment_for_line(source_lines, comment_map, method_line)),
                "line_start":  method_line,
            })

        for ctor in (node.constructors or []):
            ctor_line = ctor.position.line if ctor.position else 0
            chunks.append({
                "file_path":   file_path,
                "class_name":  class_name,
                "method_name": "<init>",
                "chunk_type":  "constructor",
                "chunk_text":  _ctor_text(class_name, ctor,
                                          _comment_for_line(source_lines, comment_map, ctor_line)),
                "line_start":  ctor_line,
            })

    return chunks if chunks else _regex_chunk(source, file_path)


def _class_text(node, comment: str = "") -> str:
    """
    Human-readable class description with camelCase expansion.
    '@Service class CodeAcquisitionService' →
    '@Service class Code Acquisition Service — handles code acquisition'

    If a Javadoc/comment was found immediately above the class declaration,
    it is appended (paper's CMT feature — the single highest-leverage signal
    for trace-link quality per Wang et al. QRS 2022).
    """
    parts: List[str] = []
    for ann in (node.annotations or []):
        parts.append(f"@{ann.name}")
    kind = "interface" if isinstance(node, javalang.tree.InterfaceDeclaration) else "class"
    # Expand class name camelCase
    expanded_name = _split_camel(node.name)
    parts.append(f"{kind} {expanded_name}")
    ext = getattr(node, "extends", None)
    if ext:
        ext_name = ext.name if hasattr(ext, "name") else str(ext)
        parts.append(f"extends {_split_camel(ext_name)}")
    for iface in (getattr(node, "implements", None) or []):
        iname = iface.name if hasattr(iface, "name") else str(iface)
        parts.append(f"implements {_split_camel(iname)}")
    # Add camelCase keyword hints as natural language suffix
    kws = _camel_keywords(node.name)
    if kws:
        parts.append(f"— {' '.join(kws)}")
    if comment:
        parts.append(f"— {comment}")
    return " ".join(parts)


def _method_text(class_name: str, method, comment: str = "") -> str:
    """
    Human-readable method description with camelCase expansion.
    'importFromGitHub' → 'import source code from GitHub repository'

    Appends the Javadoc comment immediately above the method, if any —
    this is the paper's CMT feature.
    """
    parts: List[str] = []
    for ann in (method.annotations or []):
        parts.append(f"@{ann.name}")
    # Return type
    ret = ""
    if getattr(method, "return_type", None):
        rt = method.return_type
        ret_name = (rt.name if hasattr(rt, "name") else str(rt))
        ret = _split_camel(ret_name) + " "
    # Expand class and method names
    expanded_class  = _split_camel(class_name)
    expanded_method = _split_camel(method.name)
    # Parameters with camelCase expansion
    params = _params_text_expanded(method.parameters or [])
    parts.append(f"{ret}{expanded_class} {expanded_method} ( {params} )")
    # Add keyword hints derived from camelCase parts
    method_kws  = _camel_keywords(method.name)
    class_kws   = _camel_keywords(class_name)
    all_kws = list(dict.fromkeys(method_kws + class_kws))   # deduplicated, ordered
    if all_kws:
        parts.append(f"— {' '.join(all_kws[:8])}")          # max 8 keywords
    if comment:
        parts.append(f"— {comment}")
    return " ".join(parts)


def _ctor_text(class_name: str, ctor, comment: str = "") -> str:
    params = _params_text_expanded(ctor.parameters or [])
    expanded = _split_camel(class_name)
    kws = _camel_keywords(class_name)
    suffix = f" — {' '.join(kws)}" if kws else ""
    if comment:
        suffix += f" — {comment}"
    return f"constructor {expanded} ( {params} ){suffix}"


def _params_text_expanded(params) -> str:
    """Expand parameter names and types from camelCase."""
    out = []
    for p in params:
        ptype = p.type.name if hasattr(p.type, "name") else str(p.type)
        out.append(f"{_split_camel(ptype)} {_split_camel(p.name)}")
    return ", ".join(out)


def _params_text(params) -> str:
    out = []
    for p in params:
        ptype = p.type.name if hasattr(p.type, "name") else str(p.type)
        out.append(f"{ptype} {p.name}")
    return ", ".join(out)


# ── Regex-based fallback ──────────────────────────────────────────────────────

_CLASS_RE = re.compile(
    r"(?:@\w+\s*)*"
    r"(?:public|protected|private|abstract|final|sealed)?\s*"
    r"(?:static\s+)?(?:class|interface|enum|record)\s+(\w+)",
    re.MULTILINE,
)

_METHOD_RE = re.compile(
    r"(?:@\w+\s*(?:\([^)]*\))?\s*)*"
    r"(?:public|private|protected)\s+"
    r"(?:(?:static|final|synchronized|abstract|default|native)\s+)*"
    r"(?:(?:<[^>]*>\s+)?(?:void|boolean|int|long|double|float|byte|short|char"
    r"|[A-Z]\w*(?:<[^>]*>)?(?:\[\])*)?\s+)?"
    r"(\w+)\s*\(([^)]{0,200})\)",
    re.MULTILINE,
)

_SKIP_KEYWORDS = frozenset({
    "if", "while", "for", "switch", "catch", "else", "return", "new",
    "throw", "assert", "super", "this", "instanceof",
})


def _regex_chunk(source: str, file_path: str) -> List[Dict[str, Any]]:
    if len(source) > MAX_REGEX_FALLBACK_CHARS:
        logger.warning("regex fallback: truncating %s from %d to %d chars before matching",
                       file_path, len(source), MAX_REGEX_FALLBACK_CHARS)
        source = source[:MAX_REGEX_FALLBACK_CHARS]

    chunks: List[Dict[str, Any]] = []
    filename = file_path.rstrip("/").split("/")[-1].replace(".java", "")

    class_name = filename
    cm = _CLASS_RE.search(source)
    if cm:
        class_name = cm.group(1)
        line_no = source[: cm.start()].count("\n") + 1
        expanded = _split_camel(class_name)
        kws = _camel_keywords(class_name)
        suffix = f" — {' '.join(kws)}" if kws else ""
        chunks.append({
            "file_path":   file_path,
            "class_name":  class_name,
            "method_name": None,
            "chunk_type":  "class",
            "chunk_text":  f"class {expanded}{suffix}",
            "line_start":  line_no,
        })

    for m in _METHOD_RE.finditer(source):
        name = m.group(1)
        if name in _SKIP_KEYWORDS:
            continue
        line_no = source[: m.start()].count("\n") + 1
        expanded_name  = _split_camel(name)
        expanded_class = _split_camel(class_name)
        kws = _camel_keywords(name) + _camel_keywords(class_name)
        suffix = f" — {' '.join(dict.fromkeys(kws))}" if kws else ""
        chunks.append({
            "file_path":   file_path,
            "class_name":  class_name,
            "method_name": name,
            "chunk_type":  "method",
            "chunk_text":  f"{expanded_class} {expanded_name}{suffix}",
            "line_start":  line_no,
        })

    if not chunks:
        words = source.split()
        chunks.append({
            "file_path":   file_path,
            "class_name":  filename,
            "method_name": None,
            "chunk_type":  "file",
            "chunk_text":  " ".join(words[:200]),
            "line_start":  1,
        })

    return chunks