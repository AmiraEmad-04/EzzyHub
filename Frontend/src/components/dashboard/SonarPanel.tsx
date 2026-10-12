import { useMemo, useRef, useState } from "react";
import {
  Radar,
  Upload,
  Loader2,
  FileArchive,
  ShieldCheck,
  Bug,
  ShieldAlert,
  Sparkles as SmellIcon,
  Copy,
  Percent,
  AlertCircle,
  CheckCircle2,
} from "lucide-react";
import { runSonarAnalysis, type AnalysisReport, type SonarIssue } from "@/lib/sonar";
import { saveHistoryEntry } from "@/lib/history";
import { StepProgress } from "@/components/dashboard/StepProgress";

const STEPS = [
  "Uploading ZIP file",
  "Extracting project",
  "Running sonar-scanner",
  "Fetching analysis results",
  "Building report",
];

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

const SEVERITY_STYLES: Record<string, string> = {
  BLOCKER: "text-red-400 bg-red-500/10 border-red-500/25",
  CRITICAL: "text-orange-400 bg-orange-500/10 border-orange-500/25",
  MAJOR: "text-[var(--copper)] bg-[var(--copper)]/10 border-[var(--copper)]/25",
  MINOR: "text-muted-foreground bg-muted/40 border-border",
  INFO: "text-muted-foreground bg-muted/40 border-border",
};

const QUALITY_GATE_STYLES: Record<string, string> = {
  OK: "text-green-400 bg-green-500/10 border-green-500/25",
  ERROR: "text-red-400 bg-red-500/10 border-red-500/25",
  WARN: "text-[var(--gold)] bg-[var(--gold)]/10 border-[var(--gold)]/25",
};

const ISSUE_TYPES = ["ALL", "BUG", "VULNERABILITY", "CODE_SMELL"] as const;

export function SonarPanel() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [projectName, setProjectName] = useState("");
  const [running, setRunning] = useState(false);
  const [step, setStep] = useState(0);
  const [report, setReport] = useState<AnalysisReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [typeFilter, setTypeFilter] = useState<(typeof ISSUE_TYPES)[number]>("ALL");
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [elapsed, setElapsed] = useState(0);

  const pickFile = (f: File | undefined | null) => {
    if (!f) return;
    if (!f.name.toLowerCase().endsWith(".zip")) {
      setError("Only ZIP files are supported.");
      return;
    }
    setFile(f);
    setReport(null);
    setError(null);
  };

  const handleRun = async () => {
    if (!file) return;
    setRunning(true);
    setError(null);
    setReport(null);
    setSaveState("idle");
    setElapsed(0);
    try {
      setStep(1);
      await wait(150);
      setStep(2);
      await wait(150);
      setStep(3); // stays active while we poll the async job for its result
      const res = await runSonarAnalysis(file, projectName || undefined, setElapsed);
      setStep(4);
      await wait(150);
      setStep(5);
      await wait(150);
      setReport(res);

      setSaveState("saving");
      try {
        const qg = (res.qualityGateStatus ?? "").toUpperCase();
        await saveHistoryEntry({
          projectName: res.projectName || file.name.replace(/\.zip$/i, ""),
          analysisType: "SONAR",
          status: qg === "OK" ? "success" : qg === "ERROR" ? "error" : "partial",
          sonarResult: res,
        });
        setSaveState("saved");
      } catch {
        setSaveState("error");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sonar analysis failed.");
    } finally {
      setRunning(false);
      setStep(0);
    }
  };

  const allIssues: SonarIssue[] = useMemo(() => {
    if (!report) return [];
    if (report.allIssues) return report.allIssues;
    return Object.values(report.issuesByFile ?? {}).flat();
  }, [report]);

  const filteredIssues = useMemo(
    () => (typeFilter === "ALL" ? allIssues : allIssues.filter((i) => i.type === typeFilter)),
    [allIssues, typeFilter],
  );

  const severities = useMemo(() => {
    if (!report) return [];
    return [
      { key: "BLOCKER", count: report.blocker },
      { key: "CRITICAL", count: report.critical },
      { key: "MAJOR", count: report.major },
      { key: "MINOR", count: report.minor },
      { key: "INFO", count: report.info },
    ].filter((s) => s.count > 0);
  }, [report]);

  const sizeMb = file ? (file.size / (1024 * 1024)).toFixed(1) : null;

  const metrics = report
    ? [
        { label: "Bugs", value: report.bugs, icon: Bug, color: "text-red-400" },
        {
          label: "Vulnerabilities",
          value: report.vulnerabilities,
          icon: ShieldAlert,
          color: "text-orange-400",
        },
        {
          label: "Code Smells",
          value: report.codeSmells,
          icon: SmellIcon,
          color: "text-[var(--copper)]",
        },
        {
          label: "Security Hotspots",
          value: report.securityHotspots,
          icon: ShieldAlert,
          color: "text-[var(--gold)]",
        },
        {
          label: "Coverage",
          value: report.coverage ?? "—",
          icon: Percent,
          color: "text-green-400",
        },
        {
          label: "Duplications",
          value: report.duplications ?? "—",
          icon: Copy,
          color: "text-foreground",
        },
      ]
    : [];

  const qgStatus = (report?.qualityGateStatus ?? "UNKNOWN").toUpperCase();

  return (
    <div>
      <div className="flex items-center gap-3 mb-1">
        <h1 className="text-3xl font-display font-medium">Sonar Analyzer</h1>
        <span className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full bg-[var(--gold)]/10 border border-[var(--gold)]/25 text-[var(--gold)]">
          <span className="w-1.5 h-1.5 rounded-full bg-[var(--gold)]" />
          Static analysis engine
        </span>
      </div>
      <p className="text-sm text-muted-foreground mb-8">
        Bugs, vulnerabilities, code smells, and security hotspots — checked automatically alongside
        the gap scan.
      </p>

      <div className="grid lg:grid-cols-[1fr_300px] gap-6">
        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <h3 className="font-medium mb-1">Run Static Analysis</h3>
          <p className="text-sm text-muted-foreground mb-5">
            Upload the same project ZIP, or point this at the codebase you just ran through Code
            Analyzer.
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
              "rounded-2xl border-2 border-dashed px-6 py-12 text-center cursor-pointer transition-colors " +
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
            <p className="text-xs text-muted-foreground mt-1">
              Same engine, any language — Java, Python, TypeScript, Go, and more
            </p>
          </div>

          {file && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-[var(--gold)]/15 bg-background/40 px-4 py-3">
              <FileArchive className="w-4 h-4 text-[var(--gold)] shrink-0" />
              <span className="text-sm text-foreground/85 truncate">{file.name}</span>
              <span className="text-xs text-muted-foreground ml-auto shrink-0">{sizeMb} MB</span>
            </div>
          )}

          <div className="mt-4">
            <label className="block text-xs font-medium uppercase tracking-wider text-muted-foreground mb-1.5">
              Project name (optional)
            </label>
            <input
              value={projectName}
              onChange={(e) => setProjectName(e.target.value)}
              placeholder="my-project"
              className="w-full bg-background/60 border border-border rounded-xl px-4 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
            />
          </div>

          {error && (
            <div className="mt-4 flex items-start gap-2 rounded-xl border border-destructive/20 bg-destructive/10 px-4 py-3 text-sm text-destructive">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              {error}
            </div>
          )}

          <button
            onClick={handleRun}
            disabled={!file || running}
            className="w-full mt-4 bg-[var(--gold)] text-primary-foreground font-medium py-3 rounded-xl hover:shadow-[0_0_25px_rgba(245,191,49,0.4)] transition-shadow flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {running ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" /> Scanning...
              </>
            ) : (
              <>
                <Radar className="w-4 h-4" /> Run Static Analysis
              </>
            )}
          </button>

          {running && (
            <StepProgress
              steps={STEPS}
              current={step}
              title="Analyzing your project..."
              subtitle={
                elapsed > 0
                  ? `Analyzing… ${elapsed}s elapsed — this may take 1–3 minutes depending on project size.`
                  : "This may take 1–3 minutes depending on project size."
              }
            />
          )}

          {report && (
            <div className="mt-8 animate-fade-up space-y-6">
              <div className="flex items-center gap-3 flex-wrap">
                <span
                  className={`inline-flex items-center gap-2 text-sm font-medium px-3 py-1.5 rounded-full border ${
                    QUALITY_GATE_STYLES[qgStatus] ??
                    "text-muted-foreground bg-muted/40 border-border"
                  }`}
                >
                  <ShieldCheck className="w-4 h-4" />
                  Quality Gate: {qgStatus}
                </span>
                {report.projectName && (
                  <span className="text-xs text-muted-foreground">{report.projectName}</span>
                )}
                {saveState === "saving" && (
                  <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
                    <Loader2 className="w-3.5 h-3.5 animate-spin" /> Saving to history...
                  </span>
                )}
                {saveState === "saved" && (
                  <span className="flex items-center gap-1.5 text-sm text-green-400">
                    <CheckCircle2 className="w-3.5 h-3.5" /> Saved to History
                  </span>
                )}
                {saveState === "error" && (
                  <span className="flex items-center gap-1.5 text-sm text-destructive">
                    <AlertCircle className="w-3.5 h-3.5" /> Report complete, but saving to History
                    failed.
                  </span>
                )}
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                {metrics.map((m) => (
                  <div
                    key={m.label}
                    className="rounded-xl border border-[var(--gold)]/15 bg-background/40 p-4"
                  >
                    <m.icon className={`w-4 h-4 mb-2 ${m.color}`} strokeWidth={1.8} />
                    <p className="text-[11px] text-muted-foreground mb-1">{m.label}</p>
                    <p className={`text-lg font-medium ${m.color}`}>{m.value}</p>
                  </div>
                ))}
              </div>

              {severities.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {severities.map((s) => (
                    <span
                      key={s.key}
                      className={`text-xs px-2.5 py-1 rounded-full border ${SEVERITY_STYLES[s.key]}`}
                    >
                      {s.key} <strong>{s.count}</strong>
                    </span>
                  ))}
                </div>
              )}

              <div>
                <div className="flex items-center justify-between mb-3">
                  <h4 className="text-sm font-medium">Issues</h4>
                  <div className="flex gap-1.5">
                    {ISSUE_TYPES.map((t) => (
                      <button
                        key={t}
                        onClick={() => setTypeFilter(t)}
                        className={`text-[11px] px-2.5 py-1 rounded-full border transition-colors ${
                          typeFilter === t
                            ? "bg-[var(--gold)] text-primary-foreground border-[var(--gold)]"
                            : "border-border text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {t.replace("_", " ")}
                      </button>
                    ))}
                  </div>
                </div>
                {filteredIssues.length === 0 ? (
                  <p className="text-sm text-muted-foreground py-4 text-center">
                    No issues of this type.
                  </p>
                ) : (
                  <div className="space-y-2 max-h-[420px] overflow-y-auto">
                    {filteredIssues.map((issue) => (
                      <div
                        key={issue.key}
                        className="flex items-center gap-3 rounded-xl border border-[var(--gold)]/10 bg-background/30 px-4 py-3"
                      >
                        <span
                          className={`text-[10px] px-2 py-1 rounded-full border shrink-0 ${
                            SEVERITY_STYLES[issue.severity] ??
                            "text-muted-foreground bg-muted/40 border-border"
                          }`}
                        >
                          {issue.severity}
                        </span>
                        <span className="text-sm text-foreground/85 truncate">{issue.message}</span>
                        <span className="text-xs text-muted-foreground ml-auto shrink-0 font-mono">
                          {issue.filePath ?? issue.component}
                          {issue.line ? `:${issue.line}` : ""}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-5">
          <h4 className="font-medium mb-3 text-sm">What gets checked</h4>
          <ul className="space-y-2">
            {[
              { icon: Bug, text: "Bugs and reliability issues" },
              { icon: ShieldAlert, text: "Security vulnerabilities & hotspots" },
              { icon: SmellIcon, text: "Code smells & maintainability" },
              { icon: Copy, text: "Duplicated blocks across files" },
              { icon: Percent, text: "Test coverage estimate" },
            ].map((f) => (
              <li key={f.text} className="flex items-start gap-2 text-sm text-foreground/80">
                <f.icon className="w-3.5 h-3.5 text-[var(--gold)] mt-0.5 shrink-0" />
                {f.text}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
