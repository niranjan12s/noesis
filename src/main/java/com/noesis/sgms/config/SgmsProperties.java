package com.noesis.sgms.config;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;


import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Strongly-typed configuration properties for SGMS, bound from the {@code sgms.*} namespace.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "sgms")
public class SgmsProperties {

    @Valid
    @NotNull
    private OpenSearchProperties opensearch = new OpenSearchProperties();

    @Valid @NotNull
    private LlmProperties llm = new LlmProperties();

    @Valid @NotNull
    private WatchProperties watch = new WatchProperties();

    @Valid @NotNull
    private TraversalProperties traversal = new TraversalProperties();

    @Valid @NotNull
    private ChunkerProperties chunker = new ChunkerProperties();

    @Valid @NotNull
    private BulkProperties bulk = new BulkProperties();

    // ── Nested property classes ────────────────────────────────────────────

    @Data
    public static class OpenSearchProperties {
        @NotBlank private String host = "localhost";
        @Min(1)   private int port    = 9200;
        @NotBlank private String scheme = "http";
    }

    @Data
    public static class LlmProperties {
        @NotBlank private String baseUrl        = "http://localhost:11434";
        @NotBlank private String model          = "llama3.2:1b";
        @NotBlank private String embeddingModel = "llama3.2:1b";
        @Min(1)   private int timeoutSeconds    = 120;
        @Valid @NotNull
        private RateLimiterProperties rateLimiter = new RateLimiterProperties();
    }

    @Data
    public static class RateLimiterProperties {
        private boolean enabled = false;
        @Min(1) private int maxCallsPerMinute = 5;
    }

    @Data
    public static class WatchProperties {
        @NotBlank private String dir = "./noesis";
        private List<String> extensions = List.of(".md", ".txt", ".xlsx");
    }

    @Data
    public static class TraversalProperties {
        @Min(1) private int maxDepth       = 3;
        @Min(1) private int cacheTtlSeconds = 300;
    }

    @Data
    public static class ChunkerProperties {
        @Min(64)  private int textWindowSize    = 512;
        @Min(0)   private int textWindowOverlap = 64;
    }

    @Data
    public static class BulkProperties {
        private int chunkBatchSize = 50;
        private int osRefreshIntervalSeconds = 30;
        private int llmConcurrency = 8;
        private int pgBatchSize = 500;
        private int edgeBulkBatchSize = 2000;
        private int osBulkPayloadMaxBytes = 15728640;
        private int kafkaConsumptionBatchSize = 200;
        private int heartbeatIntervalSeconds = 5;
        private int heartbeatTimeoutSeconds = 15;
        private int workerCheckIntervalSeconds = 10;
    }
}
