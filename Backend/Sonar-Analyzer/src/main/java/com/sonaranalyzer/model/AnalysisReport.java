package com.sonaranalyzer.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AnalysisReport {
    private String projectKey;
    private String projectName;
    private String analysisDate;
    private String status; // SUCCESS, FAILED, IN_PROGRESS

    // Summary metrics
    private int totalIssues;
    private int blocker;
    private int critical;
    private int major;
    private int minor;
    private int info;

    private int bugs;
    private int vulnerabilities;
    private int codeSmells;
    private int securityHotspots;

    private String coverage;
    private String duplications;
    private String linesOfCode;

    // Issues grouped by file
    private Map<String, List<SonarIssue>> issuesByFile;

    // All issues flat list
    private List<SonarIssue> allIssues;

    // Quality gate
    private String qualityGateStatus; // OK, WARN, ERROR
}
