package com.noesis.sgms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "absolute_path", nullable = false, unique = true)
    private String absolutePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DocumentStatus status;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "checksum")
    private String checksum;

    @Column(name = "ingestion_run_id")
    private String ingestionRunId;

    @Column(name = "total_assertions")
    private Integer totalAssertions;

    @Column(name = "indexed_assertions")
    private Integer indexedAssertions;

    @Column(name = "indexed_edges")
    private Integer indexedEdges;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "parsed_at")
    private Instant parsedAt;

    @Column(name = "marked_for_deletion_at")
    private Instant markedForDeletionAt;

    @Column(name = "project_root", length = 255)
    private String projectRoot;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
        totalAssertions = 0;
        indexedAssertions = 0;
        indexedEdges = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
