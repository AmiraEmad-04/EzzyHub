package com.sonaranalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SonarIssue {
    private String key;
    private String rule;
    private String severity;
    private String component;
    private String project;
    private Integer line;
    private String status;
    private String message;
    private String type;
    private String effort;
    private String debt;
    private String author;
    private String creationDate;
    private String updateDate;

    // Derived field: just the file path without project prefix
    public String getFilePath() {
        if (component != null && component.contains(":")) {
            return component.substring(component.indexOf(":") + 1);
        }
        return component;
    }
}
