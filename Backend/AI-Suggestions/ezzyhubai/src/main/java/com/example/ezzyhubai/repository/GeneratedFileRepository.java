package com.example.ezzyhubai.repository;

import com.example.ezzyhubai.entity.GeneratedFile;
import com.example.ezzyhubai.entity.GenerationVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedFileRepository
        extends JpaRepository<GeneratedFile, Long> {

    /**
     * Get all files generated for a requirement.
     */
    List<GeneratedFile>
    findByVersionRequirementId(
            Long requirementId);

    /**
     * Get all files belonging to a version.
     */
    List<GeneratedFile>
    findByVersionId(
            Long versionId);

    /**
     * Get files of a version ordered by name.
     */
    List<GeneratedFile>
    findByVersionOrderByFileName(
            GenerationVersion version);
}