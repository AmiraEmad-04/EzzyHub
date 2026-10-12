package com.example.ezzyhubai.repository;

import com.example.ezzyhubai.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadedFileRepository
        extends JpaRepository<UploadedFile, Long> {

    List<UploadedFile> findByRequirementId(Long requirementId);

    List<UploadedFile> findByVersionId(Long versionId);
}