package com.noesis.sgms.config;

/**
 * Central registry of all Kafka topic names used by SGMS.
 *
 * <p>Each constant corresponds to a stage in the ingestion / enrichment pipeline
 * as defined in the SGMS architecture specification.</p>
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    /** Fired for each chunk produced by the Chunking service. */
    public static final String CHUNK_CREATED_EVENTS = "chunk.created.events";

    /** Fired for each semantic assertion extracted by the LLM compiler. */
    public static final String ASSERTION_GENERATED_EVENTS = "assertion.generated.events";

    /** Fired after graph nodes and edges are built from assertions. */
    public static final String GRAPH_UPDATE_EVENTS = "graph.update.events";

    /** Fired when semantic clustering produces a new cluster. */
    public static final String CLUSTER_UPDATE_EVENTS = "cluster.update.events";

    /** Fired after OpenSearch writes complete, carrying a new version pointer. */
    public static final String VERSION_UPDATE_EVENTS = "version.update.events";
}
