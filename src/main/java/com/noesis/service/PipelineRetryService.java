package com.noesis.service;

import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRetryService {

    private final DocumentRepository documentRepository;
    private final NoesisStateService noesisStateService;

    private static final String JDBC_URL = "jdbc:sqlite:.noesis/state.db";

    public void onFailure(DocumentEntity doc, String stage, Exception e, RetryablePipelineStage retryable) {
        String relativePath = noesisStateService.getRelativePathString(doc.getAbsolutePath());
        Map<String, Object> sqliteState = noesisStateService.getDocumentStateByPath(relativePath);
        int retryCount = sqliteState != null ? (int) sqliteState.getOrDefault("retry_count", 0) : 0;
        retryCount++;

        if (retryCount <= retryable.maxRetries()) {
            long delaySeconds = retryable.backoffSeconds(retryCount);
            Instant nextRetryAt = Instant.now().plusSeconds(delaySeconds);
            log.warn("Pipeline stage '{}' failed for document {}. Retry #{}/{} in {}s (at {})",
                    stage, doc.getName(), retryCount, retryable.maxRetries(), delaySeconds, nextRetryAt);

            doc.setStatus(DocumentStatus.RETRYING);
            documentRepository.save(doc);

            int totalChunks = sqliteState != null ? (int) sqliteState.getOrDefault("total_chunks", 0) : 0;
            String completedChunks = sqliteState != null ? (String) sqliteState.getOrDefault("completed_chunks", "") : "";

            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.RETRYING,
                    stage,
                    retryCount,
                    e.getMessage(),
                    nextRetryAt,
                    completedChunks,
                    totalChunks,
                    doc.getChecksum()
            );
        } else {
            log.error("Pipeline stage '{}' exceeded max retries for document {}. Marking FAILED_FATAL.", stage, doc.getName());
            doc.setStatus(DocumentStatus.FAILED_FATAL);
            documentRepository.save(doc);

            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.FAILED_FATAL,
                    stage,
                    retryCount,
                    "Exceeded max retries: " + e.getMessage(),
                    null,
                    "",
                    0,
                    doc.getChecksum()
            );
        }
    }

    public void onSuccess(DocumentEntity doc, String stage) {
        String relativePath = noesisStateService.getRelativePathString(doc.getAbsolutePath());
        Map<String, Object> sqliteState = noesisStateService.getDocumentStateByPath(relativePath);
        int totalChunks = sqliteState != null ? (int) sqliteState.getOrDefault("total_chunks", 0) : 0;
        String completedChunks = sqliteState != null ? (String) sqliteState.getOrDefault("completed_chunks", "") : "";

        noesisStateService.upsertDocumentState(
                relativePath,
                doc.getStatus(),
                stage,
                0,
                null,
                null,
                completedChunks,
                totalChunks,
                doc.getChecksum()
        );
    }
}
