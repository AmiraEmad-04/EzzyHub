import { useMemo, useRef, useState } from "react";
import {
  GitCompareArrows,
  Upload,
  Loader2,
  FileArchive,
  AlertCircle,
  Search,
  Download,
  CheckCircle2,
} from "lucide-react";
import {
  runMatching,
  describeTopChunk,
  type MatchReport,
  type RequirementMatchResult,
} from "@/lib/matching";
import { saveHistoryEntry } from "@/lib/history";
import { StepProgress } from "@/components/dashboard/StepProgress";

const STEPS = [
  "Uploading ZIP file",
  "Chunking code (AST)",
  "Embedding requirements (SBERT)",
  "Computing similarity scores",
  "Classifying results",
];

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

const SAMPLE_REQUIREMENTS = `FR-1.1.1 Allow a new user to create an account using username/email and password.
FR-1.2.1 Allow registered users to log in using valid credentials.
FR-1.2.2 Deny access if credentials are incorrect.
FR-2.1.1 Allow users to import source code by uploading a ZIP file.
FR-2.2.1 Allow users to upload ZIP files.
FR-7.1.2 System calculates a similarity score using Jaccard similarity algorithm.
FR-7.2.1 System classifies requirements as IMPLEMENTED, PARTIAL, or MISSING.
FR-9.1.1 Perform static analysis on AI-generated code to verify syntactic correctness.`;

const STATUS_STYLES: Record<string, string> = {
  IMPLEMENTED: "text-green-400 bg-green-500/10 border-green-500/25",
  PARTIAL: "text-[var(--gold)] bg-[var(--gold)]/10 border-[var(--gold)]/25",
  MISSING: "text-red-400 bg-red-500/10 border-red-500/25",
};

// The score badge's color must match the status badge's color for the same
// row. It used to be computed independently from a fixed percentage cutoff
// (>=80 green, >=40 gold, else red) — but the backend doesn't classify by a
// flat score cutoff; it also weighs how far the top match stands out from
// that requirement's own noise floor (see RequirementMatchResult.standoutMargin
// in the backend). So a 44% score can legitimately be IMPLEMENTED while a
// 55% one is PARTIAL, and the two badges disagreeing looked like a bug.
// Deriving color from `status` instead keeps both badges in sync always.
function scoreClass(status: string): string {
  return STATUS_STYLES[status] ?? "text-muted-foreground bg-muted/40 border-border";
}

// Displayed confidence bands, one per status. The backend's verdict (status) is
// the source of truth: after scoring it runs a reality-check step that can
// downgrade a status (e.g. a high-similarity match to code with a known
// limitation) WITHOUT lowering the raw similarity number. Showing that raw
// number next to the downgraded label read as a contradiction ("55% but
// PARTIAL"). Per product decision, the number shown here is mapped into a band
// that always agrees with the label, so the two never contradict. The exact raw
// similarity is still shown on hover, and the exported report keeps the raw
// numbers untouched (so AI-Suggestions and re-imports are unaffected).
const CONFIDENCE_BANDS: Record<string, [number, number]> = {
  IMPLEMENTED: [75, 99],
  PARTIAL: [45, 70],
  MISSING: [5, 40],
};

function confidenceForDisplay(status: string, rawScore: number): number {
  const [low, high] = CONFIDENCE_BANDS[status] ?? [5, 40];
  // Position within the band, monotonic in the raw score (typical raw range ~0..0.6),
  // so higher-scoring rows still rank above lower ones inside the same status.
  const ratio = Math.max(0, Math.min(1, rawScore / 0.6));
  return Math.round(low + ratio * (high - low));
}

export function MatchingPanel() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [reqText, setReqText] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [running, setRunning] = useState(false);
  const [step, setStep] = useState(0);
  const [report, setReport] = useState<MatchReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [query, setQuery] = useState("");
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [statusFilter, setStatusFilter] = useState<"all" | string>("all");

  const lines = useMemo(
    () =>
      reqText
        .split("\n")
        .map((l) => l.trim())
        .filter(Boolean),
    [reqText],
  );

  const pickFile = (f: File | undefined | null) => {
    if (!f) return;
    setFile(f);
    setError(null);
  };

  const handleRun = async () => {
    if (lines.length === 0) {
      setError("Enter at least one requirement.");
      return;
    }
    if (lines.length > 100) {
      setError("Maximum 100 requirements.");
      return;
    }
    if (!file) {
      setError("Select a project ZIP first.");
      return;
    }
    setRunning(true);
    setError(null);
    setReport(null);
    setSaveState("idle");
    try {
      setStep(1);
      await wait(150);
      setStep(2);
      await wait(150);
      setStep(3); // stays active for however long the real pipeline actually takes
      const res = await runMatching(file, lines);
      setStep(4);
      await wait(150);
      setStep(5);
      await wait(150);
      setReport(res);

      setSaveState("saving");
      try {
        await saveHistoryEntry({
          projectName: file.name.replace(/\.zip$/i, ""),
          analysisType: "REQUIREMENT_MATCH",
          status:
            res.missingCount === 0 ? "success" : res.implementedCount > 0 ? "partial" : "error",
          totalRequirements: res.totalRequirements,
          implementedCount: res.implementedCount,
          partialCount: res.partialCount,
          missingCount: res.missingCount,
          coveragePercentage: res.coveragePercent,
          matchResult: res,
        });
        setSaveState("saved");
      } catch {
        setSaveState("error");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Matching failed.");
    } finally {
      setRunning(false);
      setStep(0);
    }
  };

  const results: RequirementMatchResult[] = useMemo(
    () => report?.requirementMatches ?? [],
    [report],
  );

  const filtered = useMemo(() => {
    return results.filter((r) => {
      const matchesQuery = r.reqText?.toLowerCase().includes(query.toLowerCase());
      const matchesStatus = statusFilter === "all" || r.status === statusFilter;
      return matchesQuery && matchesStatus;
    });
  }, [results, query, statusFilter]);

  const exportReport = () => {
    if (!report) return;
    // NOTE: the array key MUST be "requirementMatches" (not "results"). The
    // AI-Suggestions service parses this exported file and reads the
    // "requirementMatches" field (see MatchReportDto on the backend); exporting
    // it as "results" made AI-Suggestions report "No requirements found in report".
    const data = {
      generated: new Date().toISOString(),
      coveragePercent: report.coveragePercent,
      implementedCount: report.implementedCount,
      partialCount: report.partialCount,
      missingCount: report.missingCount,
      requirementMatches: results,
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `ezzyhub-match-report-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const sizeMb = file ? (file.size / (1024 * 1024)).toFixed(1) : null;
  const pct = report ? Math.round(report.coveragePercent) : 0;

  return (
    <div>
      <div className="flex items-center gap-3 mb-1">
        <h1 className="text-3xl font-display font-medium">Req ↔ Code Matching</h1>
        <span className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full bg-[var(--gold)]/10 border border-[var(--gold)]/25 text-[var(--gold)]">
          <span className="w-1.5 h-1.5 rounded-full bg-[var(--gold)]" />
          AST + SBERT pipeline
        </span>
      </div>
      <p className="text-sm text-muted-foreground mb-8">
        Match every requirement straight to the code that implements it, with a confidence score for
        each pairing.
      </p>

      <div className="grid lg:grid-cols-2 gap-6 mb-6">
        {/* requirements */}
        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <div className="flex items-center justify-between mb-1">
            <h3 className="font-medium">Requirements</h3>
            <div className="flex items-center gap-2">
              <span
                className={`text-[11px] px-2 py-0.5 rounded-full border ${
                  lines.length > 100
                    ? "text-red-400 bg-red-500/10 border-red-500/25"
                    : lines.length > 0
                      ? "text-[var(--gold)] bg-[var(--gold)]/10 border-[var(--gold)]/25"
                      : "text-muted-foreground bg-muted/40 border-border"
                }`}
              >
                {lines.length} / 100
              </span>
              <button
                onClick={() => setReqText(SAMPLE_REQUIREMENTS)}
                className="text-[11px] text-[var(--gold)] hover:underline"
              >
                Load samples
              </button>
            </div>
          </div>
          <p className="text-sm text-muted-foreground mb-3">One requirement per line.</p>
          <textarea
            value={reqText}
            onChange={(e) => setReqText(e.target.value)}
            placeholder="FR-1.1.1 Allow a new user to create an account..."
            rows={10}
            className="w-full bg-background/60 border border-border rounded-xl px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors font-mono resize-none"
          />
          {lines.length > 100 && (
            <p className="text-xs text-destructive mt-2">Maximum 100 requirements allowed.</p>
          )}
        </div>

        {/* upload */}
        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <h3 className="font-medium mb-1">Project ZIP</h3>
          <p className="text-sm text-muted-foreground mb-3">
            Java projects work best — the chunker is AST-based.
          </p>

          <div
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragOver(false);
              pickFile(e.dataTransfer.files?.[0]);
            }}
            onClick={() => inputRef.current?.click()}
            className={
              "rounded-2xl border-2 border-dashed px-6 py-10 text-center cursor-pointer transition-colors " +
              (dragOver
                ? "border-[var(--gold)] bg-[var(--gold)]/5"
                : "border-[var(--gold)]/20 hover:border-[var(--gold)]/40")
            }
          >
            <input
              ref={inputRef}
              type="file"
              accept=".zip"
              className="hidden"
              onChange={(e) => pickFile(e.target.files?.[0])}
            />
            <Upload className="w-6 h-6 mx-auto mb-3 text-muted-foreground" strokeWidth={1.6} />
            <p className="text-sm text-foreground/85">
              {file ? file.name : "Drop your project ZIP here, or click to browse"}
            </p>
          </div>

          {file && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-[var(--gold)]/15 bg-background/40 px-4 py-3">
              <FileArchive className="w-4 h-4 text-[var(--gold)] shrink-0" />
              <span className="text-sm text-foreground/85 truncate">{file.name}</span>
              <span className="text-xs text-muted-foreground ml-auto shrink-0">{sizeMb} MB</span>
            </div>
          )}

          {error && (
            <div className="mt-4 flex items-start gap-2 rounded-xl border border-destructive/20 bg-destructive/10 px-4 py-3 text-sm text-destructive">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              {error}
            </div>
          )}

          <button
            onClick={handleRun}
            disabled={running}
            className="w-full mt-4 bg-[var(--gold)] text-primary-foreground font-medium py-3 rounded-xl hover:shadow-[0_0_25px_rgba(245,191,49,0.4)] transition-shadow flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {running ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" /> Running...
              </>
            ) : (
              <>
                <GitCompareArrows className="w-4 h-4" /> Run Matching
              </>
            )}
          </button>

          {running && (
            <StepProgress
              steps={STEPS}
              current={step}
              title="Running the matching pipeline..."
              subtitle="This may take a few minutes depending on project size."
            />
          )}
        </div>
      </div>

      {report && (
        <div className="animate-fade-up">
          <div className="grid md:grid-cols-[220px_1fr] gap-4 mb-6">
            <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6 flex flex-col items-center justify-center text-center">
              <p className="text-4xl font-medium text-[var(--gold)]">{pct}%</p>
              <p className="text-xs text-muted-foreground uppercase tracking-wide mt-1">Coverage</p>
            </div>
            <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6 grid grid-cols-3 gap-4">
              <div>
                <p className="text-2xl font-medium text-green-400">{report.implementedCount}</p>
                <p className="text-xs text-muted-foreground mt-1">Implemented</p>
              </div>
              <div>
                <p className="text-2xl font-medium text-[var(--gold)]">{report.partialCount}</p>
                <p className="text-xs text-muted-foreground mt-1">Partial</p>
              </div>
              <div>
                <p className="text-2xl font-medium text-red-400">{report.missingCount}</p>
                <p className="text-xs text-muted-foreground mt-1">Missing</p>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 text-sm mb-4">
            {saveState === "saving" && (
              <span className="flex items-center gap-1.5 text-muted-foreground">
                <Loader2 className="w-3.5 h-3.5 animate-spin" /> Saving to history...
              </span>
            )}
            {saveState === "saved" && (
              <span className="flex items-center gap-1.5 text-green-400">
                <CheckCircle2 className="w-3.5 h-3.5" /> Saved to History
              </span>
            )}
            {saveState === "error" && (
              <span className="flex items-center gap-1.5 text-destructive">
                <AlertCircle className="w-3.5 h-3.5" /> Report complete, but saving to History
                failed.
              </span>
            )}
          </div>

          <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
            <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
              <h3 className="font-medium">Match Results</h3>
              <div className="flex items-center gap-2">
                <div className="relative">
                  <Search className="absolute left-3 top-2.5 w-3.5 h-3.5 text-muted-foreground" />
                  <input
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Search requirements..."
                    className="w-48 bg-background/60 border border-border rounded-lg pl-8 pr-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
                  />
                </div>
                <select
                  value={statusFilter}
                  onChange={(e) => setStatusFilter(e.target.value)}
                  className="bg-background/60 border border-border rounded-lg px-3 py-2 text-sm text-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
                >
                  <option value="all">All statuses</option>
                  <option value="IMPLEMENTED">Implemented</option>
                  <option value="PARTIAL">Partial</option>
                  <option value="MISSING">Missing</option>
                </select>
                <button
                  onClick={exportReport}
                  className="inline-flex items-center gap-1.5 text-sm border border-border rounded-lg px-3 py-2 text-foreground/80 hover:text-[var(--gold)] hover:border-[var(--gold)]/40 transition-colors"
                >
                  <Download className="w-3.5 h-3.5" />
                  Export
                </button>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-muted-foreground text-xs uppercase tracking-wide">
                    <th className="pb-3 pr-4 font-medium">Requirement</th>
                    <th className="pb-3 pr-4 font-medium text-center">Score</th>
                    <th className="pb-3 pr-4 font-medium text-center">Status</th>
                    <th className="pb-3 font-medium">Matched Code</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--gold)]/10">
                  {filtered.map((r, i) => {
                    const scorePct = confidenceForDisplay(r.status, r.score);
                    const rawPct = Math.round(r.score * 100);
                    return (
                      <tr key={`${r.reqId ?? i}`}>
                        <td className="py-3 pr-4 text-foreground/90 max-w-md">
                          <span className="text-xs text-muted-foreground font-mono mr-2">
                            {r.reqId ?? `#${i + 1}`}
                          </span>
                          {r.reqText}
                        </td>
                        <td className="py-3 pr-4 text-center">
                          <span
                            title={`Raw similarity: ${rawPct}%`}
                            className={`inline-flex text-xs px-2 py-1 rounded-full border ${scoreClass(r.status)}`}
                          >
                            {scorePct}%
                          </span>
                        </td>
                        <td className="py-3 pr-4 text-center">
                          <span
                            className={`inline-flex text-xs px-2.5 py-1 rounded-full border ${
                              STATUS_STYLES[r.status] ??
                              "text-muted-foreground bg-muted/40 border-border"
                            }`}
                          >
                            {r.status}
                          </span>
                        </td>
                        <td className="py-3 text-muted-foreground font-mono text-xs">
                          {describeTopChunk(r)}
                        </td>
                      </tr>
                    );
                  })}
                  {filtered.length === 0 && (
                    <tr>
                      <td colSpan={4} className="py-8 text-center text-muted-foreground">
                        No requirements match that search.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {report.warnings && report.warnings.length > 0 && (
              <div className="mt-5 space-y-1.5">
                {report.warnings.map((w, i) => (
                  <div key={i} className="flex items-start gap-2 text-xs text-muted-foreground">
                    <CheckCircle2 className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                    {w}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
