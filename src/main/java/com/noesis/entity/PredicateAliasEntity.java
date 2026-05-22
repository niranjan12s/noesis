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

@Entity
@Table(name = "predicate_aliases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredicateAliasEntity {

    /** The raw predicate as the LLM produced it (uppercase). Primary key — one alias per source. */
    @Id
    @Column(name = "source", nullable = false)
    private String source;

    /** The canonical active predicate this maps to (must exist in active_predicates). */
    @Column(name = "target", nullable = false)
    private String target;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** "USER" for manual maps, reserved for "AUTO" if we ever add automated mapping. */
    @Column(name = "created_by", nullable = false)
    @Builder.Default
    private String createdBy = "USER";
}
