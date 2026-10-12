"""
EzzyHub vs. Wang et al. (QRS 2022) benchmark harness
=====================================================

Drives the LIVE matching-service over the iTrust dataset, builds a
requirement x code-file score matrix, and computes Precision / Recall /
F-measure at the same "Acceptable / Good / Excellent" recall cut-points
used in the paper (Hayes et al. criteria), so EzzyHub's numbers are
directly comparable to Table 6/7/9/10 of the paper.

USAGE
-----
1. Start matching-service with a high top-k so we see the full ranking,
   not just the production top-5:

     cd matching-service
     mvn spring-boot:run -Dspring-boot.run.arguments=--matching.top-k-chunks=9999

   (Also make sure python-microservice (:8000) is running — matching-service
   calls it internally for embeddings.)

2. Run:

     python evaluate.py --dataset-dir /path/to/iTrust --out-dir ./results

   --dataset-dir must contain: req/ (use case .txt files), code/ (.java files),
   and itrust_solution_links.txt (oracle).

OUTPUT
------
results/score_matrix.csv      requirement x code-file matrix of best score
results/score_matrix_full.csv same, but with cosine/jaccard components too (long format)
results/metrics_report.json   P/R/F at recall cut-points + raw curve
results/metrics_report.txt    human-readable summary
"""

import argparse
import json
import os
import sys
import zipfile
import io
import time
from collections import defaultdict

import requests


# ──────────────────────────────────────────────────────────────────────────
# 1. Dataset loading
# ──────────────────────────────────────────────────────────────────────────

def load_oracle(solution_links_path):
    """
    Parses itrust_solution_links.txt lines like:
        UC10E1.txt: GetUserNameAction.java
    Returns dict: req_id -> set(code_file_name)
    """
    oracle = defaultdict(set)
    with open(solution_links_path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or ":" not in line:
                continue
            req_part, code_part = line.split(":", 1)
            req_id = req_part.strip()
            code_file = code_part.strip()
            if req_id.endswith(".txt"):
                req_id = req_id[:-4]
            oracle[req_id].add(code_file)
    return oracle


def load_requirements(req_dir):
    """Returns list of {"reqId": ..., "reqText": ...} for every .txt in req_dir."""
    reqs = []
    for fname in sorted(os.listdir(req_dir)):
        if not fname.endswith(".txt"):
            continue
        path = os.path.join(req_dir, fname)
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            text = f.read().strip()
        req_id = fname[:-4]
        if text:
            reqs.append({"reqId": req_id, "reqText": text})
    return reqs


def zip_code_dir(code_dir):
    """Zips all .java files under code_dir into an in-memory buffer."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(code_dir):
            for fname in files:
                if fname.endswith(".java"):
                    full_path = os.path.join(root, fname)
                    arcname = os.path.relpath(full_path, code_dir)
                    zf.write(full_path, arcname)
    buf.seek(0)
    return buf


# ──────────────────────────────────────────────────────────────────────────
# 2. Driving the live matching-service
# ──────────────────────────────────────────────────────────────────────────

def run_matching(matching_url, code_dir, requirements, batch_size=40, timeout=600):
    """
    Calls POST {matching_url}/api/v1/match in batches (large datasets can be
    slow / memory heavy server-side, so we chunk requirements rather than
    sending all 131 in one request — adjust batch_size if your service
    handles big batches fine).

    Returns: list of RequirementMatchResult dicts (raw JSON from the service).
    """
    all_matches = []
    warnings_seen = []

    for i in range(0, len(requirements), batch_size):
        batch = requirements[i : i + batch_size]
        zip_buf = zip_code_dir(code_dir)

        files = {"file": ("itrust_code.zip", zip_buf, "application/zip")}
        data = {"requirements": json.dumps(batch)}

        print(f"  -> matching batch {i // batch_size + 1} "
              f"({len(batch)} requirements)...", flush=True)

        t0 = time.time()
        resp = requests.post(f"{matching_url}/api/v1/match",
                              files=files, data=data, timeout=timeout)
        elapsed = time.time() - t0

        if resp.status_code != 200:
            print(f"     ERROR {resp.status_code}: {resp.text[:500]}")
            continue

        report = resp.json()
        matches = report.get("requirementMatches", [])
        all_matches.extend(matches)
        warnings_seen.extend(report.get("warnings", []) or [])
        print(f"     ok in {elapsed:.1f}s — "
              f"{report.get('implementedCount')} impl / "
              f"{report.get('partialCount')} partial / "
              f"{report.get('missingCount')} missing")

    return all_matches, warnings_seen


# ──────────────────────────────────────────────────────────────────────────
# 3. Building the score matrix
# ──────────────────────────────────────────────────────────────────────────

def build_score_matrix(matches):
    """
    From a list of RequirementMatchResult dicts, build:
      matrix[reqId][codeFileName] = best score seen for that (req, file) pair
      detail[reqId][codeFileName] = {"score", "cosineScore", "jaccardScore", "chunkType", "methodName"}

    A code file is identified by the basename of filePath (e.g. "GetUserNameAction.java"),
    matching the granularity of the iTrust oracle (class-level links).
    """
    matrix = defaultdict(dict)
    detail = defaultdict(dict)

    for m in matches:
        req_id = m.get("reqId")
        top_chunks = m.get("topChunks") or []
        for chunk in top_chunks:
            file_path = chunk.get("filePath") or ""
            file_name = os.path.basename(file_path)
            score = chunk.get("score", 0.0)

            if file_name not in matrix[req_id] or score > matrix[req_id][file_name]:
                matrix[req_id][file_name] = score
                detail[req_id][file_name] = {
                    "score": score,
                    "cosineScore": chunk.get("cosineScore"),
                    "jaccardScore": chunk.get("jaccardScore"),
                    "chunkType": chunk.get("chunkType"),
                    "methodName": chunk.get("methodName"),
                }

        # Also record the requirement-level best score/status even if topChunks
        # didn't carry a matching file name for some reason.
        if req_id not in matrix:
            matrix[req_id] = {}

    return matrix, detail


def write_score_matrix_csv(matrix, all_code_files, out_path):
    import csv
    req_ids = sorted(matrix.keys())
    code_files = sorted(all_code_files)

    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["reqId"] + code_files)
        for req_id in req_ids:
            row = [req_id]
            for cf in code_files:
                row.append(round(matrix[req_id].get(cf, 0.0), 4))
            writer.writerow(row)


# ──────────────────────────────────────────────────────────────────────────
# 4. Metrics: Precision / Recall / F-measure, Hayes-style cut points
# ──────────────────────────────────────────────────────────────────────────

def precision_recall_f(predicted_links, true_links):
    """
    predicted_links / true_links: set of (req_id, code_file) tuples.
    """
    if not predicted_links:
        precision = 0.0
    else:
        precision = len(predicted_links & true_links) / len(predicted_links)

    if not true_links:
        recall = 0.0
    else:
        recall = len(predicted_links & true_links) / len(true_links)

    if precision + recall == 0:
        f = 0.0
    else:
        f = 2 * precision * recall / (precision + recall)

    return precision, recall, f


def sweep_thresholds(matrix, oracle, thresholds=None):
    """
    For a range of score thresholds, compute P/R/F treating any (req,file)
    pair with score >= threshold as a "candidate trace link" — same idea as
    the paper's Selectivity Rate sweep (Section II-F), just driven by score
    threshold instead of candidate-count percentile (equivalent ranking).

    Returns list of dicts: {"threshold": t, "precision": p, "recall": r, "f": f,
                             "num_links": n}
    """
    if thresholds is None:
        thresholds = [round(x * 0.01, 2) for x in range(0, 101, 1)]

    true_links = set()
    for req_id, files in oracle.items():
        for f in files:
            true_links.add((req_id, f))

    curve = []
    for t in thresholds:
        predicted = set()
        for req_id, files in matrix.items():
            for file_name, score in files.items():
                if score >= t:
                    predicted.add((req_id, file_name))
        p, r, f1 = precision_recall_f(predicted, true_links)
        curve.append({"threshold": t, "precision": p, "recall": r,
                      "f": f1, "num_links": len(predicted)})
    return curve


def nearest_to_recall(curve, target_recall):
    """Finds the curve point whose recall is closest to (and >=, if possible) target_recall."""
    candidates = [pt for pt in curve if pt["recall"] >= target_recall]
    if candidates:
        # Highest threshold (most precise) among those that still clear the bar
        return max(candidates, key=lambda pt: pt["threshold"])
    # fallback: closest recall overall
    return min(curve, key=lambda pt: abs(pt["recall"] - target_recall))


def status_based_metrics(matches, oracle, implemented_threshold, partial_threshold):
    """
    Evaluate using EzzyHub's actual IMPLEMENTED/PARTIAL/MISSING classification
    rather than a raw score threshold sweep — i.e. "does EzzyHub call the
    requirement covered when it truly has a linked class, and not-covered
    when it doesn't". This is the metric closest to your product's real
    output, complementary to the IR-style P/R/F above.
    """
    true_has_link = {req_id for req_id, files in oracle.items() if files}
    tp = fp = tn = fn = 0
    partial = 0

    for m in matches:
        req_id = m.get("reqId")
        status = m.get("status")
        has_true_link = req_id in true_has_link and len(oracle[req_id]) > 0

        if status == "IMPLEMENTED":
            if has_true_link:
                tp += 1
            else:
                fp += 1
        elif status == "MISSING":
            if has_true_link:
                fn += 1
            else:
                tn += 1
        elif status == "PARTIAL":
            partial += 1

    total = tp + fp + tn + fn + partial
    return {
        "true_positive": tp, "false_positive": fp,
        "true_negative": tn, "false_negative": fn,
        "partial": partial, "total": total,
    }


# ──────────────────────────────────────────────────────────────────────────
# 5. Main
# ──────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="EzzyHub iTrust benchmark")
    parser.add_argument("--dataset-dir", required=True,
                         help="Path to iTrust dataset dir (contains req/, code/, itrust_solution_links.txt)")
    parser.add_argument("--out-dir", default="./results")
    parser.add_argument("--matching-url", default="http://localhost:8083")
    parser.add_argument("--batch-size", type=int, default=40)
    parser.add_argument("--limit", type=int, default=None,
                         help="Optional: only test first N requirements (smoke test)")
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)

    req_dir = os.path.join(args.dataset_dir, "req")
    code_dir = os.path.join(args.dataset_dir, "code")
    solution_path = os.path.join(args.dataset_dir, "itrust_solution_links.txt")

    print("Loading dataset...")
    oracle = load_oracle(solution_path)
    requirements = load_requirements(req_dir)
    if args.limit:
        requirements = requirements[: args.limit]

    all_code_files = set()
    for root, _, files in os.walk(code_dir):
        for fname in files:
            if fname.endswith(".java"):
                all_code_files.add(fname)

    print(f"  {len(requirements)} requirements, {len(all_code_files)} code files, "
          f"{sum(len(v) for v in oracle.values())} oracle links")

    print("Running matching-service over dataset (this hits your live services)...")
    matches, warnings = run_matching(args.matching_url, code_dir, requirements,
                                      batch_size=args.batch_size)

    if not matches:
        print("No matches returned — check that matching-service, "
              "python-microservice, and requirement-analyzer are all running.")
        sys.exit(1)

    print(f"Got {len(matches)} requirement match results "
          f"({len(warnings)} warnings)")

    print("Building score matrix...")
    matrix, detail = build_score_matrix(matches)
    write_score_matrix_csv(matrix, all_code_files,
                            os.path.join(args.out_dir, "score_matrix.csv"))

    with open(os.path.join(args.out_dir, "score_matrix_detail.json"), "w") as f:
        json.dump(detail, f, indent=2)

    print("Computing metrics...")
    curve = sweep_thresholds(matrix, oracle)

    cut_points = {
        "Acceptable (R=60%)": nearest_to_recall(curve, 0.60),
        "Good (R=70%)": nearest_to_recall(curve, 0.70),
        "Excellent (R=80%)": nearest_to_recall(curve, 0.80),
    }

    status_metrics = status_based_metrics(
        matches, oracle,
        implemented_threshold=0.50,   # matches matching.threshold.implemented
        partial_threshold=0.30,       # matches matching.threshold.partial
    )

    report = {
        "dataset": "iTrust",
        "num_requirements": len(requirements),
        "num_code_files": len(all_code_files),
        "num_oracle_links": sum(len(v) for v in oracle.values()),
        "cut_points": cut_points,
        "full_curve": curve,
        "status_based_metrics": status_metrics,
        "warnings": warnings,
    }

    with open(os.path.join(args.out_dir, "metrics_report.json"), "w") as f:
        json.dump(report, f, indent=2)

    # Human-readable side-by-side report (EzzyHub vs. the paper), written UTF-8.
    # See report_format.py — kept in its own module so already-saved
    # metrics_report.json files can be re-rendered by regenerate_reports.py too.
    from report_format import write_human_readable_report
    write_human_readable_report(report, os.path.join(args.out_dir, "metrics_report.txt"))

    print(f"\nDone. Results written to {args.out_dir}/")
    print(f"  - score_matrix.csv")
    print(f"  - score_matrix_detail.json")
    print(f"  - metrics_report.json")
    print(f"  - metrics_report.txt")


if __name__ == "__main__":
    main()
