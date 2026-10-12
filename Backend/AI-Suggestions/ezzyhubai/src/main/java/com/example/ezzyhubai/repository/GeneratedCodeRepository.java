package com.example.ezzyhubai.repository;

import com.example.ezzyhubai.entity.GeneratedCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
public interface GeneratedCodeRepository
        extends JpaRepository<GeneratedCode, Long> {

    List<GeneratedCode> findByRequirementId(Long requirementId);

    List<GeneratedCode> findByVersionId(Long versionId);


}