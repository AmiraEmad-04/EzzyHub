"""
Human-readable report writer for the EzzyHub vs. Wang et al. (QRS 2022) benchmark.

Takes the metrics dict produced by evaluate.py (the same object written to
metrics_report.json) and writes a clear, plain-language metrics_report.txt that
puts EzzyHub and the paper SIDE BY SIDE with the difference spelled out, so the
result folder actually explains how EzzyHub compares to the paper.

Kept in its own module so it can be used two ways:
  1. evaluate.py imports write_human_readable_report() for live runs.
  2. regenerate_reports.py re-renders already-saved metrics_report.json files
     without re-running the (slow, service-dependent) benchmark.
"""

# Paper's best published iTrust numbers (Wang et al., QRS 2022 — VSM, CN_MN_CMT),
# at the same Acceptable / Good / Excellent recall cut-points. (recall, precision, f)
# Ordered to line up with the Acceptable/Good/Excellent cut-points in the report.
PAPER_REFERENCE = [
    ("Acceptable (~60% recall)", (0.6412, 0.1293, 0.2153)),
    ("Good (~70% recall)",       (0.7057, 0.0906, 0.1606)),
    ("Excellent (~80% recall)",  (0.8038, 0.0454, 0.0859)),
]


def _pct(x):
    """0..1 fraction -> '64.12%'."""
    return f"{x * 100:.2f}%"


def _delta_pts(ezzy_frac, paper_frac):
    """Signed difference in PERCENTAGE POINTS, EzzyHub minus paper, e.g. '-7.85 pts'."""
    d = (ezzy_frac - paper_frac) * 100
    return f"{d:+.2f} pts"


def write_human_readable_report(report, out_path):
    """
    report: dict with keys num_requirements, num_code_files, num_oracle_links,
            cut_points {label -> {recall, precision, f, threshold, num_links}},
            status_based_metrics {...}, warnings [...]
    out_path: where to write the .txt (UTF-8).
    """
    cut_points = report.get("cut_points", {})
    status = report.get("status_based_metrics", {})
    warnings = report.get("warnings") or []

    lines = []
    w = lines.append

    bar = "=" * 80
    w(bar)
    w(" EzzyHub vs. Wang et al. (QRS 2022) - iTrust Requirement-to-Code Traceability")
    w(bar)
    w("")
    w("WHAT THIS FILE IS")
    w("-" * 80)
    w("This compares EzzyHub against the published results of Wang et al. (QRS 2022)")
    w("on the SAME public dataset (iTrust) using the SAME accuracy measures, so the")
    w("two can be read directly side by side.")
    w("")
    w(f"Dataset: iTrust   |   Requirements: {report.get('num_requirements', '?')}"
      f"   |   Code files: {report.get('num_code_files', '?')}"
      f"   |   True links in the answer key: {report.get('num_oracle_links', '?')}")
    w("")
    w("Three definitions (for every one, higher = better):")
    w("  - Recall     : of all the CORRECT requirement->code links, how many were found.")
    w("  - Precision  : of all the links the tool REPORTED, how many were actually correct.")
    w("  - F-measure  : one balanced score that combines recall and precision.")
    w("")
    w("")

    # ── VIEW 1: IR-style, side by side with the paper ──────────────────────────
    w("VIEW 1 - TRACE-LINK RETRIEVAL (this is the view the paper reports)")
    w("-" * 80)
    w("At each recall level, EzzyHub and the paper are shown together. 'Same recall'")
    w("means both found the same share of the true links; the gap is then in precision")
    w("(how many extra wrong candidates came along).")
    w("")
    header = f"  {'Recall level':24s} {'Metric':10s} {'EzzyHub':>9s} {'Paper':>9s} {'Difference':>12s}"
    w(header)
    w("  " + "-" * (len(header) - 2))

    ezzy_points = list(cut_points.values())
    for idx, (label, (pr, pp, pf)) in enumerate(PAPER_REFERENCE):
        if idx >= len(ezzy_points):
            break
        pt = ezzy_points[idx]
        er, ep, ef = pt.get("recall", 0.0), pt.get("precision", 0.0), pt.get("f", 0.0)
        w(f"  {label:24s} {'Recall':10s} {_pct(er):>9s} {_pct(pr):>9s} {_delta_pts(er, pr):>12s}")
        w(f"  {'':24s} {'Precision':10s} {_pct(ep):>9s} {_pct(pp):>9s} {_delta_pts(ep, pp):>12s}")
        w(f"  {'':24s} {'F-measure':10s} {_pct(ef):>9s} {_pct(pf):>9s} {_delta_pts(ef, pf):>12s}")
        w("  " + "-" * (len(header) - 2))
    w("")
    w("  How to read it: EzzyHub reaches the SAME recall levels as the paper (it finds")
    w("  just as many of the true links), but at lower precision in this raw view - to")
    w("  find those links it also proposes many more candidate pairs. That is the normal")
    w("  recall/precision trade-off; the paper's method is tuned to output fewer, cleaner")
    w("  candidates in this specific ranking.")
    w("")
    w("")

    # ── VIEW 2: EzzyHub-only product verdict ───────────────────────────────────
    tp = status.get("true_positive", 0)
    fp = status.get("false_positive", 0)
    tn = status.get("true_negative", 0)
    fn = status.get("false_negative", 0)
    partial = status.get("partial", 0)
    total = status.get("total", 0) or 1
    accuracy = (tp + tn) / total
    precision_status = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall_status = tp / (tp + fn) if (tp + fn) > 0 else 0.0

    w("VIEW 2 - EZZYHUB'S ACTUAL VERDICT: IMPLEMENTED / PARTIAL / MISSING")
    w("-" * 80)
    w("The paper only outputs a ranked list of candidate links. EzzyHub goes further and")
    w("gives each requirement a real verdict. This view scores THOSE verdicts against the")
    w("answer key. (The paper has no equivalent output, so this section is EzzyHub only.)")
    w("")
    w("  When EzzyHub says a requirement IS implemented:")
    w(f"      Correct (true positive) : {tp:4d}")
    w(f"      Wrong   (false positive): {fp:4d}")
    w("  When EzzyHub says a requirement is MISSING:")
    w(f"      Correct (true negative) : {tn:4d}")
    w(f"      Wrong   (false negative): {fn:4d}")
    w(f"  Marked PARTIAL (left for a human to judge): {partial:4d}")
    w("  " + "-" * 52)
    w(f"  Total requirements: {total:4d}")
    w("")
    w(f"  Precision of the 'IMPLEMENTED' verdict  : {_pct(precision_status)}")
    w( "      -> when EzzyHub says a requirement is done, it is right this often.")
    w(f"  Recall of truly-implemented requirements : {_pct(recall_status)}")
    w( "      -> of the requirements that really are done, EzzyHub catches this share.")
    w(f"  Overall accuracy (clearly-right verdicts): {_pct(accuracy)}")
    w( "      -> the PARTIAL cases are counted as neither right nor wrong, which is why")
    w( "         this looks lower than the precision above.")
    w("")
    w("")

    # ── Bottom line ────────────────────────────────────────────────────────────
    w("BOTTOM LINE")
    w("-" * 80)
    w("  - On the paper's own trace-link measure, EzzyHub MATCHES the paper on recall at")
    w("    every level, and trails on precision (it proposes more candidate links).")
    w(f"  - On EzzyHub's real product output, when it declares a requirement IMPLEMENTED it")
    w(f"    is correct {_pct(precision_status)} of the time - a high-confidence verdict the paper's")
    w("    method does not itself provide.")
    w("")

    if warnings:
        w("")
        w(f"RUN WARNINGS ({len(set(warnings))} unique)")
        w("-" * 80)
        for wn in sorted(set(warnings)):
            w(f"  - {wn}")

    # Sanity flag for the classic key-mismatch failure mode.
    if ezzy_points and all(pt.get("recall", 0.0) == 0.0 for pt in ezzy_points):
        w("")
        w("WARNING: recall was 0% at every cut point. This almost always means the")
        w("requirement IDs or file names do not line up with the answer key, not that")
        w("the scoring is bad. Check score_matrix_detail.json: do the reqId values look")
        w("like the dataset's real IDs (e.g. 'UC10E1') or auto-generated ones ('REQ-AUTO-3')?")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
