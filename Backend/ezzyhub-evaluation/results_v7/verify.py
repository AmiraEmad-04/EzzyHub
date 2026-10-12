#!/usr/bin/env python3
"""
EzzyHub — retrieve-then-VERIFY stage  (the precision fix)
=========================================================
WHAT THIS FIXES
---------------
Today the IMPLEMENTED/PARTIAL/MISSING verdict is decided by a cosine+jaccard
SIMILARITY THRESHOLD. Similarity finds *related* code; it does not *read* code,
so precision is capped (~0.06 at R=60% on iTrust vs the paper's ~0.13 ceiling
for the whole similarity paradigm).

This stage keeps your existing matcher as the RETRIEVAL step (it produces the
candidate shortlist per requirement), then adds a VERIFY step: an LLM (your
Cohere key) reads the candidate code and JUDGES, per requirement, which atomic
sub-criteria are actually implemented and in which file — with evidence.

  status      = derived from how many criteria have implementing code
  percentage  = satisfied_criteria / total_criteria   (interpretable coverage,
                NOT a cosine score relabelled as a percent)
  evidence    = the specific files cited for each criterion

IMPORTANT CEILING (measured on iTrust, top-K shortlist from score_matrix.csv):
  verify can only confirm files RETRIEVAL surfaced. At K=25 the shortlist
  contains a true file for ~77% of linked requirements; at K=30 ~78%. Raising
  recall above that needs better RETRIEVAL (turn on the cross-encoder reranker
  you already have, and/or de-noise jaccard) — verify alone cannot.

USAGE
-----
  export COHERE_API_KEY=...            # PowerShell: $env:COHERE_API_KEY="..."
  python verify.py --score-matrix score_matrix.csv \
                   --dataset-dir /path/to/iTrust \
                   --top-k 25 --out-dir ./verify_results
  # plumbing test without spending the key:
  python verify.py --score-matrix ... --dataset-dir ... --mock
"""
import argparse, csv, json, os, re, sys, time, hashlib
from collections import defaultdict

import requests  # same dep your evaluate.py already uses

COHERE_URL = "https://api.cohere.com/v2/chat"   # matches your LLMService.callCohere


# ---------------------------------------------------------------- data loading
def load_score_matrix(path):
    with open(path, newline="", encoding="utf-8") as f:
        r = csv.reader(f); header = next(r); files = header[1:]; M = {}
        for row in r:
            M[row[0]] = {files[i]: float(row[i + 1]) for i in range(len(files))}
    return M

def load_requirements(req_dir):
    reqs = {}
    for fn in os.listdir(req_dir):
        if fn.endswith(".txt"):
            with open(os.path.join(req_dir, fn), encoding="utf-8", errors="ignore") as f:
                t = f.read().strip()
            if t:
                reqs[fn[:-4]] = t
    return reqs

def basename_to_path(dataset_dir):
    """Look in <dataset>/code first; if that finds no .java, scan the whole
    dataset dir recursively. Returns (map, dir_used)."""
    candidates_dirs = [os.path.join(dataset_dir, "code"), dataset_dir]
    for cdir in candidates_dirs:
        if not os.path.isdir(cdir):
            continue
        m = {}
        for root, _, files in os.walk(cdir):
            for fn in files:
                if fn.endswith(".java"):
                    m[fn] = os.path.join(root, fn)
        if m:
            return m, cdir
    return {}, candidates_dirs[0]

def load_oracle(path):
    o = defaultdict(set)
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if ":" not in line:
                continue
            a, b = line.split(":", 1)
            rid = a.strip()
            if rid.endswith(".txt"):
                rid = rid[:-4]
            o[rid].add(b.strip())
    return o


# ---------------------------------------------------------------- prompt
SYSTEM = (
    "You are a meticulous senior software engineer auditing whether a software "
    "requirement is implemented by a set of candidate source files that were "
    "retrieved as likely-relevant. Judge ONLY from the code shown. Credit a file "
    "as evidence when its classes or methods actually perform the required "
    "capability (a class whose responsibility clearly matches a sub-requirement, "
    "with methods that carry it out, counts as evidence even if not every detail "
    "is visible). Do NOT credit a file that is merely topically related but does "
    "not implement the behaviour. If no shown file implements a sub-requirement, "
    "mark it missing."
)

def build_prompt(req_id, req_text, candidates):
    """candidates: list of (filename, source_text)."""
    sb = [
        f"REQUIREMENT ({req_id}):\n{req_text}\n",
        "Break this requirement into its atomic, independently-checkable sub-criteria "
        "(typically 1-5). For EACH sub-criterion decide, strictly from the candidate "
        "code below, whether it is implemented. Cite the exact file(s) whose code "
        "implements it. If no candidate file implements it, mark it missing with an "
        "empty evidence list. Do not credit a file merely for being topically related "
        "— it must actually implement the sub-criterion.\n",
        "CANDIDATE FILES:\n",
    ]
    for fn, src in candidates:
        sb.append(f"=== FILE: {fn} ===\n{src}\n")
    sb.append(
        "\nReturn ONLY valid JSON (no markdown, start with '{'):\n"
        '{ "criteria": [ {"criterion": "...", '
        '"status": "satisfied" | "missing", '
        '"evidence_files": ["File.java"], "note": "one short sentence"} ] }'
    )
    return "\n".join(sb)


# ---------------------------------------------------------------- llm call
def call_cohere(prompt, model, api_key, timeout=180, retries=4):
    body = {
        "model": model, "max_tokens": 4096, "temperature": 0.2,
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": prompt}],
    }
    headers = {"Authorization": f"Bearer {api_key}",
               "Content-Type": "application/json", "Accept": "application/json"}
    for attempt in range(retries):
        try:
            r = requests.post(COHERE_URL, json=body, headers=headers, timeout=timeout)
            if r.status_code == 200:
                return r.json()["message"]["content"][0]["text"]
            if r.status_code in (429, 500, 502, 503, 529):
                time.sleep(2 ** attempt * 2)
                continue
            raise RuntimeError(f"Cohere {r.status_code}: {r.text[:300]}")
        except requests.RequestException as e:
            if attempt == retries - 1:
                raise
            time.sleep(2 ** attempt * 2)
    raise RuntimeError("Cohere call failed after retries")


def extract_json(raw):
    raw = raw.strip()
    raw = re.sub(r"^```(json)?", "", raw).strip()
    raw = re.sub(r"```$", "", raw).strip()
    s, e = raw.find("{"), raw.rfind("}")
    if s == -1 or e == -1:
        raise ValueError("no JSON object found")
    return json.loads(raw[s:e + 1])


# ---------------------------------------------------------------- verdict
def derive_verdict(parsed):
    crit = parsed.get("criteria", []) or []
    if not crit:
        return "MISSING", 0.0, []
    sat = [c for c in crit if str(c.get("status", "")).lower() == "satisfied"]
    pct = round(100.0 * len(sat) / len(crit), 1)
    status = "IMPLEMENTED" if len(sat) == len(crit) else ("MISSING" if not sat else "PARTIAL")
    evidence = sorted({f for c in sat for f in (c.get("evidence_files") or [])})
    return status, pct, evidence


def mock_llm(req_id, candidates, oracle):
    """Deterministic stub for plumbing tests: 'satisfies' criterion iff a true
    oracle file is in the shortlist. Tests the harness, NOT real verdict quality."""
    true_here = [fn for fn, _ in candidates if fn in oracle.get(req_id, set())]
    crit = [{"criterion": "core capability", "status": "satisfied" if true_here else "missing",
             "evidence_files": true_here[:1], "note": "mock"}]
    return json.dumps({"criteria": crit})


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--score-matrix", required=True)
    ap.add_argument("--dataset-dir", required=True)
    ap.add_argument("--top-k", type=int, default=25)
    ap.add_argument("--out-dir", default="./verify_results")
    ap.add_argument("--max-chars-per-file", type=int, default=8000,
                    help="cap per file so one huge file can't dominate")
    ap.add_argument("--max-total-chars", type=int, default=80000,
                    help="total code budget per requirement prompt")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--model", default=os.environ.get("COHERE_MODEL", "command-r-plus-08-2024"))
    ap.add_argument("--mock", action="store_true", help="run without calling Cohere")
    ap.add_argument("--debug", action="store_true", help="print the raw Cohere reply")
    ap.add_argument("--no-cache", action="store_true", help="ignore cached verdicts")
    ap.add_argument("--only", default=None,
                    help="comma-separated requirement IDs to run (e.g. UC15E1,UC16)")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    cache_dir = os.path.join(args.out_dir, "cache"); os.makedirs(cache_dir, exist_ok=True)

    api_key = os.environ.get("COHERE_API_KEY", "")
    if not args.mock and not api_key:
        sys.exit("Set COHERE_API_KEY (or use --mock).")

    M = load_score_matrix(args.score_matrix)
    reqs = load_requirements(os.path.join(args.dataset_dir, "req"))
    b2p, code_dir_used = basename_to_path(args.dataset_dir)
    oracle = load_oracle(os.path.join(args.dataset_dir, "itrust_solution_links.txt"))

    # ---- preflight: show exactly what was found, so failures are obvious ----
    print("PREFLIGHT")
    print(f"  requirements loaded : {len(reqs)}")
    print(f"  code .java found    : {len(b2p)}   (in: {code_dir_used})")
    print(f"  oracle links        : {sum(len(v) for v in oracle.values())}")
    if len(b2p) == 0:
        print("  !! NO JAVA FILES FOUND — every requirement will be MISSING.")
        print("     Point --dataset-dir at the folder that contains your .java files.")
    if len(reqs) == 0:
        print("  !! NO REQUIREMENT TEXTS FOUND — expected .txt files in a 'req' subfolder.")
    print()

    req_ids = [r for r in M if r in reqs]
    if args.only:
        wanted = {x.strip() for x in args.only.split(",") if x.strip()}
        req_ids = [r for r in req_ids if r in wanted]
    if args.limit:
        req_ids = req_ids[: args.limit]

    verdicts = {}
    for i, rid in enumerate(req_ids, 1):
        top = sorted(M[rid].items(), key=lambda x: -x[1])[: args.top_k]
        candidates = []
        total = 0
        for fn, _ in top:
            p = b2p.get(fn)
            if not p:
                continue
            with open(p, encoding="utf-8", errors="ignore") as f:
                src = f.read()
            if len(src) > args.max_chars_per_file:
                src = src[: args.max_chars_per_file] + "\n/* ...truncated... */"
            if candidates and total + len(src) > args.max_total_chars:
                break  # budget reached — keep the highest-ranked files we have
            candidates.append((fn, src))
            total += len(src)

        ck = hashlib.md5(f"{rid}|{args.model}|{args.top_k}|{args.max_chars_per_file}|{args.max_total_chars}".encode()).hexdigest()
        cpath = os.path.join(cache_dir, ck + ".json")
        raw = None
        if os.path.exists(cpath) and not args.mock and not args.no_cache:
            parsed = json.load(open(cpath))
        else:
            try:
                if args.mock:
                    raw = mock_llm(rid, candidates, oracle)
                else:
                    raw = call_cohere(build_prompt(rid, reqs[rid], candidates), args.model, api_key)
                if args.debug and not args.mock:
                    print(f"    --- raw reply for {rid} (first 800 chars) ---")
                    print("    " + (raw or "")[:800].replace("\n", "\n    "))
                    print("    --- end raw ---")
                parsed = extract_json(raw)
                if not args.mock:
                    json.dump(parsed, open(cpath, "w"))
            except Exception as e:
                print(f"  [{i}/{len(req_ids)}] {rid}: ERROR {e}")
                if raw is not None:
                    print(f"      raw was: {raw[:300]!r}")
                parsed = {"criteria": []}

        if not args.mock and not parsed.get("criteria"):
            print(f"      (note: no criteria parsed for {rid} — empty/odd reply; use --debug to see it)")

        status, pct, evidence = derive_verdict(parsed)
        verdicts[rid] = {"status": status, "coverage_percent": pct,
                         "implementing_files": evidence, "criteria": parsed.get("criteria", [])}
        print(f"  [{i}/{len(req_ids)}] {rid}: {status} {pct}%  "
              f"(candidates={len(candidates)})  evidence={evidence}")

    json.dump(verdicts, open(os.path.join(args.out_dir, "verdicts.json"), "w"), indent=2)

    # ---- evaluation vs oracle ----
    fileset = set(next(iter(M.values())).keys())
    true_links = set((r, f) for r in req_ids for f in oracle.get(r, ()) if f in fileset)
    pred_links = set((r, f) for r in verdicts for f in verdicts[r]["implementing_files"])
    inter = len(pred_links & true_links)
    P = inter / len(pred_links) if pred_links else 0.0
    R = inter / len(true_links) if true_links else 0.0
    F = 2 * P * R / (P + R) if P + R else 0.0

    linked = {r for r in req_ids if any(f in fileset for f in oracle.get(r, ()))}
    tp = sum(1 for r in verdicts if r in linked and verdicts[r]["status"] != "MISSING")
    fp = sum(1 for r in verdicts if r not in linked and verdicts[r]["status"] != "MISSING")
    fn_ = sum(1 for r in verdicts if r in linked and verdicts[r]["status"] == "MISSING")
    tn = sum(1 for r in verdicts if r not in linked and verdicts[r]["status"] == "MISSING")

    lines = [
        "VERIFY-stage results (Cohere judge over top-K retrieval shortlist)" + (" [MOCK]" if args.mock else ""),
        "=" * 64,
        f"requirements judged: {len(verdicts)}   top_k={args.top_k}   model={args.model}",
        "",
        "LINK-LEVEL (verifier-cited files vs oracle) — compare to paper:",
        f"  P={P:.4f}  R={R:.4f}  F={F:.4f}   (predicted links={len(pred_links)})",
        "  paper iTrust best: Acceptable P=0.1293 / Good P=0.0906 / Excellent P=0.0454",
        "",
        "REQUIREMENT-LEVEL covered vs not-covered:",
        f"  TP={tp}  FP={fp}  TN={tn}  FN={fn_}   (linked reqs={len(linked)})",
        f"  precision={tp/(tp+fp) if tp+fp else 0:.3f}  recall={tp/(tp+fn_) if tp+fn_ else 0:.3f}",
        ]
    report = "\n".join(lines)
    print("\n" + report)
    open(os.path.join(args.out_dir, "verify_report.txt"), "w").write(report + "\n")
    # predicted links csv
    with open(os.path.join(args.out_dir, "verify_links.csv"), "w", newline="") as f:
        w = csv.writer(f); w.writerow(["reqId", "file", "status", "coverage_percent"])
        for r in verdicts:
            for fn in verdicts[r]["implementing_files"]:
                w.writerow([r, fn, verdicts[r]["status"], verdicts[r]["coverage_percent"]])
    print(f"\nWrote: verdicts.json, verify_report.txt, verify_links.csv  (+ cache/ for cheap reruns)")


if __name__ == "__main__":
    main()