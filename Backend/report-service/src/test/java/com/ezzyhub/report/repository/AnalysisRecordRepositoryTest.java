package com.ezzyhub.report.repository;

import com.ezzyhub.report.model.AnalysisRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA slice test for {@link AnalysisRecordRepository} against the in-memory H2 DB
 * configured in src/test/resources/application.properties.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnalysisRecordRepositoryTest {

    @Autowired
    private AnalysisRecordRepository repo;

    private AnalysisRecord record(String userId, String project, Instant createdAt) {
        AnalysisRecord r = new AnalysisRecord();
        r.setUserId(userId);
        r.setProjectName(project);
        r.setAnalysisType("CODE_ANALYSIS");
        r.setStatus("success");
        r.setCreatedAt(createdAt);
        return r;
    }

    @Test
    void saveAndFindById_roundTrip() {
        AnalysisRecord saved = repo.save(record("alice", "proj-a", Instant.now()));
        assertThat(saved.getId()).isNotNull();

        Optional<AnalysisRecord> found = repo.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getProjectName()).isEqualTo("proj-a");
        assertThat(found.get().getUserId()).isEqualTo("alice");
    }

    @Test
    void findByUserId_returnsOnlyThatUser_newestFirst() {
        Instant now = Instant.now();
        repo.save(record("alice", "older", now.minusSeconds(60)));
        repo.save(record("alice", "newer", now));
        repo.save(record("bob", "bob-proj", now));

        List<AnalysisRecord> aliceRecords = repo.findByUserIdOrderByCreatedAtDesc("alice");

        assertThat(aliceRecords).hasSize(2);
        assertThat(aliceRecords).extracting(AnalysisRecord::getProjectName)
                .containsExactly("newer", "older");
    }

    @Test
    void findByIdAndUserId_enforcesOwnership() {
        AnalysisRecord saved = repo.save(record("alice", "proj", Instant.now()));
        assertThat(repo.findByIdAndUserId(saved.getId(), "alice")).isPresent();
        assertThat(repo.findByIdAndUserId(saved.getId(), "bob")).isEmpty();
    }

    @Test
    void delete_removesRecord() {
        AnalysisRecord saved = repo.save(record("alice", "proj", Instant.now()));
        repo.delete(saved);
        assertThat(repo.findById(saved.getId())).isEmpty();
    }
}
