import { useEffect, useMemo, useState } from "react";
import { Search, Download, Trash2, Loader2, History as HistoryIcon } from "lucide-react";
import {
  deleteHistoryEntry,
  downloadHistoryPdf,
  fetchHistory,
  onHistoryUpdated,
  type HistoryEntry,
} from "@/lib/history";

const STATUS_STYLES: Record<string, string> = {
  success: "text-green-400 bg-green-500/10 border-green-500/25",
  partial: "text-[var(--gold)] bg-[var(--gold)]/10 border-[var(--gold)]/25",
  error: "text-red-400 bg-red-500/10 border-red-500/25",
};

const STATUS_LABELS: Record<string, string> = {
  success: "Success",
  partial: "Partial",
  error: "Error",
};

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  } catch {
    return iso;
  }
}

export function HistoryPanel() {
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<"all" | string>("all");

  const load = () => {
    setLoading(true);
    setError(null);
    fetchHistory()
      .then(setEntries)
      .catch((err) => setError(err instanceof Error ? err.message : "Could not load history."))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // Panels stay mounted in the background now, so History won't naturally
    // re-fetch just because you switch to it — this is what makes a report
    // saved from another tab actually show up here without a manual reload.
    return onHistoryUpdated(load);
  }, []);

  const filtered = useMemo(() => {
    return entries.filter((e) => {
      const matchesQuery = e.projectName?.toLowerCase().includes(query.toLowerCase());
      const matchesStatus = statusFilter === "all" || e.status === statusFilter;
      return matchesQuery && matchesStatus;
    });
  }, [entries, query, statusFilter]);

  const handleDelete = async (id: number) => {
    setBusyId(id);
    try {
      await deleteHistoryEntry(id);
      setEntries((prev) => prev.filter((e) => e.id !== id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not delete that report.");
    } finally {
      setBusyId(null);
    }
  };

  const handleDownload = async (id: number) => {
    setBusyId(id);
    try {
      await downloadHistoryPdf(id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not download the PDF.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <h1 className="text-3xl font-display font-medium mb-1">History</h1>
      <p className="text-sm text-muted-foreground mb-8">
        Every verification report you've saved, searchable and ready to compare against your latest
        scan.
      </p>

      <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-6">
        <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
          <h3 className="font-medium">Past Runs</h3>
          <div className="flex items-center gap-2">
            <div className="relative">
              <Search className="absolute left-3 top-2.5 w-3.5 h-3.5 text-muted-foreground" />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search projects..."
                className="w-44 bg-background/60 border border-border rounded-lg pl-8 pr-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
              />
            </div>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="bg-background/60 border border-border rounded-lg px-3 py-2 text-sm text-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
            >
              <option value="all">All statuses</option>
              <option value="success">Success</option>
              <option value="partial">Partial</option>
              <option value="error">Error</option>
            </select>
          </div>
        </div>

        {error && (
          <div className="mb-4 p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm">
            {error}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground text-sm">
            <Loader2 className="w-4 h-4 animate-spin" />
            Loading your history...
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center text-center py-16">
            <div className="w-12 h-12 rounded-full bg-[var(--gold)]/10 border border-[var(--gold)]/25 flex items-center justify-center mb-4">
              <HistoryIcon className="w-5 h-5 text-[var(--gold)]" />
            </div>
            <p className="text-foreground/85 font-medium mb-1">
              {entries.length === 0 ? "No verification reports yet" : "No runs match that search"}
            </p>
            <p className="text-sm text-muted-foreground max-w-sm">
              {entries.length === 0
                ? "Run an analysis from Code Analyzer, Sonar Analyzer, or Req ↔ Code Matching, and save it — it'll show up here."
                : "Try a different search term or status filter."}
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-muted-foreground text-xs uppercase tracking-wide">
                  <th className="pb-3 pr-4 font-medium">Project</th>
                  <th className="pb-3 pr-4 font-medium">Date</th>
                  <th className="pb-3 pr-4 font-medium">Requirements</th>
                  <th className="pb-3 pr-4 font-medium">Coverage</th>
                  <th className="pb-3 pr-4 font-medium">Status</th>
                  <th className="pb-3 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--gold)]/10">
                {filtered.map((e) => (
                  <tr key={e.id}>
                    <td className="py-3 pr-4 text-foreground/90 font-medium">{e.projectName}</td>
                    <td className="py-3 pr-4 text-muted-foreground">{formatDate(e.createdAt)}</td>
                    <td className="py-3 pr-4 text-foreground/80">{e.totalRequirements ?? "—"}</td>
                    <td className="py-3 pr-4 text-foreground/80">
                      {e.coveragePercentage != null ? `${Math.round(e.coveragePercentage)}%` : "—"}
                    </td>
                    <td className="py-3 pr-4">
                      <span
                        className={`inline-flex text-xs px-2.5 py-1 rounded-full border ${STATUS_STYLES[e.status] ?? "text-muted-foreground bg-muted/40 border-border"}`}
                      >
                        {STATUS_LABELS[e.status] ?? e.status}
                      </span>
                    </td>
                    <td className="py-3 text-right">
                      <div className="inline-flex items-center gap-1.5">
                        <button
                          title="Download PDF"
                          disabled={busyId === e.id}
                          onClick={() => handleDownload(e.id)}
                          className="p-1.5 rounded-md text-muted-foreground hover:text-[var(--gold)] hover:bg-[var(--gold)]/10 transition-colors disabled:opacity-40"
                        >
                          {busyId === e.id ? (
                            <Loader2 className="w-3.5 h-3.5 animate-spin" />
                          ) : (
                            <Download className="w-3.5 h-3.5" />
                          )}
                        </button>
                        <button
                          title="Delete"
                          disabled={busyId === e.id}
                          onClick={() => handleDelete(e.id)}
                          className="p-1.5 rounded-md text-muted-foreground hover:text-red-400 hover:bg-red-500/10 transition-colors disabled:opacity-40"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
