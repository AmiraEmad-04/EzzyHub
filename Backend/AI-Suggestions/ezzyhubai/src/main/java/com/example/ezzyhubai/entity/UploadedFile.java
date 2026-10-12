package com.example.ezzyhubai.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "uploaded_files")
@Data
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;

    private String filePath;

    private String fileType;
    @ManyToOne
    @JoinColumn(name = "version_id")
    private GenerationVersion version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id")
    private Requirement requirement;
}