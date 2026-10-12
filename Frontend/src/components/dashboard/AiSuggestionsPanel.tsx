import { useMemo, useRef, useState } from "react";
import {
  Sparkles,
  Upload,
  Loader2,
  FileArchive,
  FileJson,
  AlertCircle,
  Download,
  CheckCircle2,
} from "lucide-react";
import {
  generateFromMatch,
  downloadGeneratedProject,
  type GenerateFromMatchResult,
} from "@/lib/aiSuggestions";
import { StepProgress } from "@/components/dashboard/StepProgress";

const STEPS = [
  "Uploading report + project ZIP",
  "Extracting project files",
  "Generating code for each requirement",
  "Saving generated versions",
];

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

const STATUS_STYLES: Record<string, string> = {
  PARTIAL: "text-[var(--gold)] bg-[var(--gold)]/10 border-[var(--gold)]/25",
  NOT_IMPLEMENTED: "text-red-400 bg-red-500/10 border-red-500/25",
  MISSING: "text-red-400 bg-red-500/10 border-red-500/25",
};

export function AiSuggestionsPanel() {
  const reportInputRef = useRef<HTMLInputElement>(null);
  const zipInputRef = useRef<HTMLInputElement>(null);
  const [reportFile, setReportFile] = useState<File | null>(null);
  const [projectZip, setProjectZip] = useState<File | null>(null);
  const [running, setRunning] = useState(false);
  const [step, setStep] = useState(0);
  const [result, setResult] = useState<GenerateFromMatchResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const pickReport = (f: File | undefined | null) => {
    if (!f) return;
    const name = f.name.toLowerCase();
    if (!name.endsWith(".json") && !name.endsWith(".pdf")) {
      setError("The match report must be a JSON or PDF file.");
      return;
    }
    setReportFile(f);
    setError(null);
  };

  const pickZip = (f: File | undefined | null) => {
    if (!f) return;
    if (!f.name.toLowerCase().endsWith(".zip")) {
      setError("Only ZIP files are supported for the project archive.");
      return;
    }
    setProjectZip(f);
    setError(null);
  };

  const handleRun = async () => {
    if (!reportFile) {
      setError("Upload the matching-service report (JSON or PDF) first.");
      return;
    }
    if (!projectZip) {
      setError("Select the project ZIP.");
      return;
    }
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      setStep(1);
      await wait(150);
      setStep(2);
      await wait(150);
      setStep(3); // stays active for however long generation actually takes
      const res = await generateFromMatch(reportFile, projectZip);
      setStep(4);
      await wait(150);
      setResult(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : "AI suggestion generation failed.");
    } finally {
      setRunning(false);
      setStep(0);
    }
  };

  const handleDownload = async () => {
    if (!result) return;
    setDownloading(true);
    try {
      await downloadGeneratedProject(result.batchId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not download the generated project.");
    } finally {
      setDownloading(false);
    }
  };

  const versions = useMemo(() => result?.versions ?? [], [result]);
  const reportSizeMb = reportFile ? (reportFile.size / (1024 * 1024)).toFixed(2) : null;
  const zipSizeMb = projectZip ? (projectZip.size / (1024 * 1024)).toFixed(1) : null;

  return (
    <div>
      <div className="flex items-center gap-3 mb-1">
        <h1 className="text-3xl font-display font-medium">AI Suggestions</h1>
        <span className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full bg-[var(--gold)]/10 border border-[var(--gold)]/25 text-[var(--gold)]">
          <span className="w-1.5 h-1.5 rounded-full bg-[var(--gold)]" />
          OpenAI-powered generation
        </span>
      </div>
      <p className="text-sm text-muted-foreground mb-8">
        Feed in a matching report and the project it was run against — get AI-generated code for
        every partial or missing requirement, ready to review and download.
      </p>

      <div className="grid lg:grid-cols-2 gap-6 mb-6">
        {/* report */}
        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <h3 className="font-medium mb-1">Match Report</h3>
          <p className="text-sm text-muted-foreground mb-3">
            The JSON or PDF report produced by Req ↔ Code Matching.
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
              pickReport(e.dataTransfer.files?.[0]);
            }}
            onClick={() => reportInputRef.current?.click()}
            className={
              "rounded-2xl border-2 border-dashed px-6 py-10 text-center cursor-pointer transition-colors " +
              (dragOver
                ? "border-[var(--gold)] bg-[var(--gold)]/5"
                : "border-[var(--gold)]/20 hover:border-[var(--gold)]/40")
            }
          >
            <input
              ref={reportInputRef}
              type="file"
              accept=".json,.pdf"
              className="hidden"
              onChange={(e) => pickReport(e.target.files?.[0])}
            />
            <Upload className="w-6 h-6 mx-auto mb-3 text-muted-foreground" strokeWidth={1.6} />
            <p className="text-sm text-foreground/85">
              {reportFile ? reportFile.name : "Drop the match report JSON or PDF here, or click to browse"}
            </p>
          </div>

          {reportFile && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-[var(--gold)]/15 bg-background/40 px-4 py-3">
              <FileJson className="w-4 h-4 text-[var(--gold)] shrink-0" />
              <span className="text-sm text-foreground/85 truncate">{reportFile.name}</span>
              <span className="text-xs text-muted-foreground ml-auto shrink-0">
                {reportSizeMb} MB
              </span>
            </div>
          )}
        </div>

        {/* project zip */}
        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <h3 className="font-medium mb-1">Project ZIP</h3>
          <p className="text-sm text-muted-foreground mb-3">
            The same project the report was generated against.
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
              pickZip(e.dataTransfer.files?.[0]);
            }}
            onClick={() => zipInputRef.current?.click()}
            className={
              "rounded-2xl border-2 border-dashed px-6 py-10 text-center cursor-pointer transition-colors " +
              (dragOver
                ? "border-[var(--gold)] bg-[var(--gold)]/5"
                : "border-[var(--gold)]/20 hover:border-[var(--gold)]/40")
            }
          >
            <input
              ref={zipInputRef}
              type="file"
              accept=".zip"
              className="hidden"
              onChange={(e) => pickZip(e.target.files?.[0])}
            />
            <Upload className="w-6 h-6 mx-auto mb-3 text-muted-foreground" strokeWidth={1.6} />
            <p className="text-sm text-foreground/85">
              {projectZip ? projectZip.name : "Drop your project ZIP here, or click to browse"}
            </p>
          </div>

          {projectZip && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-[var(--gold)]/15 bg-background/40 px-4 py-3">
              <FileArchive className="w-4 h-4 text-[var(--gold)] shrink-0" />
              <span className="text-sm text-foreground/85 truncate">{projectZip.name}</span>
              <span className="text-xs text-muted-foreground ml-auto shrink-0">{zipSizeMb} MB</span>
            </div>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-6 flex items-start gap-2 rounded-xl border border-destructive/20 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
          {error}
        </div>
      )}

      <button
        onClick={handleRun}
        disabled={!reportFile || !projectZip || running}
        className="w-full mb-4 bg-[var(--gold)] text-primary-foreground font-medium py-3 rounded-xl hover:shadow-[0_0_25px_rgba(245,191,49,0.4)] transition-shadow flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed"
      >
        {running ? (
          <>
            <Loader2 className="w-4 h-4 animate-spin" /> Generating...
          </>
        ) : (
          <>
            <Sparkles className="w-4 h-4" /> Generate AI Suggestions
          </>
        )}
      </button>

      {running && (
        <StepProgress
          steps={STEPS}
          current={step}
          title="Generating code for each requirement..."
          subtitle="This may take a few minutes — one AI call runs per requirement."
        />
      )}

      {result && (
        <div className="animate-fade-up rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
            <div>
              <h3 className="font-medium">Generated Versions</h3>
              <p className="text-xs text-muted-foreground mt-1">
                Batch #{result.batchId} · {versions.length} requirement
                {versions.length === 1 ? "" : "s"} processed
              </p>
            </div>
            <button
              onClick={handleDownload}
              disabled={downloading}
              className="inline-flex items-center gap-1.5 text-sm border border-border rounded-lg px-3 py-2 text-foreground/80 hover:text-[var(--gold)] hover:border-[var(--gold)]/40 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {downloading ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Download className="w-3.5 h-3.5" />
              )}
              Download generated project
            </button>
          </div>

          {versions.length === 0 ? (
            <p className="text-sm text-muted-foreground py-4 text-center">
              No requirements needed generated code.
            </p>
          ) : (
            <div className="space-y-2 max-h-[420px] overflow-y-auto">
              {versions.map((v) => (
                <div
                  key={v.id}
                  className="flex items-center gap-3 rounded-xl border border-[var(--gold)]/10 bg-background/30 px-4 py-3"
                >
                  <span className="text-xs text-muted-foreground font-mono shrink-0">
                    v{v.versionNumber}
                  </span>
                  <span
                    className={`text-[10px] px-2 py-1 rounded-full border shrink-0 ${
                      STATUS_STYLES[v.requirementStatus ?? ""] ??
                      "text-muted-foreground bg-muted/40 border-border"
                    }`}
                  >
                    {v.requirementStatus ?? "UNKNOWN"}
                  </span>
                  <span className="text-sm text-foreground/85 truncate">
                    {v.userPrompt ?? "Generated from Match Report"}
                  </span>
                  {typeof v.similarityScore === "number" && (
                    <span className="text-xs text-muted-foreground ml-auto shrink-0 font-mono">
                      {Math.round(v.similarityScore * 100)}% match
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}

          <div className="mt-5 flex items-center gap-2 text-sm text-muted-foreground">
            <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
            Review every generated file before merging it into your project.
          </div>
        </div>
      )}
    </div>
  );
}
