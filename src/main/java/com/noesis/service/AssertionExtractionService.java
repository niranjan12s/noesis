package com.noesis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.client.LlmClient;
import com.noesis.dto.AssertionExtractionResponse;
import com.noesis.entity.AssertionEntity;
import com.noesis.entity.ChunkEntity;
import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.event.DocumentEvent;
import com.noesis.event.DocumentEventType;
import com.noesis.events.AssertionGeneratedEvent;
import com.noesis.producer.BulkAssertionProducer;
import com.noesis.producer.DocumentEventProducer;
import com.noesis.repository.AssertionJpaRepository;
import com.noesis.repository.ChunkJpaRepository;
import com.noesis.repository.DocumentRepository;
import com.noesis.event.PipelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssertionExtractionService {

    private final ChunkJpaRepository chunkJpaRepository;
    private final AssertionJpaRepository assertionJpaRepository;
    private final DocumentRepository documentRepository;
    private final DocumentEventProducer documentEventProducer;
    private final LlmClient llmClient;
    private final AssertionValidationService validationService;
    private final ObjectMapper objectMapper;
    private final NoesisStateService noesisStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final PredicateService predicateService;
    private final ModeService modeService;
    private final BulkAssertionProducer bulkAssertionProducer;
    private final BulkProgressStore bulkProgressStore;
    private final OutboxService outboxService;

    @Value("${noesis.llm.model:llama3.2:1b}")
    private String extractionModel;

    @Value("${noesis.auto-approve.threshold:0}")
    private int autoApproveThreshold;

    @Transactional
    public void processDocumentAssertions(String documentIdStr) {
        log.info("Starting assertion extraction for document: {}", documentIdStr);
        UUID documentId = UUID.fromString(documentIdStr);

        DocumentEntity document = documentRepository.findById(documentIdStr).orElse(null);
        if (document == null) {
            log.error("Document not found for assertion extraction: {}", documentIdStr);
            return;
        }

        if (document.getStatus() == DocumentStatus.QUERYABLE || document.getStatus() == DocumentStatus.PROCESSING_GRAPH) {
            log.info("Document {} is already in stage {}. Skipping assertion extraction.", documentIdStr, document.getStatus());
            return;
        }

        String relativePath = noesisStateService.getRelativePathString(document.getAbsolutePath());

        // Update stage in SQLite
        noesisStateService.upsertDocumentState(
                relativePath,
                DocumentStatus.PROCESSING_ASSERTIONS,
                "PROCESSING_ASSERTIONS",
                0,
                null,
                null,
                String.join(",", noesisStateService.getCompletedChunkIds(relativePath)),
                0,
                document.getChecksum()
        );

        try {
            List<ChunkEntity> chunks = chunkJpaRepository.findByDocumentIdAndDocumentVersion(documentId, document.getVersion());
            
            // Sync total chunks count
            int totalChunks = chunks.size();
            Map<String, Object> sqliteState = noesisStateService.getDocumentStateByPath(relativePath);
            int currentRetryCount = sqliteState != null ? (Integer) sqliteState.get("retry_count") : 0;
            
            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.PROCESSING_ASSERTIONS,
                    "PROCESSING_ASSERTIONS",
                    currentRetryCount,
                    null,
                    null,
                    String.join(",", noesisStateService.getCompletedChunkIds(relativePath)),
                    totalChunks,
                    document.getChecksum()
            );

            List<AssertionEntity> allAssertions = new ArrayList<>();
            List<String> completedChunkIds = noesisStateService.getCompletedChunkIds(relativePath);

            // Fetch previous version chunks for incremental diffing
            List<ChunkEntity> previousChunks = new ArrayList<>();
            if (document.getVersion() > 1) {
                previousChunks = chunkJpaRepository.findByDocumentIdAndDocumentVersion(documentId, document.getVersion() - 1);
            }

            // Pre-load existing assertion checksums for dedup
            List<String> existingChecksums = assertionJpaRepository.findChecksumsByDocumentId(documentId);

            for (ChunkEntity chunk : chunks) {
                String chunkIdStr = chunk.getId().toString();
                
                // PARTIAL PROGRESS PRESERVATION check
                if (completedChunkIds.contains(chunkIdStr)) {
                    log.info("Partial Progress: Chunk {} is already completed. Skipping extraction.", chunkIdStr);
                    continue;
                }

                // Check if chunk is unchanged from the previous version
                ChunkEntity matchedPrevChunk = null;
                for (ChunkEntity pc : previousChunks) {
                    if (pc.getChunkChecksum().equals(chunk.getChunkChecksum())) {
                        matchedPrevChunk = pc;
                        break;
                    }
                }

                if (matchedPrevChunk != null) {
                    log.info("Chunk Diffing: Chunk {} is unchanged (checksum matched previous version chunk {}). Reusing assertions without calling LLM.", 
                            chunkIdStr, matchedPrevChunk.getId());
                    List<AssertionEntity> prevAssertions = assertionJpaRepository.findByChunkId(matchedPrevChunk.getId());
                    List<AssertionEntity> clonedAssertions = new ArrayList<>();
                    for (AssertionEntity pa : prevAssertions) {
                        AssertionEntity cloned = AssertionEntity.builder()
                                .id(UUID.randomUUID())
                                .chunkId(chunk.getId())
                                .documentId(chunk.getDocumentId())
                                .documentVersion(document.getVersion())
                                .ingestionRunId(document.getIngestionRunId() != null ? UUID.fromString(document.getIngestionRunId()) : null)
                                .subjectNodeId(null) // Will be updated during the graph building phase
                                .objectNodeId(null)
                                .rawText(pa.getRawText())
                                .normalizedText(pa.getNormalizedText())
                                .subject(pa.getSubject())
                                .predicate(pa.getPredicate())
                                .object(pa.getObject())
                                .attributes(pa.getAttributes() != null ? new java.util.HashMap<>(pa.getAttributes()) : null)
                                .extractionModel(pa.getExtractionModel())
                                .semanticChecksum(pa.getSemanticChecksum())
                                .evidenceChecksum(pa.getEvidenceChecksum())
                                .projectRoot(pa.getProjectRoot())
                                .createdAt(Instant.now())
                                .build();
                        
                        if (!existingChecksums.contains(cloned.getSemanticChecksum())) {
                            clonedAssertions.add(cloned);
                            existingChecksums.add(cloned.getSemanticChecksum());
                        }
                    }

                    assertionJpaRepository.saveAll(clonedAssertions);
                    allAssertions.addAll(clonedAssertions);

                    // Add this chunk to the completed list in SQLite
                    noesisStateService.addCompletedChunk(relativePath, chunkIdStr);
                    continue;
                }

                // Attempt to call the LLM
                List<AssertionExtractionResponse> extracted = llmClient.extractAssertions(
                        chunk.getSectionPath(),
                        chunk.getContent()
                );

                List<AssertionEntity> chunkAssertions = new ArrayList<>();
                for (AssertionExtractionResponse response : extracted) {
                    if (validationService.isValid(response, chunk.getContent())) {
                        AssertionEntity entity = buildAssertionEntity(response, chunk, document);
                        if (!existingChecksums.contains(entity.getSemanticChecksum())) {
                            chunkAssertions.add(entity);
                            existingChecksums.add(entity.getSemanticChecksum());
                        }
                    } else {
                        log.warn("Rejected invalid assertion for chunk {}: {}", chunkIdStr, response);
                        if (validationService.isPredicateInvalidOnly(response, chunk.getContent())) {
                            try {
                                UUID runId = document.getIngestionRunId() != null ? UUID.fromString(document.getIngestionRunId()) : null;
                                predicateService.recordFailedPredicate(
                                        response.getPredicate(),
                                        relativePath,
                                        response.getRawText(),
                                        response,
                                        chunk.getId(),
                                        chunk.getDocumentId(),
                                        document.getVersion(),
                                        runId
                                );
                            } catch (Exception ex) {
                                log.error("Failed to record unrecognized predicate for failed assertion", ex);
                            }
                        }
                    }
                }

                assertionJpaRepository.saveAll(chunkAssertions);
                allAssertions.addAll(chunkAssertions);

                // Add this chunk to the completed list in SQLite
                noesisStateService.addCompletedChunk(relativePath, chunkIdStr);
            }

            document.setTotalAssertions(document.getTotalAssertions() + allAssertions.size());
            document.setStatus(DocumentStatus.PROCESSING_GRAPH);
            documentRepository.save(document);

            if ("bulk".equals(modeService.getCurrentMode()) && modeService.isBulkJobActive()) {
                bulkProgressStore.addAssertions(allAssertions.size());
            }

            // Successfully processed all chunks! Move to PROCESSING_GRAPH in SQLite
            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.PROCESSING_GRAPH,
                    "PROCESSING_GRAPH",
                    0, // reset retry count on success
                    null,
                    null,
                    String.join(",", noesisStateService.getCompletedChunkIds(relativePath)),
                    totalChunks,
                    document.getChecksum()
            );

            emitEvent(document, DocumentEventType.ASSERTION_EXTRACTION_COMPLETED);
            log.info("Completed assertion extraction for document: {}. Saved {} assertions.", documentIdStr, allAssertions.size());

            // Trigger downstream in-process step synchronously
            eventPublisher.publishEvent(PipelineEvent.builder()
                    .eventType("ASSERTION_EXTRACTION_COMPLETED")
                    .documentId(document.getId())
                    .ingestionRunId(document.getIngestionRunId())
                    .build());

            // In bulk mode, produce assertions to Kafka topic
            if ("bulk".equals(modeService.getCurrentMode()) && modeService.isBulkJobActive()) {
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                for (AssertionEntity a : allAssertions) {
                                    outboxService.publish("assertion.generated.events", a.getDocumentId().toString(),
                                            AssertionGeneratedEvent.builder()
                                                    .assertionId(a.getId().toString())
                                                    .documentId(a.getDocumentId().toString())
                                                    .ingestionRunId(a.getIngestionRunId() != null ? a.getIngestionRunId().toString() : "")
                                                    .chunkId(a.getChunkId() != null ? a.getChunkId().toString() : "")
                                                    .subject(a.getSubject())
                                                    .predicate(a.getPredicate())
                                                    .object(a.getObject())
                                                    .rawText(a.getRawText())
                                                    .createdAt(System.currentTimeMillis())
                                                    .build(),
                                            "ASSERTION", a.getId().toString());
                                }
                                log.info("Published {} assertions to {} after commit", allAssertions.size(), "assertion.generated.events");
                            }
                        }
                    );
                } else {
                    for (AssertionEntity a : allAssertions) {
                        outboxService.publish("assertion.generated.events", a.getDocumentId().toString(),
                                AssertionGeneratedEvent.builder()
                                        .assertionId(a.getId().toString())
                                        .documentId(a.getDocumentId().toString())
                                        .ingestionRunId(a.getIngestionRunId() != null ? a.getIngestionRunId().toString() : "")
                                        .chunkId(a.getChunkId() != null ? a.getChunkId().toString() : "")
                                        .subject(a.getSubject())
                                        .predicate(a.getPredicate())
                                        .object(a.getObject())
                                        .rawText(a.getRawText())
                                        .createdAt(System.currentTimeMillis())
                                        .build(),
                                "ASSERTION", a.getId().toString());
                    }
                    log.info("Published {} assertions to {}", allAssertions.size(), "assertion.generated.events");
                }
            }

            // Auto-approve predicates meeting threshold after extraction completes
            if (autoApproveThreshold > 0) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        int approved = predicateService.autoApprovePredicates(autoApproveThreshold);
                        if (approved > 0) {
                            log.info("Auto-approved {} predicates (threshold >= {}) after document extraction", approved, autoApproveThreshold);
                        }
                    } catch (Exception e) {
                        log.warn("Auto-approve sweep failed after extraction: {}", e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            log.error("Failed assertion extraction for document: {}", documentIdStr, e);
            handleExtractionFailure(document, relativePath, e);
        }
    }

    private void handleExtractionFailure(DocumentEntity document, String relativePath, Exception e) {
        // Read current state from SQLite to get retry count
        Map<String, Object> sqliteState = noesisStateService.getDocumentStateByPath(relativePath);
        int retryCount = 0;
        int totalChunks = 0;
        String completedChunks = "";
        if (sqliteState != null) {
            retryCount = (Integer) sqliteState.get("retry_count");
            totalChunks = (Integer) sqliteState.get("total_chunks");
            completedChunks = (String) sqliteState.get("completed_chunks");
        }

        retryCount++;

        // Write retry logs to file system
        writeRetryLogFile(document.getId(), retryCount, e);

        if (retryCount <= 5) {
            // Calculate exponential backoff: 15 * 2^(retry_count - 1) seconds
            long delaySeconds = 15L * (1L << (retryCount - 1));
            Instant nextRetryAt = Instant.now().plusSeconds(delaySeconds);

            log.warn("Assertion extraction failed for {}. Scheduling retry #{} in {} seconds (at {}).", 
                    relativePath, retryCount, delaySeconds, nextRetryAt);

            document.setStatus(DocumentStatus.RETRYING);
            documentRepository.save(document);

            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.RETRYING,
                    "PROCESSING_ASSERTIONS",
                    retryCount,
                    e.getMessage(),
                    nextRetryAt,
                    completedChunks,
                    totalChunks,
                    document.getChecksum()
            );

            emitEvent(document, DocumentEventType.ASSERTION_EXTRACTION_FAILED);
        } else {
            // Exceeded maximum 5 retries. Fail Fatally.
            log.error("Assertion extraction for {} exceeded max retries. Transitioning to FAILED_FATAL.", relativePath);

            document.setStatus(DocumentStatus.FAILED_FATAL);
            documentRepository.save(document);

            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.FAILED_FATAL,
                    "PROCESSING_ASSERTIONS",
                    retryCount,
                    "Exceeded maximum 5 retries. Last error: " + e.getMessage(),
                    null,
                    completedChunks,
                    totalChunks,
                    document.getChecksum()
            );

            emitEvent(document, DocumentEventType.ASSERTION_EXTRACTION_FAILED);
        }
    }

    private void writeRetryLogFile(String documentId, int attempt, Exception e) {
        try {
            Path retriesDir = Paths.get(".noesis/retries");
            if (!Files.exists(retriesDir)) {
                Files.createDirectories(retriesDir);
            }
            Path logFile = retriesDir.resolve(documentId + ".log");
            
            StringBuilder logContent = new StringBuilder();
            logContent.append("[").append(Instant.now()).append("] Attempt #").append(attempt).append(" failed:\n");
            logContent.append("Error message: ").append(e.getMessage()).append("\n");
            logContent.append("Stacktrace:\n");
            for (StackTraceElement element : e.getStackTrace()) {
                logContent.append("\tat ").append(element.toString()).append("\n");
            }
            logContent.append("----------------------------------------------------------------------\n\n");
            
            Files.writeString(logFile, logContent.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ioException) {
            log.error("Failed to write retry log file for document: {}", documentId, ioException);
        }
    }

    private AssertionEntity buildAssertionEntity(AssertionExtractionResponse dto, ChunkEntity chunk, DocumentEntity document) throws Exception {
        String canonicalAssertion = String.format("%s|%s|%s",
                dto.getSubjectText().toUpperCase(),
                dto.getPredicate().toUpperCase(),
                dto.getObjectText().toUpperCase()
        );

        return AssertionEntity.builder()
                .id(UUID.randomUUID())
                .chunkId(chunk.getId())
                .documentId(chunk.getDocumentId())
                .documentVersion(document.getVersion())
                .ingestionRunId(document.getIngestionRunId() != null ? UUID.fromString(document.getIngestionRunId()) : null)
                .rawText(dto.getRawText())
                .normalizedText(dto.getNormalizedText())
                .subject(dto.getSubjectText())
                .predicate(dto.getPredicate().toUpperCase())
                .object(dto.getObjectText())
                .attributes(dto.getAttributes())
                .extractionModel(extractionModel)
                .projectRoot(chunk.getProjectRoot())
                .semanticChecksum(computeSha256(canonicalAssertion))
                .evidenceChecksum(computeSha256(dto.getRawText()))
                .build();
    }

    private String computeSha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void emitEvent(DocumentEntity doc, DocumentEventType eventType) {
        DocumentEvent event = DocumentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .documentId(doc.getId())
                .ingestionRunId(doc.getIngestionRunId())
                .eventType(eventType)
                .version(doc.getVersion())
                .timestamp(Instant.now())
                .build();

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        outboxService.publish("noesis-ingestion-events", doc.getId(), event, "DOCUMENT", doc.getId());
                    }
                }
            );
        } else {
            outboxService.publish("noesis-ingestion-events", doc.getId(), event, "DOCUMENT", doc.getId());
        }
    }
}
