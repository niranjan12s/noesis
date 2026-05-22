package com.noesis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "edges", indexes = {
    @Index(name = "idx_edges_semantic_checksum", columnList = "semantic_checksum", unique = true),
    @Index(name = "idx_edges_ingestion_run_id", columnList = "ingestion_run_id"),
    @Index(name = "idx_edges_from_pred_to", columnList = "from_node_id, predicate, to_node_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "from_node_id", nullable = false)
    private UUID fromNodeId;

    @Column(name = "predicate", nullable = false, columnDefinition = "TEXT")
    private String predicate;

    @Column(name = "to_node_id", nullable = false)
    private UUID toNodeId;

    @Column(name = "semantic_checksum", nullable = false, columnDefinition = "TEXT")
    private String semanticChecksum;

    @Column(name = "ingestion_run_id", nullable = false)
    private UUID ingestionRunId;

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
