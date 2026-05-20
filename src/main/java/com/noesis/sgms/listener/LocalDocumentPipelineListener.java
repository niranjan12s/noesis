package com.noesis.sgms.listener;

import com.noesis.sgms.service.AssertionExtractionService;
import com.noesis.sgms.service.DashboardMetricsStore;
import com.noesis.sgms.service.GraphComponentService;
import com.noesis.sgms.service.MarkdownChunkingService;
import com.noesis.sgms.service.ModeService;
import com.noesis.sgms.event.PipelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDocumentPipelineListener {

    private final MarkdownChunkingService markdownChunkingService;
    private final AssertionExtractionService assertionExtractionService;
    private final GraphComponentService graphComponentService;
    private final DashboardMetricsStore metricsStore;
    private final ModeService modeService;

    @EventListener(condition = "#event.eventType == 'DOCUMENT_CREATED' || #event.eventType == 'DOCUMENT_UPDATED'")
    public void handleDocumentEvent(PipelineEvent event) {
        if ("bulk".equals(modeService.getCurrentMode())) return;
        log.info("Pipeline listener: {} for document {}", event.getEventType(), event.getDocumentId());
        long start = System.currentTimeMillis();
        markdownChunkingService.processDocumentChunking(event.getDocumentId());
        metricsStore.recordChunkLatency(System.currentTimeMillis() - start);
    }

    @EventListener(condition = "#event.eventType == 'CHUNKING_COMPLETED'")
    public void handleChunkingCompleted(PipelineEvent event) {
        if ("bulk".equals(modeService.getCurrentMode())) return;
        log.info("Pipeline listener: CHUNKING_COMPLETED for document {}", event.getDocumentId());
        long start = System.currentTimeMillis();
        assertionExtractionService.processDocumentAssertions(event.getDocumentId());
        metricsStore.recordChunkLatency(System.currentTimeMillis() - start);
    }

    @EventListener(condition = "#event.eventType == 'ASSERTION_EXTRACTION_COMPLETED'")
    public void handleAssertionExtractionCompleted(PipelineEvent event) {
        if ("bulk".equals(modeService.getCurrentMode())) return;
        log.info("Pipeline listener: ASSERTION_EXTRACTION_COMPLETED for run {}", event.getIngestionRunId());
        long start = System.currentTimeMillis();
        graphComponentService.buildGraphComponents(event.getIngestionRunId());
        metricsStore.recordChunkLatency(System.currentTimeMillis() - start);
    }
}
