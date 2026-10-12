"""
Re-render the human-readable metrics_report.txt for every results_* folder that
already has a metrics_report.json, WITHOUT re-running the benchmark (which needs
all the live services up). Use this after changing report_format.py.

    python regenerate_reports.py
"""

import glob
import json
import os

from report_format import write_human_readable_report


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    json_files = sorted(glob.glob(os.path.join(here, "results*", "metrics_report.json")))
    if not json_files:
        print("No results*/metrics_report.json files found.")
        return
    for jf in json_files:
        with open(jf, "r", encoding="utf-8") as f:
            report = json.load(f)
        out_txt = os.path.join(os.path.dirname(jf), "metrics_report.txt")
        write_human_readable_report(report, out_txt)
        print(f"regenerated {os.path.relpath(out_txt, here)}")


if __name__ == "__main__":
    main()
