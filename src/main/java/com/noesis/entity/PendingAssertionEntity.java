package com.noesis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pending_assertions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAssertionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "ingestion_run_id", nullable = false)
    private UUID ingestionRunId;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "predicate", nullable = false)
    private String predicate;

    @Column(name = "object", nullable = false)
    private String object;

    @Column(name = "raw_text", columnDefinition = "TEXT", nullable = false)
    private String rawText;

    @Column(name = "normalized_text", nullable = false)
    private String normalizedText;

    @Column(name = "attributes", columnDefinition = "TEXT")
    private String attributes; // JSON string representing attributes Map

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
