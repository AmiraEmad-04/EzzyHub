package com.example.ezzyhubai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchReportDto {

    // Accept both the raw matching-service report ("requirementMatches") and any
    // report exported from the frontend. Older frontend exports used the key
    // "results"; @JsonAlias keeps those files working too.
    @JsonProperty("requirementMatches")
    @JsonAlias({"results"})
    private List<RequirementMatchDto> requirements;
}