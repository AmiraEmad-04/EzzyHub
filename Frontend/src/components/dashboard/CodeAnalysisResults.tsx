import { useState } from "react";
import { ChevronDown, ChevronRight, Folder, FileIcon } from "lucide-react";
import type {
  AnalysisResult,
  FileNode,
  FileSemanticAnalysis,
  ProjectStructure,
} from "@/lib/codeAnalyzer";

function formatBytes(n: number | undefined): string {
  if (n == null) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function FileTreeNode({ node, depth = 0 }: { node: FileNode; depth?: number }) {
  const [open, setOpen] = useState(depth < 1);
  const hasChildren = !!node.children?.length;
  return (
    <div>
      <div
        className={`flex items-center gap-1.5 py-1 text-sm ${hasChildren ? "cursor-pointer" : ""}`}
        style={{ paddingLeft: depth * 16 }}
        onClick={() => hasChildren && setOpen((o) => !o)}
      >
        {hasChildren ? (
          open ? (
            <ChevronDown className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          ) : (
            <ChevronRight className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          )
        ) : (
          <span className="w-3.5 shrink-0" />
        )}
        {node.type === "directory" ? (
          <Folder className="w-3.5 h-3.5 text-[var(--gold)] shrink-0" />
        ) : (
          <FileIcon className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        )}
        <span className="text-foreground/85 truncate">{node.name}</span>
      </div>
      {hasChildren && open && (
        <div>
          {node.children!.slice(0, 200).map((child, i) => (
            <FileTreeNode key={`${child.name}-${i}`} node={child} depth={depth + 1} />
          ))}
          {node.children!.length > 200 && (
            <p
              className="text-xs text-muted-foreground"
              style={{ paddingLeft: (depth + 1) * 16 + 20 }}
            >
              … +{node.children!.length - 200} more
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function StructureSection({ structure }: { structure: ProjectStructure }) {
  const [showTree, setShowTree] = useState(false);
  const stats = structure.stats;
  const langs = Object.entries(stats?.languageDistribution ?? {}).sort((a, b) => b[1] - a[1]);

  return (
    <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6 mb-6">
      <h3 className="font-medium mb-4">Project Structure</h3>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-5">
        <div className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3.5">
          <p className="text-[11px] text-muted-foreground mb-1">Total Files</p>
          <p className="text-xl font-medium text-[var(--gold)]">{stats?.totalFiles ?? "—"}</p>
        </div>
        <div className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3.5">
          <p className="text-[11px] text-muted-foreground mb-1">Directories</p>
          <p className="text-xl font-medium">{stats?.totalDirectories ?? "—"}</p>
        </div>
        <div className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3.5">
          <p className="text-[11px] text-muted-foreground mb-1">Max Depth</p>
          <p className="text-xl font-medium">{stats?.maxDepth ?? "—"}</p>
        </div>
        <div className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3.5">
          <p className="text-[11px] text-muted-foreground mb-1">Size</p>
          <p className="text-xl font-medium text-green-400">{formatBytes(stats?.totalSizeBytes)}</p>
        </div>
      </div>

      {langs.length > 0 && (
        <div className="mb-4">
          <p className="text-xs text-muted-foreground mb-2">Languages</p>
          <div className="flex flex-wrap gap-1.5">
            {langs.map(([lang, count]) => (
              <span
                key={lang}
                className="text-xs px-2 py-1 rounded-md bg-blue-500/10 text-blue-400 border border-blue-500/20"
              >
                {lang} ({count})
              </span>
            ))}
          </div>
        </div>
      )}

      {[
        ["Entry Points", structure.entryPoints],
        ["Build Files", structure.buildFiles],
        ["Config Files", structure.configFiles],
      ].map(([label, arr]) =>
        arr && (arr as string[]).length > 0 ? (
          <div key={label as string} className="mb-4">
            <p className="text-xs text-muted-foreground mb-2">{label}</p>
            <div className="flex flex-wrap gap-1.5">
              {(arr as string[]).map((x) => (
                <span
                  key={x}
                  className="text-xs px-2 py-1 rounded-md bg-muted/40 text-muted-foreground font-mono"
                >
                  {x}
                </span>
              ))}
            </div>
          </div>
        ) : null,
      )}

      {structure.tree && (
        <div>
          <button
            onClick={() => setShowTree((s) => !s)}
            className="text-sm text-[var(--gold)] hover:underline mb-2"
          >
            {showTree ? "Hide" : "Show"} file tree
          </button>
          {showTree && (
            <div className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3 max-h-[340px] overflow-y-auto">
              <FileTreeNode node={structure.tree} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function FileGroup({ file }: { file: FileSemanticAnalysis }) {
  const classes = file.classes ?? [];
  return (
    <div className="mb-6 pb-6 border-b border-[var(--gold)]/10 last:border-0 last:mb-0 last:pb-0">
      <div className="flex items-center gap-2 mb-3 pb-2 border-b border-[var(--gold)]/10">
        <span className="font-mono text-sm text-blue-400">{file.fileName}</span>
        {file.semanticRole && (
          <span className="text-xs text-muted-foreground">— {file.semanticRole}</span>
        )}
      </div>

      {classes.length === 0 && (
        <p className="text-sm text-muted-foreground">No further breakdown available.</p>
      )}

      {classes.map((c, i) => (
        <div key={`${c.name}-${i}`} className="mb-4 last:mb-0">
          {c.name && <h4 className="font-medium text-base mb-1">{c.name}</h4>}
          {c.semanticMeaning && (
            <p className="text-sm text-muted-foreground mb-3">{c.semanticMeaning}</p>
          )}

          {(c.methods ?? []).length > 0 ? (
            <div className="space-y-2">
              {c.methods!.map((m, j) => (
                <div
                  key={`${m.name}-${j}`}
                  className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3"
                >
                  <p className="font-mono text-sm text-[var(--gold)] mb-1 break-words">
                    {m.signature || `${m.name}()`}
                  </p>
                  <p className="text-sm text-foreground/85">
                    {m.semanticMeaning || (
                      <span className="text-muted-foreground">No description provided.</span>
                    )}
                  </p>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">No methods found.</p>
          )}

          {(c.fields ?? []).length > 0 && (
            <div className="mt-3">
              <p className="text-xs text-muted-foreground mb-1.5">Fields</p>
              {c.fields!.map((f, k) => (
                <div key={`${f.name}-${k}`} className="flex gap-2 py-1 text-sm">
                  <span className="font-mono text-purple-400 shrink-0">{f.name}</span>
                  <span className="text-foreground/80">{f.semanticMeaning}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

export function CodeAnalysisResults({ result }: { result: AnalysisResult }) {
  const files = result.fileAnalyses ?? [];

  return (
    <div className="mt-8 animate-fade-up">
      <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6 mb-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-medium">Analysis Complete</h3>
          <span className="text-xs px-2.5 py-1 rounded-full bg-green-500/10 border border-green-500/25 text-green-400">
            {result.status === "success" ? "Success" : result.status}
          </span>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          {[
            {
              label: "Files Analyzed",
              value: result.analyzedFileCount ?? files.length,
              color: "text-[var(--gold)]",
            },
            { label: "Total Source", value: result.totalSourceFiles, color: "text-green-400" },
            { label: "LLM", value: result.llmProvider ?? "—", color: "text-foreground" },
            {
              label: "Time (ms)",
              value: result.processingTimeMs?.toLocaleString() ?? "—",
              color: "text-foreground",
            },
          ].map((c) => (
            <div
              key={c.label}
              className="rounded-lg border border-[var(--gold)]/10 bg-background/40 p-3.5"
            >
              <p className="text-[11px] text-muted-foreground mb-1">{c.label}</p>
              <p className={`text-lg font-medium ${c.color}`}>{c.value}</p>
            </div>
          ))}
        </div>
      </div>

      {result.structure && <StructureSection structure={result.structure} />}

      <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
        <h3 className="font-medium mb-4">File-Level Analysis</h3>
        {files.length === 0 ? (
          <p className="text-sm text-muted-foreground">No file analyses returned.</p>
        ) : (
          files.map((f, i) => <FileGroup key={`${f.filePath}-${i}`} file={f} />)
        )}
      </div>
    </div>
  );
}
