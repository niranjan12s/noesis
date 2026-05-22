package com.noesis.config;

import com.noesis.service.MarkdownChunkingService;
import com.noesis.service.GraphComponentService;
import com.noesis.service.AssertionExtractionService;
import com.noesis.service.RetryablePipelineStage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class PipelineStageConfig {

    private final MarkdownChunkingService markdownChunkingService;
    private final AssertionExtractionService assertionExtractionService;
    private final GraphComponentService graphComponentService;

    @Bean
    public RetryablePipelineStage chunkingStage() {
        return new RetryablePipelineStage() {
            @Override public String stageName() { return "CHUNKING"; }
            @Override public void execute(String documentId) {
                markdownChunkingService.processDocumentChunking(documentId);
            }
        };
    }

    @Bean
    public RetryablePipelineStage assertionExtractionStage() {
        return new RetryablePipelineStage() {
            @Override public String stageName() { return "PROCESSING_ASSERTIONS"; }
            @Override public void execute(String documentId) {
                assertionExtractionService.processDocumentAssertions(documentId);
            }
        };
    }

    @Bean
    public RetryablePipelineStage graphBuildStage() {
        return new RetryablePipelineStage() {
            @Override public String stageName() { return "PROCESSING_GRAPH"; }
            @Override public void execute(String documentId) {
                graphComponentService.retryBuildGraphByDocumentId(documentId);
            }
        };
    }
}
