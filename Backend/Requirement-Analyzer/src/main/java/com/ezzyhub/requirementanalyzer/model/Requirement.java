package com.ezzyhub.requirementanalyzer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persisted representation of an analyzed requirement.
 *
 * FIX Issue #2: LocalDateTime fields are kept as Java types here;
 * serialization is handled by RequirementSummaryDto which formats them
 * as ISO-8601 strings. The controller never returns this entity directly.
 *
 * FIX Issue #12: added @PreUpdate so updatedAt tracks when a record is modified.
 * createdAt is preserved on UPDATE by the service layer (entity.setCreatedAt(existing.getCreatedAt())).
 */
@Entity
@Table(name = "requirements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_id", nullable = false, unique = true)
    private String reqId;

    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "clean_text", columnDefinition = "TEXT")
    private String cleanText;

    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    @Column(name = "action_verbs", columnDefinition = "TEXT")
    private String actionVerbs;

    @Column(name = "tokens", columnDefinition = "TEXT")
    private String tokens;

    @Column(name = "sentence_embedding", columnDefinition = "LONGTEXT")
    private String sentenceEmbedding;

    @Column(name = "validation_status")
    private String validationStatus;

    @Column(name = "validation_confidence")
    private Double validationConfidence;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
