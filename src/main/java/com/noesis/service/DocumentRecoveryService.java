package com.noesis.service;

import com.noesis.entity.ChunkEntity;
import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.repository.ChunkJpaRepository;
import com.noesis.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRecoveryService {

    private final DocumentRepository documentRepository;
    private final MarkdownChunkingService markdownChunkingService;
    private final AssertionExtractionService assertionExtractionService;
    private final GraphComponentService graphComponentService;
    private final NoesisStateService noesisStateService;
    private final ChunkJpaRepository chunkJpaRepository;
    private final PredicateSeedService predicateSeedService;

    @PostConstruct
    public void init() {
        new Thread(this::recoverStuckDocuments, "pipeline-recovery").start();
    }

    public void recoverStuckDocuments() {
        List<DocumentEntity> docs = documentRepository.findAll();
        int recovered = 0;
        for (DocumentEntity doc : docs) {
            if (isTerminal(doc.getStatus())) continue;
            log.info("Pipeline recovery: document '{}' ({}) is in state {}. Resuming...",
                    doc.getName(), doc.getId(), doc.getStatus());
            recoverDocument(doc);
            recovered++;
        }
        if (recovered == 0) {
            log.info("Pipeline recovery: no stuck documents found");
        } else {
            log.info("Pipeline recovery: resumed {} document(s)", recovered);
        }
    }

    public void recoverDocument(DocumentEntity doc) {
        try {
            switch (doc.getStatus()) {
                case DISCOVERED:
                    doc.setStatus(DocumentStatus.QUEUED);
                    documentRepository.save(doc);
                    markdownChunkingService.processDocumentChunking(doc.getId());
                    break;

                case QUEUED:
                    markdownChunkingService.processDocumentChunking(doc.getId());
                    break;

                case PROCESSING_ASSERTIONS:
                case PROCESSING_GRAPH:
                case INDEXING_ASSERTIONS:
                case INDEXING_EDGES:
                case FAILED_RETRYABLE:
                    // Check if chunks exist; if not, re-chunk first
                    List<ChunkEntity> existingChunks = chunkJpaRepository.findByDocumentId(UUID.fromString(doc.getId()));
                    if (existingChunks.isEmpty()) {
                        log.info("No chunks found for document '{}'. Re-chunking first.", doc.getName());
                        doc.setStatus(DocumentStatus.QUEUED);
                        documentRepository.save(doc);
                        markdownChunkingService.processDocumentChunking(doc.getId());
                        doc = documentRepository.findById(doc.getId()).orElse(doc);
                    }
                    // Reset status so processDocumentAssertions does not skip
                    doc.setStatus(DocumentStatus.PROCESSING_ASSERTIONS);
                    documentRepository.save(doc);
                    noesisStateService.resetCompletedChunks(doc.getAbsolutePath());
                    assertionExtractionService.processDocumentAssertions(doc.getId());
                    break;

                case RETRYING:
                    String relativePath = noesisStateService.getRelativePathString(doc.getAbsolutePath());
                    Map<String, Object> state = noesisStateService.getDocumentStateByPath(relativePath);
                    doc.setStatus(DocumentStatus.PROCESSING_ASSERTIONS);
                    documentRepository.save(doc);
                    if (state != null) {
                        String currentStage = (String) state.get("current_stage");
                        if ("CHUNKING".equals(currentStage) || "CHUNKING_FAILED".equals(currentStage)) {
                            markdownChunkingService.processDocumentChunking(doc.getId());
                        } else {
                            noesisStateService.resetCompletedChunks(doc.getAbsolutePath());
                            assertionExtractionService.processDocumentAssertions(doc.getId());
                        }
                    } else {
                        noesisStateService.resetCompletedChunks(doc.getAbsolutePath());
                        assertionExtractionService.processDocumentAssertions(doc.getId());
                    }
                    break;

                default:
                    log.warn("Pipeline recovery: unhandled status {} for document {}", doc.getStatus(), doc.getId());
            }
        } catch (Exception e) {
            log.error("Pipeline recovery failed for document {}: {}", doc.getId(), e.getMessage(), e);
        }
    }

    private static boolean isTerminal(DocumentStatus status) {
        return status == DocumentStatus.QUERYABLE || status == DocumentStatus.FAILED_FATAL;
    }
}