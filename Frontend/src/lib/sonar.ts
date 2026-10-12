/**
 * Client for sonar-analyzer's /api/analyze, via api-gateway's new
 * /api/sonar/** route. Types mirror com.sonaranalyzer.model.AnalysisReport /
 * SonarIssue field-for-field — this service returns its own flat summary
 * fields directly (bugs, vulnerabilities, coverage as a string like
 * "82.5%", etc.), not a raw SonarQube `measures` array.
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8085";
// The sonar-analyzer runs analysis ASYNCHRONOUSLY: POST /analyze returns a jobId
// immediately, and the result is polled from /status/{jobId}. These bound the poll.
const SONAR_POLL_INTERVAL_MS = 5000;
const SONAR_MAX_WAIT_MS = 900_000; // 15 min ceiling, so a stuck job can't poll forever

export type SonarIssue = {
  key: string;
  rule: string;
  severity: "BLOCKER" | "CRITICAL" | "MAJOR" | "MINOR" | "INFO" | string;
  component: string;
  project?: string;
  line?: number;
  status?: string;
  message: string;
  type: "BUG" | "VULNERABILITY" | "CODE_SMELL" | string;
  filePath?: string;
};

export type AnalysisReport = {
  projectKey?: string;
  projectName?: string;
  analysisDate?: string;
  status: "SUCCESS" | "FAILED" | "IN_PROGRESS" | string;
  totalIssues: number;
  blocker: number;
  critical: number;
  major: number;
  minor: number;
  info: number;
  bugs: number;
  vulnerabilities: number;
  codeSmells: number;
  securityHotspots: number;
  coverage?: string;
  duplications?: string;
  linesOfCode?: string;
  issuesByFile?: Record<string, SonarIssue[]>;
  allIssues?: SonarIssue[];
  qualityGateStatus?: "OK" | "WARN" | "ERROR" | string;
};

type StartJobResponse = { jobId?: string; message?: string; error?: string };
type StatusResponse = {
  status: "RUNNING" | "DONE" | "FAILED" | "NOT_FOUND" | string;
  result?: AnalysisReport;
  error?: string;
};

/**
 * Runs a Sonar analysis against the async sonar-analyzer API:
 *   1. POST /api/sonar/analyze  → returns a jobId immediately.
 *   2. Poll GET /api/sonar/status/{jobId} every few seconds until the job is
 *      DONE (returns the report) or FAILED.
 *
 * @param onProgress optional callback invoked on each poll with the seconds
 *   elapsed so far, so the UI can show a live "analyzing… Ns" message.
 */
export async function runSonarAnalysis(
  file: File,
  projectName?: string,
  onProgress?: (elapsedSeconds: number) => void,
): Promise<AnalysisReport> {
  const formData = new FormData();
  formData.append("file", file);
  if (projectName) formData.append("projectName", projectName);

  // ── Step 1: start the job ──────────────────────────────────────────────────
  const startRes = await fetch(`${API_BASE_URL}/api/sonar/analyze`, {
    method: "POST",
    body: formData,
  });
  const startBody = (await startRes.json().catch(() => ({}))) as StartJobResponse;
  if (!startRes.ok || !startBody.jobId) {
    // 409 = another analysis already running; 400 = bad file; etc.
    throw new Error(startBody.error || `Failed to start analysis (HTTP ${startRes.status})`);
  }
  const jobId = startBody.jobId;

  // ── Step 2: poll for the result ────────────────────────────────────────────
  const startedAt = Date.now();
  // eslint-disable-next-line no-constant-condition
  while (true) {
    await new Promise((r) => setTimeout(r, SONAR_POLL_INTERVAL_MS));

    const elapsedMs = Date.now() - startedAt;
    onProgress?.(Math.round(elapsedMs / 1000));
    if (elapsedMs > SONAR_MAX_WAIT_MS) {
      throw new Error("Sonar analysis timed out. Please try again or check the sonar-analyzer service.");
    }

    const statusRes = await fetch(`${API_BASE_URL}/api/sonar/status/${jobId}`);
    // 404 carries a NOT_FOUND status body we still want to read; any other
    // non-OK is a genuine transport failure.
    if (!statusRes.ok && statusRes.status !== 404) {
      throw new Error(`Status check failed (HTTP ${statusRes.status})`);
    }
    const statusBody = (await statusRes.json().catch(() => ({}))) as StatusResponse;

    if (statusBody.status === "DONE") {
      if (!statusBody.result) throw new Error("Analysis finished but returned no result.");
      return statusBody.result;
    }
    if (statusBody.status === "FAILED") {
      throw new Error(statusBody.error || "Analysis failed.");
    }
    if (statusBody.status === "NOT_FOUND") {
      throw new Error("Analysis job not found (it may have expired). Please run it again.");
    }
    // RUNNING (or anything unexpected) → keep polling.
  }
}
