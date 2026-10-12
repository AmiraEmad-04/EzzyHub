package com.sonaranalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonaranalyzer.model.AnalysisReport;
import com.sonaranalyzer.model.SonarIssue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SonarService {

    @Value("${sonar.host.url}")
    private String sonarUrl;

    @Value("${sonar.token}")
    private String sonarToken;

    @Value("${app.temp.dir}")
    private String tempDir;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Main method: receives ZIP, extracts, runs sonar-scanner, fetches report
     */
    public AnalysisReport analyzeProject(MultipartFile zipFile, String projectName) throws Exception {
        // 1. Generate unique project key
        String projectKey = "project-" + System.currentTimeMillis();
        if (projectName != null && !projectName.isBlank()) {
            projectKey = projectName.toLowerCase().replaceAll("[^a-z0-9]", "-") + "-" + System.currentTimeMillis();
        }

        // 2. Extract ZIP to temp directory
        Path projectPath = extractZip(zipFile, projectKey);

        // 3. Create project in SonarQube
        createSonarProject(projectKey, projectName != null ? projectName : projectKey);

        // 4. Generate sonar-project.properties
        writeSonarProperties(projectPath, projectKey, projectName);

        // 5. Run sonar-scanner
        runSonarScanner(projectPath, projectKey);

        // 6. Wait for analysis to complete
        waitForAnalysis(projectKey);

        // 7. Fetch and return report
        return buildReport(projectKey, projectName);
    }

    private Path extractZip(MultipartFile zipFile, String projectKey) throws Exception {
        Path targetDir = Paths.get(tempDir, projectKey);
        Files.createDirectories(targetDir);

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new SecurityException("ZIP entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
        return targetDir;
    }

    private void createSonarProject(String projectKey, String projectName) {
        try {
            String url = sonarUrl + "/api/projects/create";
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("project", projectKey);
            body.add("name", projectName);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, request, String.class);
        } catch (Exception e) {
            // Project might already exist, continue
            System.out.println("Project creation note: " + e.getMessage());
        }
    }

    private void writeSonarProperties(Path projectPath, String projectKey, String projectName) throws Exception {
        Path sourcePath = findSourceRoot(projectPath);

        // تحديد مسار الـ binaries بناءً على نوع المشروع
        String binaries = resolveBinariesPath(sourcePath);

        String content = "sonar.projectKey=" + projectKey + "\n" +
                "sonar.projectName=" + (projectName != null ? projectName : projectKey) + "\n" +
                "sonar.sources=.\n" +
                "sonar.host.url=" + sonarUrl + "\n" +
                "sonar.token=" + sonarToken + "\n" +
                "sonar.java.binaries=" + binaries + "\n" +
                "sonar.exclusions=**/node_modules/**,**/*.min.js,**/target/**,**/*.class\n";

        Files.writeString(sourcePath.resolve("sonar-project.properties"), content);
    }

    /**
     * يحدد مسار الـ binaries الصح بناءً على نوع المشروع
     */
    private String resolveBinariesPath(Path sourcePath) {
        // Maven project
        if (Files.exists(sourcePath.resolve("pom.xml"))) {
            return "target/classes";
        }
        // Gradle project
        if (Files.exists(sourcePath.resolve("build.gradle")) ||
                Files.exists(sourcePath.resolve("build.gradle.kts"))) {
            return "build/classes";
        }
        // مشروع مش Java أو مش compiled → استخدم الـ source مباشرة
        return ".";
    }

    private Path findSourceRoot(Path projectPath) throws IOException {
        // If ZIP had a single top-level folder, use that
        try (var stream = Files.list(projectPath)) {
            List<Path> entries = stream.collect(Collectors.toList());
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
        }
        return projectPath;
    }

    private void runSonarScanner(Path projectPath, String projectKey) throws Exception {
        Path sourcePath = findSourceRoot(projectPath);

        // ابني المشروع الأول عشان يتولد الـ target/classes
        boolean built = tryBuildProject(sourcePath);

        String scannerCmd = findSonarScanner();

        // حدد الـ binaries بناءً على نتيجة الـ build
        String binaries = built ? resolveBinariesPath(sourcePath) : ".";

        ProcessBuilder pb = new ProcessBuilder(
                scannerCmd,
                "-Dsonar.projectKey=" + projectKey,
                "-Dsonar.host.url=" + sonarUrl,
                "-Dsonar.token=" + sonarToken,
                "-Dsonar.sources=.",
                "-Dsonar.java.binaries=" + binaries,
                "-Dsonar.exclusions=**/node_modules/**,**/*.min.js,**/target/**,**/*.class"
        );
        pb.directory(sourcePath.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("[SONAR-SCANNER] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("sonar-scanner failed (exit " + exitCode + "):\n" + output);
        }
    }

    /**
     * يحاول يبني المشروع (Maven أو Gradle) ويرجع true لو نجح
     */
    private boolean tryBuildProject(Path sourcePath) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        // Maven
        if (Files.exists(sourcePath.resolve("pom.xml"))) {
            System.out.println("[BUILD] pom.xml found → running Maven compile...");
            String mvn = isWindows ? "mvn.cmd" : "mvn";
            return runBuildCommand(sourcePath, mvn, "compile", "-q", "-DskipTests");
        }

        // Gradle wrapper أو Gradle عادي
        if (Files.exists(sourcePath.resolve("build.gradle")) ||
                Files.exists(sourcePath.resolve("build.gradle.kts"))) {
            System.out.println("[BUILD] build.gradle found → running Gradle classes...");
            boolean hasWrapper = Files.exists(sourcePath.resolve(isWindows ? "gradlew.bat" : "gradlew"));
            String gradle = hasWrapper ? (isWindows ? "gradlew.bat" : "./gradlew") : "gradle";
            return runBuildCommand(sourcePath, gradle, "classes", "-q");
        }

        System.out.println("[BUILD] No build file found, skipping build step.");
        return false;
    }

    /**
     * ينفذ أمر build ويرجع true لو exit code = 0
     */
    private boolean runBuildCommand(Path workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                r.lines().forEach(line -> System.out.println("[BUILD] " + line));
            }
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                System.out.println("[BUILD] Build succeeded.");
                return true;
            } else {
                System.out.println("[BUILD] Build failed with exit code " + exitCode + ". Continuing without binaries...");
                return false;
            }
        } catch (Exception e) {
            System.out.println("[BUILD] Could not run build: " + e.getMessage() + ". Continuing without binaries...");
            return false;
        }
    }

    private String findSonarScanner() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        String[] candidates = isWindows
                ? new String[]{"sonar-scanner.bat", "sonar-scanner"}
                : new String[]{
                "sonar-scanner",
                "/opt/sonar-scanner/bin/sonar-scanner",
                "/usr/local/bin/sonar-scanner"
        };

        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                if (p.exitValue() == 0) {
                    System.out.println("Found sonar-scanner at: " + cmd);
                    return cmd;
                }
            } catch (Exception ignored) {}
        }

        // البحث في PATH يدويًا
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String ext = isWindows ? ".bat" : "";
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File f = new File(dir, "sonar-scanner" + ext);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }

        throw new RuntimeException(
                "sonar-scanner not found. Install it and ensure it is on your PATH.\n" +
                        "Download: https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner/"
        );
    }

    private void waitForAnalysis(String projectKey) throws Exception {
        String url = sonarUrl + "/api/ce/component?component=" + projectKey;
        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> req = new HttpEntity<>(headers);

        int maxRetries = 30;
        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(3000);
            try {
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
                JsonNode root = objectMapper.readTree(resp.getBody());
                JsonNode current = root.path("current");
                if (!current.isMissingNode()) {
                    String status = current.path("status").asText();
                    if ("SUCCESS".equals(status)) return;
                    if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                        throw new RuntimeException("SonarQube analysis " + status);
                    }
                }
            } catch (Exception e) {
                System.out.println("Waiting for analysis... attempt " + (i + 1));
            }
        }
        throw new RuntimeException("Analysis timed out after 90 seconds");
    }

    private AnalysisReport buildReport(String projectKey, String projectName) throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setProjectKey(projectKey);
        report.setProjectName(projectName != null ? projectName : projectKey);
        report.setAnalysisDate(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        report.setStatus("SUCCESS");

        List<SonarIssue> allIssues = fetchAllIssues(projectKey);
        report.setAllIssues(allIssues);
        report.setTotalIssues(allIssues.size());

        report.setBlocker((int) allIssues.stream().filter(i -> "BLOCKER".equals(i.getSeverity())).count());
        report.setCritical((int) allIssues.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count());
        report.setMajor((int) allIssues.stream().filter(i -> "MAJOR".equals(i.getSeverity())).count());
        report.setMinor((int) allIssues.stream().filter(i -> "MINOR".equals(i.getSeverity())).count());
        report.setInfo((int) allIssues.stream().filter(i -> "INFO".equals(i.getSeverity())).count());

        report.setBugs((int) allIssues.stream().filter(i -> "BUG".equals(i.getType())).count());
        report.setVulnerabilities((int) allIssues.stream().filter(i -> "VULNERABILITY".equals(i.getType())).count());
        report.setCodeSmells((int) allIssues.stream().filter(i -> "CODE_SMELL".equals(i.getType())).count());

        Map<String, List<SonarIssue>> byFile = allIssues.stream()
                .collect(Collectors.groupingBy(SonarIssue::getFilePath));
        report.setIssuesByFile(byFile);

        fetchMetrics(projectKey, report);
        fetchQualityGate(projectKey, report);

        return report;
    }

    private List<SonarIssue> fetchAllIssues(String projectKey) throws Exception {
        List<SonarIssue> all = new ArrayList<>();
        int page = 1;
        int pageSize = 500;

        while (true) {
            String url = sonarUrl + "/api/issues/search?componentKeys=" + projectKey
                    + "&ps=" + pageSize + "&p=" + page + "&resolved=false";

            HttpEntity<Void> req = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isEmpty()) break;

            for (JsonNode node : issuesNode) {
                SonarIssue issue = objectMapper.treeToValue(node, SonarIssue.class);
                all.add(issue);
            }

            int total = root.path("total").asInt();
            if (all.size() >= total) break;
            page++;
        }
        return all;
    }

    private void fetchMetrics(String projectKey, AnalysisReport report) {
        try {
            String metrics = "coverage,duplicated_lines_density,ncloc,security_hotspots";
            String url = sonarUrl + "/api/measures/component?component=" + projectKey
                    + "&metricKeys=" + metrics;

            HttpEntity<Void> req = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode measures = root.path("component").path("measures");

            for (JsonNode m : measures) {
                String key = m.path("metric").asText();
                String value = m.path("value").asText();
                switch (key) {
                    case "coverage" -> report.setCoverage(value + "%");
                    case "duplicated_lines_density" -> report.setDuplications(value + "%");
                    case "ncloc" -> report.setLinesOfCode(value);
                    case "security_hotspots" -> report.setSecurityHotspots(Integer.parseInt(value));
                }
            }
        } catch (Exception e) {
            System.out.println("Could not fetch metrics: " + e.getMessage());
        }
    }

    private void fetchQualityGate(String projectKey, AnalysisReport report) {
        try {
            String url = sonarUrl + "/api/qualitygates/project_status?projectKey=" + projectKey;
            HttpEntity<Void> req = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String status = root.path("projectStatus").path("status").asText();
            report.setQualityGateStatus(status);
        } catch (Exception e) {
            report.setQualityGateStatus("UNKNOWN");
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (sonarToken != null && !sonarToken.isBlank()) {
            String encoded = Base64.getEncoder().encodeToString((sonarToken + ":").getBytes());
            headers.set("Authorization", "Basic " + encoded);
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
}