package com.noesis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "nodes", indexes = {
    @Index(name = "idx_nodes_semantic_checksum", columnList = "semantic_checksum", unique = true),
    @Index(name = "idx_nodes_normalized_name", columnList = "normalized_name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "canonical_name", nullable = false, columnDefinition = "TEXT")
    private String canonicalName;

    @Column(name = "normalized_name", nullable = false, columnDefinition = "TEXT")
    private String normalizedName;

    @Column(name = "semantic_checksum", nullable = false, columnDefinition = "TEXT")
    private String semanticChecksum;

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
