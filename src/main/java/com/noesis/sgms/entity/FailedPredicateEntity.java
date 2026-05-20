package com.noesis.sgms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "failed_predicates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedPredicateEntity {

    @Id
    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "occurrence_count", nullable = false)
    private Integer occurrenceCount;

    @Column(name = "last_seen_document", nullable = false)
    private String lastSeenDocument;

    @Column(name = "sample_raw_text", columnDefinition = "TEXT")
    private String sampleRawText;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "predicate_group", nullable = false)
    private String predicateGroup;
}
