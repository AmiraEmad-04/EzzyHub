package com.example.ezzyhubai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
// Renamed from "requirements" — Requirement-Analyzer already owns a table
// with that exact name in the shared ezzyhub_db MySQL database. Prefixed to
// keep this service's own requirement-tracking table distinct now that both
// services persist to the same schema.
@Table(name = "ai_suggestion_requirements")
@Data
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String basePackage;
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String businessLogic;

    @Enumerated(EnumType.STRING)
    private RequirementType type;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}