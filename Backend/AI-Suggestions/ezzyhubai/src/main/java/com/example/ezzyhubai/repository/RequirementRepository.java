package com.example.ezzyhubai.repository;

import com.example.ezzyhubai.entity.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RequirementRepository
        extends JpaRepository<Requirement, Long>
{
    Optional<Requirement> findByName(
            String name);
}