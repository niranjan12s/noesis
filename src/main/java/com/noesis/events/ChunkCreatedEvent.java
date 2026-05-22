package com.noesis.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka event published to {@code chunk.created.events} for each text chunk
 * produced by the Chunking service from a source document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkCreatedEvent {

    /** Unique chunk identifier (UUID). */
    @JsonProperty("chunk_id")
    private String chunkId;

    /** ID of the originating document. */
    @JsonProperty("document_id")
    private String documentId;

    /** Breadcrumb section path within the document. */
    @JsonProperty("section_path")
    private String sectionPath;

    /** Raw text content of this chunk. */
    @JsonProperty("text")
    private String text;

    /** ID of the enclosing parent chunk, or {@code null} for root chunks. */
    @JsonProperty("parent_chunk_id")
    private String parentChunkId;

    /** Zero-based sequential position within the document. */
    @JsonProperty("order_index")
    private int orderIndex;

    /** Epoch millis when this chunk was created. */
    @JsonProperty("created_at")
    private long createdAt;
}
