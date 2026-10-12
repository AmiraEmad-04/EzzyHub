package com.example.ezzyhubai.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "generation_versions")
@Data
public class GenerationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "batch_id")
    private GenerationBatch batch;
    /**
     * v1, v2, v3...
     */
    @Column(nullable = false)
    private Integer versionNumber;

    /**
     * User feedback:
     * "Add validation"
     * "Generate unit tests"
     */
    @Column(columnDefinition = "TEXT")
    private String userPrompt;

    /**
     * Path of generated zip.
     */
    private String zipPath;

    /**
     * PARTIAL or NOT_IMPLEMENTED
     */
    private String requirementStatus;

    /**
     * Similarity score from matching service.
     */
    private Double similarityScore;

    private LocalDateTime createdAt;

    /**
     * Requirement that owns this version.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id")
    private Requirement requirement;

    /**
     * Previous version.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_version_id")
    private GenerationVersion parentVersion;

    /**
     * Child versions.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "parentVersion")
    private List<GenerationVersion> children = new ArrayList<>();

    /**
     * Files of this version.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "version",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<GeneratedFile> files = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}