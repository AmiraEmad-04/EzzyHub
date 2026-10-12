/**
 * Client for code-analyzer's /api/v1/analyze, via api-gateway's
 * /api/code/** route. Types below mirror the backend's real DTOs
 * (com.codeanalyzer.model.AnalysisResult / FileSemanticAnalysis /
 * ProjectStructure) field-for-field — checked directly against that
 * service's source, not guessed from the response.
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8085";

// Large projects can take a while — gateway's own response-timeout for this
// route is 900s, so match it here rather than hitting the browser's default
// fetch timeout (none) in a way that's inconsistent with the backend.
const ANALYZE_TIMEOUT_MS = 900_000;

export type MethodSemantic = {
  name: string;
  signature: string;
  visibility: string;
  isAsync: boolean;
  isStatic: boolean;
  returnType: string;
  semanticMeaning: string;
};

export type FieldSemantic = {
  name: string;
  type: string;
  visibility: string;
  semanticMeaning: string;
};

export type ClassSemantic = {
  name: string;
  kind: string;
  extendsFrom?: string;
  semanticMeaning: string;
  fields?: FieldSemantic[];
  methods?: MethodSemantic[];
};

export type FileSemanticAnalysis = {
  filePath: string;
  fileName: string;
  language: string;
  semanticRole: string;
  dependencies?: string[];
  classes?: ClassSemantic[];
  moduleFunctions?: MethodSemantic[];
};

export type FileNode = {
  name: string;
  type: "file" | "directory";
  children?: FileNode[];
};

export type ProjectStats = {
  totalFiles: number;
  totalDirectories: number;
  totalSizeBytes: number;
  languageDistribution?: Record<string, number>;
  maxDepth: number;
  topLevelDirs?: string[];
};

export type ProjectStructure = {
  tree?: FileNode;
  stats?: ProjectStats;
  entryPoints?: string[];
  configFiles?: string[];
  buildFiles?: string[];
};

export type AnalysisResult = {
  id: string;
  status: "success" | "partial" | "error";
  timestamp: string;
  projectName?: string;
  processingTimeMs: number;
  llmProvider?: string;
  llmModel?: string;
  fileAnalyses?: FileSemanticAnalysis[];
  structure?: ProjectStructure;
  totalSourceFiles: number;
  analyzedFileCount: number;
  error?: string;
};

export type ApiError = {
  status: number;
  error: string;
  message: string;
  path?: string;
};

export async function analyzeCode(file: File): Promise<AnalysisResult> {
  const formData = new FormData();
  formData.append("file", file);

  const res = await fetch(`${API_BASE_URL}/api/code/analyze`, {
    method: "POST",
    body: formData,
    signal: AbortSignal.timeout(ANALYZE_TIMEOUT_MS),
  });

  const body = await res.json();
  if (!res.ok) {
    const err = body as ApiError;
    throw new Error(err.message || `Analysis failed (HTTP ${res.status})`);
  }
  return body as AnalysisResult;
}
