package com.example.ezzyhubai.dto;

import lombok.Data;

import java.util.List;

@Data
public class RequirementGenerationDto {

    private String reqId;
    private String requirementText;

    private String status; // PARTIAL or NOT_IMPLEMENTED

    private Double similarityScore;

    private List<RelatedFileDto> relatedFiles;
}