/**
 * Client for AI-Suggestions's /api/requirements/from-match endpoints, via
 * api-gateway's /api/ai-suggestions/** route. Types mirror
 * com.example.ezzyhubai.entity.GenerationVersion field-for-field (its
 * `requirement`, `parentVersion`, `children`, and `files` associations are
 * @JsonIgnore on the backend, so they're intentionally absent here).
 *
 * Identity comes from the same JWT auth-service issues on login — sent as a
 * normal Authorization: Bearer <token> header, same as lib/history.ts.
 */

import { getToken } from "@/lib/auth";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8085";
const GENERATION_TIMEOUT_MS = 900_000;

export type GenerationVersion = {
  id: number;
  batch?: { id: number; createdAt?: string };
  versionNumber: number;
  userPrompt?: string;
  zipPath?: string;
  requirementStatus?: string;
  similarityScore?: number;
  createdAt?: string;
};

export type GenerateFromMatchResult = {
  batchId: number;
  versions: GenerationVersion[];
};

type ApiError = { error?: string; message?: string };

function authHeaders(): HeadersInit {
  const token = getToken();
  if (!token) {
    throw new Error("You're not signed in.");
  }
  return { Authorization: `Bearer ${token}` };
}

export async function generateFromMatch(
  reportFile: File,
  projectZip: File,
): Promise<GenerateFromMatchResult> {
  const formData = new FormData();
  formData.append("report", reportFile);
  formData.append("projectZip", projectZip);

  const res = await fetch(`${API_BASE_URL}/api/ai-suggestions/from-match`, {
    method: "POST",
    headers: authHeaders(),
    body: formData,
    signal: AbortSignal.timeout(GENERATION_TIMEOUT_MS),
  });

  const body = await res.json();
  if (!res.ok) {
    const err = body as ApiError;
    throw new Error(err.message || err.error || `Generation failed (HTTP ${res.status})`);
  }
  return body as GenerateFromMatchResult;
}

/**
 * Downloads the generated project ZIP for a batch. Uses fetch (not a plain
 * <a href>) because the request needs the Authorization header, which a bare
 * link can't send.
 */
export async function downloadGeneratedProject(batchId: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/ai-suggestions/from-match/${batchId}`, {
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Could not download the generated project (${res.status})`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `ezzyhub-generated-project-${batchId}.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
