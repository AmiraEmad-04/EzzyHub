/**
 * Client for matching-service's /api/v1/match, via api-gateway's /api/match
 * route. Types mirror com.ezzyhub.matching.dto.MatchReport /
 * RequirementMatchResult / ChunkMatchResult field-for-field.
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8085";
const MATCH_TIMEOUT_MS = 900_000;

export type ChunkMatchResult = {
  filePath: string;
  className?: string;
  methodName?: string;
  chunkType: string;
  chunkText?: string;
  lineStart: number;
  score: number;
  cosineScore: number;
  jaccardScore: number;
  chunkKeywords?: string[];
};

export type RequirementStatus = "IMPLEMENTED" | "PARTIAL" | "MISSING";

export type RequirementMatchResult = {
  reqId?: string;
  reqText: string;
  status: RequirementStatus;
  score: number;
  cosineScore: number;
  jaccardScore: number;
  topChunks?: ChunkMatchResult[];
  requirementKeywords?: string[];
  confidenceNote?: string;
};

export type MatchReport = {
  reportId: string;
  timestamp: string;
  totalRequirements: number;
  implementedCount: number;
  partialCount: number;
  missingCount: number;
  coveragePercent: number;
  totalJavaFiles: number;
  totalCodeChunks: number;
  processingTimeMs: number;
  requirementMatches?: RequirementMatchResult[];
  warnings?: string[];
};

type ApiError = { status: number; error: string; message: string };

/** Best short label for the "matched code" column — e.g. "UserController.login()". */
export function describeTopChunk(r: RequirementMatchResult): string {
  const top = r.topChunks?.[0];
  if (!top) return "—";
  if (top.className && top.methodName) return `${top.className}.${top.methodName}()`;
  if (top.className) return top.className;
  return top.filePath;
}

export async function runMatching(file: File, requirements: string[]): Promise<MatchReport> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("requirements", JSON.stringify(requirements.map((reqText) => ({ reqText }))));

  const res = await fetch(`${API_BASE_URL}/api/match`, {
    method: "POST",
    body: formData,
    signal: AbortSignal.timeout(MATCH_TIMEOUT_MS),
  });

  const body = await res.json();
  if (!res.ok) {
    const err = body as ApiError;
    throw new Error(err.message || `Matching failed (HTTP ${res.status})`);
  }
  return body as MatchReport;
}
