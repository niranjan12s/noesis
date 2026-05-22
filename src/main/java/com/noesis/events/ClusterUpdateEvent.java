package com.noesis.events;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka event published to {@code cluster.update.events} when the Clustering
 * service produces a new semantic cluster.
 *
 * <p>Clusters are advisory — they never automatically mutate the graph.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterUpdateEvent {

    /** Unique cluster identifier (UUID). */
    @JsonProperty("cluster_id")
    private String clusterId;

    /** Assertion IDs that are members of this cluster. */
    @JsonProperty("members")
    private List<String> members;

    /** Clustering confidence score [0.0 – 1.0]. */
    @JsonProperty("confidence")
    private float confidence;

    /**
     * Human-readable suggestions for follow-up actions
     * (e.g. "merge nodes X and Y", "expand alias for Z").
     */
    @JsonProperty("suggestions")
    private List<String> suggestions;

    /** Epoch millis when this cluster was computed. */
    @JsonProperty("computed_at")
    private long computedAt;
}
