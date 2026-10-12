package com.ezzyhub.report.repository;

import com.ezzyhub.report.model.AnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, Long> {

    /** All records for a user, newest first, excluding heavy JSON blobs */
    @Query("SELECT r FROM AnalysisRecord r WHERE r.userId = :userId ORDER BY r.createdAt DESC")
    List<AnalysisRecord> findByUserIdOrderByCreatedAtDesc(@Param("userId") String userId);

    /** Single record, only if it belongs to the given user (security guard) */
    Optional<AnalysisRecord> findByIdAndUserId(Long id, String userId);
}
