package com.example.ezzyhubai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequirementMatchDto {

    @JsonProperty("reqText")
    private String requirementText;

    private String reqId;
    private String status;
    private Double score;

    @JsonProperty("topChunks")
    private List<MatchedFileDto> matchedFiles;
}