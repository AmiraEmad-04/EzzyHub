package com.example.ezzyhubai.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerationRequest {
    private String projectId;
    private List<RequirementGenerationDto> requirements;
}