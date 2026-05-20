package com.noesis.sgms.service;

import com.noesis.sgms.entity.DocumentEntity;
import com.noesis.sgms.entity.DocumentStatus;
import com.noesis.sgms.repository.DocumentRepository;
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
public class NeosisRetryScheduler {

    private final NeosisStateService neosisStateService;
    private final AssertionExtractionService assertionExtractionService;
    private final MarkdownChunkingService markdownChunkingService;
    private final DocumentRepository documentRepository;

    private static final String JDBC_URL = "jdbc:sqlite:.neosis/state.db";

    @Scheduled(fixedDelay = 5000)
    public void scheduleRetries() {
        String sql = "SELECT * FROM document_state WHERE status = 'RETRYING' AND next_retry_at <= ?";
        List<Map<String, Object>> retryableDocs = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setTimestamp(1, Timestamp.from(Instant.now()));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    retryableDocs.add(neosisStateService.getDocumentStateByPath(rs.getString("path")));
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

            // Transition status to active state in SQLite and Postgres
            DocumentStatus activeStatus = stage.equals("CHUNKING") ? DocumentStatus.PROCESSING_ASSERTIONS : DocumentStatus.PROCESSING_ASSERTIONS;
            
            // Sync with Postgres first
            DocumentEntity doc = documentRepository.findById(documentId).orElse(null);
            if (doc != null) {
                doc.setStatus(activeStatus);
                documentRepository.save(doc);
            }

            neosisStateService.upsertDocumentState(
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

            // Execute actual pipeline resumption
            try {
                if ("CHUNKING".equals(stage) || "CHUNKING_FAILED".equals(stage)) {
                    markdownChunkingService.processDocumentChunking(documentId);
                } else {
                    // Default / PROCESSING_ASSERTIONS
                    assertionExtractionService.processDocumentAssertions(documentId);
                }
            } catch (Exception ex) {
                log.error("Failed executing scheduled retry for: {}", path, ex);
            }
        }
    }
}
