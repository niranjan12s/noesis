package com.noesis.sgms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assertions", indexes = {
    @Index(name = "idx_assertions_run_id", columnList = "ingestion_run_id"),
    @Index(name = "idx_assertions_doc_id", columnList = "document_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssertionEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "ingestion_run_id", nullable = false)
    private UUID ingestionRunId;

    @Column(name = "subject_node_id")
    private UUID subjectNodeId;

    @Column(name = "object_node_id")
    private UUID objectNodeId;

    @Column(name = "raw_text", nullable = false, columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "normalized_text", nullable = false, columnDefinition = "TEXT")
    private String normalizedText;

    @Column(name = "subject", nullable = false, columnDefinition = "TEXT")
    private String subject;

    @Column(name = "predicate", nullable = false, columnDefinition = "TEXT")
    private String predicate;

    @Column(name = "object", nullable = false, columnDefinition = "TEXT")
    private String object;

    // Mapping attributes as JSON. Jackson and Hibernate 6 handle the serialization/deserialization.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private java.util.Map<String, Object> attributes;

    @Column(name = "extraction_model", columnDefinition = "TEXT")
    private String extractionModel;

    @Column(name = "semantic_checksum", nullable = false, columnDefinition = "TEXT")
    private String semanticChecksum;

    @Column(name = "evidence_checksum", nullable = false, columnDefinition = "TEXT")
    private String evidenceChecksum;

    @Column(name = "project_root", length = 255)
    private String projectRoot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
