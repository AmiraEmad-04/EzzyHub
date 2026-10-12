import { useRef, useState } from "react";
import { Code2, Upload, Loader2, FileArchive, AlertCircle, CheckCircle2 } from "lucide-react";
import { analyzeCode, type AnalysisResult } from "@/lib/codeAnalyzer";
import { saveHistoryEntry } from "@/lib/history";
import { CodeAnalysisResults } from "@/components/dashboard/CodeAnalysisResults";
import { StepProgress } from "@/components/dashboard/StepProgress";

const WHAT_YOU_GET = [
  "Semantic role of every file",
  "Class and method descriptions",
  "Field-level semantic meaning",
  "Dependency graph",
  "Language & tech stack detection",
  "Parallel LLM analysis (fast)",
];

const STEPS = [
  "Uploading ZIP file",
  "Extracting project",
  "Scanning files & structure",
  "Running AI semantic analysis",
  "Building report",
];

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function CodeAnalyzerPanel() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [step, setStep] = useState(0);
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");

  const pickFile = (f: File | undefined | null) => {
    if (!f) return;
    setFile(f);
    setResult(null);
    setError(null);
    setSaveState("idle");
  };

  const handleAnalyze = async () => {
    if (!file) return;
    setAnalyzing(true);
    setError(null);
    setResult(null);
    setSaveState("idle");
    try {
      setStep(1);
      await wait(150);
      setStep(2);
      await wait(150);
      setStep(3); // stays active for however long the real request actually takes
      const res = await analyzeCode(file);
      setStep(4);
      await wait(150);
      setStep(5);
      await wait(150);
      setResult(res);

      // Persist to report-service so it shows up under History. Running the
      // analysis only produces an on-screen result — this is the step that
      // actually saves it.
      setSaveState("saving");
      try {
        await saveHistoryEntry({
          projectName: res.projectName || file.name.replace(/\.zip$/i, ""),
          analysisType: "CODE_ANALYSIS",
          status:
            res.status === "error" ? "error" : res.status === "success" ? "success" : "partial",
          totalSourceFiles: res.totalSourceFiles,
          analyzedFileCount: res.analyzedFileCount,
          codeAnalysisResult: res,
        });
        setSaveState("saved");
      } catch {
        setSaveState("error");
      }
    } catch (err) {
      setError(
        err instanceof Error
          ? `Code Analyzer unreachable or failed: ${err.message}`
          : "Code Analyzer unreachable.",
      );
    } finally {
      setAnalyzing(false);
      setStep(0);
    }
  };

  const sizeMb = file ? (file.size / (1024 * 1024)).toFixed(1) : null;

  return (
    <div>
      <div className="flex items-center gap-3 mb-1">
        <h1 className="text-3xl font-display font-medium">Code Analyzer</h1>
        <span className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full bg-[var(--gold)]/10 border border-[var(--gold)]/25 text-[var(--gold)]">
          <span className="w-1.5 h-1.5 rounded-full bg-[var(--gold)]" />
          Powered by EzzyHub AI
        </span>
      </div>
      <p className="text-sm text-muted-foreground mb-8">
        Upload a ZIP file and get a semantic analysis of every class, method, and field.
      </p>

      <div className="grid lg:grid-cols-[1fr_320px] gap-6">
        {/* upload + analyze */}
        <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
          <h3 className="font-medium mb-1">Upload Project ZIP</h3>
          <p className="text-sm text-muted-foreground mb-5">
            Supports any language. ZIP up to 1 GB. Large projects can take a few minutes.
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
              Java · Python · TypeScript · Go · Rust · any language
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
            onClick={handleAnalyze}
            disabled={!file || analyzing}
            className="w-full mt-4 bg-[var(--gold)] text-primary-foreground font-medium py-3 rounded-xl hover:shadow-[0_0_25px_rgba(245,191,49,0.4)] transition-shadow flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {analyzing ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" /> Analyzing...
              </>
            ) : (
              <>
                <Code2 className="w-4 h-4" /> Analyze Code
              </>
            )}
          </button>

          {analyzing && (
            <StepProgress
              steps={STEPS}
              current={step}
              title="Analyzing your project..."
              subtitle="This may take a few minutes depending on project size."
            />
          )}

          {result && (
            <>
              <div className="mt-4 flex items-center gap-2 text-sm">
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
                    <AlertCircle className="w-3.5 h-3.5" /> Analysis complete, but saving to History
                    failed.
                  </span>
                )}
              </div>
              <CodeAnalysisResults result={result} />
            </>
          )}
        </div>

        {/* info panel */}
        <div className="space-y-4">
          <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-5">
            <h4 className="font-medium mb-3 text-sm">What you'll get</h4>
            <ul className="space-y-2">
              {WHAT_YOU_GET.map((f) => (
                <li key={f} className="flex items-start gap-2 text-sm text-foreground/80">
                  <span className="text-[var(--gold)] mt-0.5">✓</span>
                  {f}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
}
