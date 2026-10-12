package com.example.ezzyhubai.repository;

import com.example.ezzyhubai.entity.GenerationVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GenerationVersionRepository
        extends JpaRepository<GenerationVersion, Long> {

    /**
     * Get all versions of a requirement.
     */
    List<GenerationVersion>
    findByRequirementIdOrderByVersionNumberDesc(
            Long requirementId);

    /**
     * Get a specific version.
     */
    Optional<GenerationVersion>
    findByRequirementIdAndVersionNumber(
            Long requirementId,
            Integer versionNumber);
    List<GenerationVersion>
    findByBatchId(Long batchId);
    /**
     * Get the latest version.
     */
    Optional<GenerationVersion>
    findTopByRequirementIdOrderByVersionNumberDesc(
            Long requirementId);
}