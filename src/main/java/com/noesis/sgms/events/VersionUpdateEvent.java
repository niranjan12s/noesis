package com.noesis.sgms.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka event published to {@code version.update.events} after the OpenSearch
 * Writer successfully persists a graph update batch.
 *
 * <p>Acts as a durable audit trail for every version bump in the knowledge graph.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VersionUpdateEvent {

    /** ID of the originating document. */
    @JsonProperty("document_id")
    private String documentId;

    /** New active version tag applied to this document's nodes and edges. */
    @JsonProperty("version")
    private String version;

    /** Number of nodes written in this batch. */
    @JsonProperty("node_count")
    private int nodeCount;

    /** Number of edges written in this batch. */
    @JsonProperty("edge_count")
    private int edgeCount;

    /** Epoch millis when the version was committed. */
    @JsonProperty("committed_at")
    private long committedAt;
}
