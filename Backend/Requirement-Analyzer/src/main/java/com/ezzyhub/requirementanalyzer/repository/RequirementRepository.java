package com.ezzyhub.requirementanalyzer.repository;

import com.ezzyhub.requirementanalyzer.model.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Requirement entities.
 *
 * FIX: Added findByCleanText so the service can detect duplicate requirement
 * text even when requirements have different auto-generated IDs.
 * This prevents the same text being inserted multiple times as
 * REQ-AUTO-2, REQ-AUTO-10, REQ-AUTO-11, etc.
 */
@Repository
public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    /** Look up by the business key (requirement ID string, e.g. "REQ-AUTH-1"). */
    Optional<Requirement> findByReqId(String reqId);

    boolean existsByReqId(String reqId);

    /**
     * FIX: Look up by clean (ID-stripped) text to detect content duplicates.
     * Used by the service before inserting a new auto-ID requirement.
     *
     * Comparison is case-insensitive (LOWER on both sides) so minor casing
     * differences don't create extra rows.
     */
    @Query("SELECT r FROM Requirement r WHERE LOWER(r.cleanText) = LOWER(:cleanText)")
    Optional<Requirement> findByCleanTextIgnoreCase(@Param("cleanText") String cleanText);

    /**
     * Returns all requirements that share the same reqId prefix.
     * Useful for admin tooling / duplicate cleanup.
     */
    @Query("SELECT r FROM Requirement r WHERE r.reqId LIKE :prefix% ORDER BY r.id DESC")
    List<Requirement> findByReqIdStartingWith(@Param("prefix") String prefix);
}