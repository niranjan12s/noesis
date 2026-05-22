package com.noesis.service;

import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NoesisRetryScheduler {

    private final NoesisStateService noesisStateService;
    private final DocumentRepository documentRepository;
    private final List<RetryablePipelineStage> pipelineStages;

    private static final String JDBC_URL = "jdbc:sqlite:.noesis/state.db";

    @Scheduled(fixedDelay = 5000)
    public void scheduleRetries() {
        String sql = "SELECT * FROM document_state WHERE status = 'RETRYING' AND next_retry_at <= ?";
        List<Map<String, Object>> retryableDocs = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setTimestamp(1, Timestamp.from(Instant.now()));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    retryableDocs.add(noesisStateService.getDocumentStateByPath(rs.getString("path")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query scheduled retries from SQLite", e);
            return;
        }

        for (Map<String, Object> state : retryableDocs) {
            String path = (String) state.get("path");
            String documentId = (String) state.get("document_id");
            String stage = (String) state.get("current_stage");
            int retryCount = (Integer) state.get("retry_count");

            log.info("Scheduled Retry: Resuming document {} (Stage: {}) - Attempt #{}", path, stage, retryCount);

            DocumentStatus activeStatus = DocumentStatus.PROCESSING_ASSERTIONS;

            DocumentEntity doc = documentRepository.findById(documentId).orElse(null);
            if (doc != null) {
                doc.setStatus(activeStatus);
                documentRepository.save(doc);
            }

            noesisStateService.upsertDocumentState(
                    path,
                    activeStatus,
                    stage,
                    retryCount,
                    null,
                    null,
                    (String) state.get("completed_chunks"),
                    (Integer) state.get("total_chunks"),
                    (String) state.get("checksum")
            );

            try {
                RetryablePipelineStage handler = findStage(stage);
                if (handler != null) {
                    handler.execute(documentId);
                } else {
                    log.warn("No pipeline stage handler found for stage: {}", stage);
                }
            } catch (Exception ex) {
                log.error("Failed executing scheduled retry for: {}", path, ex);
            }
        }
    }

    private RetryablePipelineStage findStage(String stageName) {
        for (RetryablePipelineStage s : pipelineStages) {
            if (s.stageName().equals(stageName)) {
                return s;
            }
        }
        return null;
    }
}
