package com.noesis.sgms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chunks", indexes = {
    @Index(name = "idx_chunks_doc_id_ver", columnList = "document_id, document_version")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "section_path", nullable = false, columnDefinition = "TEXT")
    private String sectionPath;

    @Column(name = "heading", columnDefinition = "TEXT")
    private String heading;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "normalized_content", nullable = false, columnDefinition = "TEXT")
    private String normalizedContent;

    @Column(name = "chunk_checksum", nullable = false, columnDefinition = "TEXT")
    private String chunkChecksum;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "token_estimate")
    private Integer tokenEstimate;

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
