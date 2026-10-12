/**
 * Client for report-service's /api/history endpoints (via api-gateway).
 *
 * Identity now comes from the same JWT auth-service issues on login —
 * sent as a normal Authorization: Bearer <token> header, same as
 * fetchCurrentUser() in lib/auth.ts already does. This used to send a
 * plain X-User-Id header instead, which report-service trusted at face
 * value; anyone could edit that header in devtools to read or delete
 * someone else's reports. report-service now verifies the token's
 * signature and pulls the user id out of that instead, so the caller here
 * doesn't need to pass a userId at all anymore — the token already is the
 * identity.
 */

import { getToken } from "@/lib/auth";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8085";

export type HistoryStatus = "success" | "partial" | "error" | string;

export type HistoryEntry = {
  id: number;
  projectName: string;
  analysisType: string;
  createdAt: string;
  status: HistoryStatus;
  totalRequirements: number | null;
  implementedCount: number | null;
  partialCount: number | null;
  missingCount: number | null;
  coveragePercentage: number | null;
  totalSourceFiles: number | null;
  analyzedFileCount: number | null;
  hasCodeAnalysis: boolean;
  hasMatchResult: boolean;
  hasSonarResult: boolean;
  hasAiSuggestions: boolean;
};

export type AnalysisType = "CODE_ANALYSIS" | "REQUIREMENT_MATCH" | "SONAR" | "COMBINED";

export type SaveHistoryPayload = {
  projectName: string;
  analysisType: AnalysisType;
  status: "success" | "partial" | "error";
  totalRequirements?: number;
  implementedCount?: number;
  partialCount?: number;
  missingCount?: number;
  coveragePercentage?: number;
  totalSourceFiles?: number;
  analyzedFileCount?: number;
  codeAnalysisResult?: unknown;
  matchResult?: unknown;
  sonarResult?: unknown;
  aiSuggestions?: unknown;
};

function authHeaders(): HeadersInit {
  const token = getToken();
  if (!token) {
    throw new Error("You're not signed in.");
  }
  return { Authorization: `Bearer ${token}` };
}

const HISTORY_UPDATED_EVENT = "ezzyhub:history-updated";

/** HistoryPanel listens for this so it refreshes even while it's sitting in
 * a background tab (panels stay mounted, so it won't naturally re-fetch on
 * its own when another tab saves a new report). */
function notifyHistoryUpdated() {
  window.dispatchEvent(new CustomEvent(HISTORY_UPDATED_EVENT));
}

export function onHistoryUpdated(handler: () => void): () => void {
  window.addEventListener(HISTORY_UPDATED_EVENT, handler);
  return () => window.removeEventListener(HISTORY_UPDATED_EVENT, handler);
}

/**
 * Saves a finished analysis run as a history record. Called automatically
 * by each tool panel right after a run completes successfully — this is
 * what actually makes a report show up in History; running an analysis by
 * itself only shows the result on screen, it doesn't persist anything.
 */
export async function saveHistoryEntry(payload: SaveHistoryPayload): Promise<number> {
  const res = await fetch(`${API_BASE_URL}/api/history`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    throw new Error(`Could not save this report to history (${res.status})`);
  }
  const body = (await res.json()) as { id: number };
  notifyHistoryUpdated();
  return body.id;
}

export async function fetchHistory(): Promise<HistoryEntry[]> {
  const res = await fetch(`${API_BASE_URL}/api/history`, {
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Could not load history (${res.status})`);
  }
  return (await res.json()) as HistoryEntry[];
}

export async function deleteHistoryEntry(id: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/history/${id}`, {
    method: "DELETE",
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Could not delete that report (${res.status})`);
  }
}

/**
 * Downloads the PDF report for one record. Uses fetch (not a plain <a
 * href>) because the request needs the Authorization header, which a bare
 * link can't send.
 */
export async function downloadHistoryPdf(id: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/history/${id}/pdf`, {
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Could not download the PDF (${res.status})`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `ezzyhub-report-${id}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
