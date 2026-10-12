package com.ezzyhub.requirementanalyzer.repository;

import com.ezzyhub.requirementanalyzer.model.VerificationReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for persisted MatchReports.
 *
 * findAllSummaries() avoids loading the large report_json blob
 * when only listing reports on the dashboard.
 */
@Repository
public interface VerificationReportRepository extends JpaRepository<VerificationReport, Long> {

    /** Returns the 20 most recent reports, newest first — for the dashboard. */
    List<VerificationReport> findTop20ByOrderByGeneratedAtDesc();

    /** Paginated list for the reports archive page. */
    Page<VerificationReport> findAllByOrderByGeneratedAtDesc(Pageable pageable);

    /**
     * Lightweight summary projection — does NOT load report_json.
     * Use this for listing; fetch by ID when full JSON is needed.
     */
    @Query("SELECT r.id, r.projectName, r.generatedAt, r.totalRequirements, " +
           "r.implementedCount, r.partialCount, r.missingCount, r.coveragePercentage " +
           "FROM VerificationReport r ORDER BY r.generatedAt DESC")
    List<Object[]> findAllSummaries();
}
