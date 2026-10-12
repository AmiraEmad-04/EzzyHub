const express = require("express");
const multer = require("multer");
const cors = require("cors");
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const AdmZip = require("adm-zip");
const axios = require("axios");

const app = express();

const ALLOWED_ORIGINS = (process.env.CORS_ALLOWED_ORIGINS || "http://localhost:3000,http://localhost:5173")
    .split(",")
    .map((o) => o.trim());
app.use(cors({ origin: ALLOWED_ORIGINS }));
app.use(express.json());

const SONAR_URL = process.env.SONAR_URL || "http://sonarqube:9000";
const SONAR_TOKEN = process.env.SONAR_TOKEN || "";
const SONAR_USER = process.env.SONAR_USER;
const SONAR_PASS = process.env.SONAR_PASS;

if (!SONAR_USER || !SONAR_PASS) {
  throw new Error("SONAR_USER and SONAR_PASS must be set as environment variables (no default credentials allowed).");
}

const upload = multer({
  dest: "/tmp/uploads/",
  limits: { fileSize: 200 * 1024 * 1024 }, // 200MB
  fileFilter: (req, file, cb) => {
    if (!file.originalname.toLowerCase().endsWith(".zip")) {
      return cb(new Error("Only ZIP files are supported"));
    }
    cb(null, true);
  },
});

// Only allow safe characters in project names used for filesystem paths / process args
function sanitizeProjectName(name) {
  const trimmed = (name || "Spring Boot Project").trim();
  return trimmed.replace(/[^a-zA-Z0-9 ._-]/g, "").slice(0, 100) || "Spring Boot Project";
}

// Extract a zip while rejecting any entry that would escape the target directory (zip-slip protection)
function safeExtract(zip, targetDir) {
  const resolvedTarget = path.resolve(targetDir);
  for (const entry of zip.getEntries()) {
    const entryPath = path.resolve(resolvedTarget, entry.entryName);
    if (!entryPath.startsWith(resolvedTarget + path.sep) && entryPath !== resolvedTarget) {
      throw new Error(`Zip entry outside target dir: ${entry.entryName}`);
    }
  }
  zip.extractAllTo(resolvedTarget, true);
}

// Helper: wait for SonarQube to be ready
async function waitForSonar(retries = 20, delay = 5000) {
  for (let i = 0; i < retries; i++) {
    try {
      const res = await axios.get(`${SONAR_URL}/api/system/status`, {
        auth: { username: SONAR_USER, password: SONAR_PASS },
        timeout: 5000,
      });
      if (res.data.status === "UP") return true;
    } catch (e) {}
    await new Promise((r) => setTimeout(r, delay));
  }
  throw new Error("SonarQube not ready");
}

// Helper: create or reset project
async function ensureProject(projectKey, projectName) {
  const auth = { username: SONAR_USER, password: SONAR_PASS };
  try {
    await axios.post(
        `${SONAR_URL}/api/projects/create`,
        null,
        {
          params: { project: projectKey, name: projectName },
          auth,
        }
    );
  } catch (e) {
    // Project might already exist — that's fine
  }
}

// Helper: generate user token
async function generateToken(tokenName) {
  const auth = { username: SONAR_USER, password: SONAR_PASS };
  try {
    // Revoke old token first
    await axios.post(`${SONAR_URL}/api/user_tokens/revoke`, null, {
      params: { name: tokenName },
      auth,
    });
  } catch (e) {}

  const res = await axios.post(`${SONAR_URL}/api/user_tokens/generate`, null, {
    params: { name: tokenName },
    auth,
  });
  return res.data.token;
}

// Helper: wait for analysis task to finish
async function waitForAnalysis(projectKey, auth, retries = 30, delay = 3000) {
  for (let i = 0; i < retries; i++) {
    try {
      const res = await axios.get(`${SONAR_URL}/api/ce/component`, {
        params: { component: projectKey },
        auth,
      });
      const tasks = res.data.queue || [];
      const current = res.data.current;
      if (tasks.length === 0 && current && current.status === "SUCCESS") {
        return true;
      }
      if (current && current.status === "FAILED") {
        throw new Error("SonarQube analysis task failed");
      }
    } catch (e) {
      if (e.message.includes("FAILED")) throw e;
    }
    await new Promise((r) => setTimeout(r, delay));
  }
  throw new Error("Analysis timed out");
}

// Helper: fetch issues from SonarQube
async function fetchIssues(projectKey, auth) {
  let issues = [];
  let page = 1;
  const pageSize = 500;

  while (true) {
    const res = await axios.get(`${SONAR_URL}/api/issues/search`, {
      params: {
        componentKeys: projectKey,
        ps: pageSize,
        p: page,
        additionalFields: "rules,comments",
      },
      auth,
    });
    issues = issues.concat(res.data.issues || []);
    const total = res.data.paging?.total || 0;
    if (issues.length >= total) break;
    page++;
  }
  return issues;
}

// Helper: fetch metrics/measures
async function fetchMeasures(projectKey, auth) {
  const metrics = [
    "bugs", "vulnerabilities", "code_smells", "security_hotspots",
    "coverage", "duplicated_lines_density", "ncloc", "sqale_rating",
    "reliability_rating", "security_rating", "alert_status",
  ].join(",");

  const res = await axios.get(`${SONAR_URL}/api/measures/component`, {
    params: { component: projectKey, metricKeys: metrics },
    auth,
  });
  return res.data.component?.measures || [];
}

// Main analysis endpoint
app.post("/api/analyze", upload.any(), async (req, res) => {
  if (req.files && req.files.length > 0) req.file = req.files[0];

  const projectKey = `proj-${Date.now()}`;
  const projectName = sanitizeProjectName(req.body.projectName);
  const extractDir = `/tmp/projects/${projectKey}`;

  try {
    // Magic bytes check: confirm the file content is actually a ZIP, not just the filename
    const header = fs.readFileSync(req.file.path, { encoding: null, flag: "r" }).subarray(0, 4);
    if (header.length < 4 || header[0] !== 0x50 || header[1] !== 0x4b) {
      throw new Error("File is not a valid ZIP archive");
    }

    // Extract ZIP
    fs.mkdirSync(extractDir, { recursive: true });
    const zip = new AdmZip(req.file.path);

    // Entry-count limit (zip-bomb protection by entry count only)
    const entries = zip.getEntries();
    if (entries.length > 10000) {
      throw new Error("ZIP has too many entries");
    }

    safeExtract(zip, extractDir);
    fs.unlinkSync(req.file.path);

    // Wait for SonarQube
    await waitForSonar();

    const auth = { username: SONAR_USER, password: SONAR_PASS };

    // Create project
    await ensureProject(projectKey, projectName);

    // Generate token
    const token = await generateToken(`token-${projectKey}`);

    // Run sonar-scanner — pass args as an array (no shell) so no value here can inject commands
    const scannerArgs = [
      `-Dsonar.projectKey=${projectKey}`,
      `-Dsonar.projectName=${projectName}`,
      "-Dsonar.sources=.",
      `-Dsonar.host.url=${SONAR_URL}`,
      `-Dsonar.login=${token}`,
      "-Dsonar.java.binaries=.",
      "-Dsonar.sourceEncoding=UTF-8",
    ];

    execFileSync("sonar-scanner", scannerArgs, { cwd: extractDir, stdio: "pipe", timeout: 120000 });

    // Wait for analysis to complete
    await waitForAnalysis(projectKey, auth);

    // Fetch results
    const [issues, measures] = await Promise.all([
      fetchIssues(projectKey, auth),
      fetchMeasures(projectKey, auth),
    ]);

    // Group issues by file
    const byFile = {};
    for (const issue of issues) {
      const comp = issue.component?.replace(`${projectKey}:`, "") || "Unknown";
      if (!byFile[comp]) byFile[comp] = [];
      byFile[comp].push({
        severity: issue.severity,
        type: issue.type,
        message: issue.message,
        line: issue.line,
        rule: issue.rule,
        effort: issue.effort,
        debt: issue.debt,
        tags: issue.tags || [],
      });
    }

    // Cleanup
    fs.rmSync(extractDir, { recursive: true, force: true });

    res.json({
      projectKey,
      projectName,
      measures,
      issuesByFile: byFile,
      totalIssues: issues.length,
      sonarUrl: `${SONAR_URL}/dashboard?id=${projectKey}`,
    });
  } catch (err) {
    try { fs.rmSync(extractDir, { recursive: true, force: true }); } catch {}
    console.error(err);
    res.status(500).json({ error: "Analysis failed. Check server logs for details." });
  }
});

// Health check
app.get("/api/health", async (req, res) => {
  try {
    await axios.get(`${SONAR_URL}/api/system/status`, {
      auth: { username: SONAR_USER, password: SONAR_PASS },
      timeout: 3000,
    });
    res.json({ status: "ok", sonar: "connected" });
  } catch {
    res.json({ status: "ok", sonar: "unreachable" });
  }
});

const PORT = process.env.PORT || 8083;
app.listen(PORT, () => console.log("Sonar Analyzer backend running on port " + PORT));