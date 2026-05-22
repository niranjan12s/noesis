package com.noesis.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphUpdateEvent {
    @JsonProperty("ingestion_run_id")
    private String ingestionRunId;

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("nodes_count")
    private int nodesCount;

    @JsonProperty("edges_count")
    private int edgesCount;

    @JsonProperty("assertion_ids")
    private List<String> assertionIds;

    @JsonProperty("created_at")
    private long createdAt;
}
