package com.example.ezzyhubai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "generated_files")
@Data
public class GeneratedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * UserController.java
     */
    @Column(nullable = false)
    private String fileName;

    /**
     * src/main/java/com/example/controller/UserController.java
     */
    @Column(length = 1000)
    private String filePath;

    /**
     * New file or modified existing file.
     */
    private Boolean modified = false;

    /**
     * AI generated source code.
     */

    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * Original file before modification.
     * Useful for diff view.
     */

    @Column(columnDefinition = "TEXT")
    private String originalContent;

    /**
     * Controller
     * Service
     * Repository
     * Entity
     * DTO
     * Test
     */
    private String fileType;

    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id")
    private GenerationVersion version;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}