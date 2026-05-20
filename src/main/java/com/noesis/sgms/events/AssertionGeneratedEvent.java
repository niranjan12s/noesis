package com.noesis.sgms.events;

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
public class AssertionGeneratedEvent {
    @JsonProperty("assertion_id")
    private String assertionId;

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("ingestion_run_id")
    private String ingestionRunId;

    @JsonProperty("chunk_id")
    private String chunkId;

    @JsonProperty("subject")
    private String subject;

    @JsonProperty("predicate")
    private String predicate;

    @JsonProperty("object")
    private String object;

    @JsonProperty("raw_text")
    private String rawText;

    @JsonProperty("confidence")
    private double confidence;

    @JsonProperty("subject_node_id")
    private String subjectNodeId;

    @JsonProperty("object_node_id")
    private String objectNodeId;

    @JsonProperty("created_at")
    private long createdAt;
}
