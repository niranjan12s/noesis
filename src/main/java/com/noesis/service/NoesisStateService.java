package com.noesis.service;

import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class NoesisStateService {

    private final DocumentRepository documentRepository;
    private static final String DB_DIR = ".noesis";
    private static final String DB_FILE = ".noesis/state.db";
    private static final String JDBC_URL = "jdbc:sqlite:.noesis/state.db";
    private final Object sqliteLock = new Object();

    public NoesisStateService(@Lazy DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @PostConstruct
    public void init() {
        try {
            // Ensure .noesis directory exists
            Path dirPath = Paths.get(DB_DIR);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                log.info("Created state database directory: {}", dirPath.toAbsolutePath());
            }

            // Load SQLite JDBC Driver
            Class.forName("org.sqlite.JDBC");

            // Initialize SQLite schema
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                
                String sql = """
                    CREATE TABLE IF NOT EXISTS document_state (
                        document_id VARCHAR(36) PRIMARY KEY,
                        path TEXT UNIQUE,
                        status VARCHAR(32),
                        current_stage VARCHAR(64),
                        retry_count INTEGER DEFAULT 0,
                        last_error TEXT,
                        next_retry_at TIMESTAMP,
                        completed_chunks TEXT,
                        total_chunks INTEGER DEFAULT 0,
                        checksum VARCHAR(64),
                        updated_at TIMESTAMP
                    );
                    """;
                stmt.execute(sql);
                log.info("SQLite schema initialized successfully at {}", DB_FILE);
            }
        } catch (Exception e) {
            log.error("Failed to initialize SQLite state database", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL);
    }

    /**
     * Get details of a document state from SQLite by relative path
     */
    public Map<String, Object> getDocumentStateByPath(String path) {
        synchronized (sqliteLock) {
            String sql = "SELECT * FROM document_state WHERE path = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                
                pstmt.setString(1, path);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> state = new HashMap<>();
                        state.put("document_id", rs.getString("document_id"));
                        state.put("path", rs.getString("path"));
                        state.put("status", rs.getString("status"));
                        state.put("current_stage", rs.getString("current_stage"));
                        state.put("retry_count", rs.getInt("retry_count"));
                        state.put("last_error", rs.getString("last_error"));
                        
                        Timestamp nextRetry = rs.getTimestamp("next_retry_at");
                        state.put("next_retry_at", nextRetry != null ? nextRetry.toInstant() : null);
                        
                        state.put("completed_chunks", rs.getString("completed_chunks"));
                        state.put("total_chunks", rs.getInt("total_chunks"));
                        state.put("checksum", rs.getString("checksum"));
                        
                        Timestamp updated = rs.getTimestamp("updated_at");
                        state.put("updated_at", updated != null ? updated.toInstant() : null);
                        return state;
                    }
                }
            } catch (SQLException e) {
                log.error("Error querying document state for path: {}", path, e);
            }
            return null;
        }
    }

    /**
     * Update/Upsert the state in SQLite and dual-write/sync to PostgreSQL
     */
    @Transactional
    public void upsertDocumentState(
            String path,
            DocumentStatus status,
            String currentStage,
            int retryCount,
            String lastError,
            Instant nextRetryAt,
            String completedChunks,
            int totalChunks,
            String checksum
    ) {
        String relativePath = getRelativePathString(path);
        String absolutePath = Paths.get(path).toAbsolutePath().normalize().toString();
        absolutePath = absolutePath.replace('\\', '/');
        if (absolutePath.length() >= 2 && absolutePath.charAt(1) == ':') {
            absolutePath = Character.toUpperCase(absolutePath.charAt(0)) + absolutePath.substring(1);
        }

        log.debug("Syncing state for doc: {} (Status: {}, Stage: {})", relativePath, status, currentStage);

        // 1. Get or generate document ID
        String documentId = null;
        Optional<DocumentEntity> pgDocOpt = documentRepository.findByAbsolutePath(absolutePath);
        if (pgDocOpt.isPresent()) {
            documentId = pgDocOpt.get().getId();
        } else {
            Map<String, Object> existing = getDocumentStateByPath(relativePath);
            if (existing != null) {
                documentId = (String) existing.get("document_id");
            } else {
                documentId = UUID.randomUUID().toString();
            }
        }

        // 2. Write to SQLite
        String sqliteSql = """
            INSERT INTO document_state (
                document_id, path, status, current_stage, retry_count, 
                last_error, next_retry_at, completed_chunks, total_chunks, checksum, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                document_id = excluded.document_id,
                status = excluded.status,
                current_stage = excluded.current_stage,
                retry_count = excluded.retry_count,
                last_error = excluded.last_error,
                next_retry_at = excluded.next_retry_at,
                completed_chunks = excluded.completed_chunks,
                total_chunks = excluded.total_chunks,
                checksum = excluded.checksum,
                updated_at = excluded.updated_at;
            """;

        synchronized (sqliteLock) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sqliteSql)) {
                
                pstmt.setString(1, documentId);
                pstmt.setString(2, relativePath);
                pstmt.setString(3, status.name());
                pstmt.setString(4, currentStage);
                pstmt.setInt(5, retryCount);
                pstmt.setString(6, lastError);
                pstmt.setTimestamp(7, nextRetryAt != null ? Timestamp.from(nextRetryAt) : null);
                pstmt.setString(8, completedChunks);
                pstmt.setInt(9, totalChunks);
                pstmt.setString(10, checksum);
                pstmt.setTimestamp(11, Timestamp.from(Instant.now()));
                
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to upsert state to SQLite for path: {}", relativePath, e);
            }
        }

        // 3. Sync to PostgreSQL
        try {
            DocumentEntity doc;
            if (pgDocOpt.isPresent()) {
                doc = pgDocOpt.get();
                doc.setStatus(status);
                doc.setChecksum(checksum);
                doc.setUpdatedAt(Instant.now());
            } else {
                doc = DocumentEntity.builder()
                        .id(documentId)
                        .name(Paths.get(path).getFileName().toString())
                        .absolutePath(absolutePath)
                        .status(status)
                        .version(1)
                        .checksum(checksum)
                        .ingestionRunId(UUID.randomUUID().toString())
                        .build();
            }
            documentRepository.save(doc);
        } catch (Exception e) {
            log.error("Failed to sync state to PostgreSQL for path: {}", absolutePath, e);
        }
    }

    /**
     * Atomically appends a chunk ID to the completed_chunks column in SQLite
     */
    public void addCompletedChunk(String path, String chunkId) {
        String relativePath = getRelativePathString(path);
        synchronized (sqliteLock) {
            Map<String, Object> state = getDocumentStateByPath(relativePath);
            if (state == null) return;

            String completed = (String) state.get("completed_chunks");
            Set<String> chunks = new LinkedHashSet<>();
            if (completed != null && !completed.isBlank()) {
                chunks.addAll(Arrays.asList(completed.split(",")));
            }
            chunks.add(chunkId);

            String updatedCompleted = String.join(",", chunks);

            String sql = "UPDATE document_state SET completed_chunks = ?, updated_at = ? WHERE path = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                
                pstmt.setString(1, updatedCompleted);
                pstmt.setTimestamp(2, Timestamp.from(Instant.now()));
                pstmt.setString(3, relativePath);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to update completed chunks for path: {}", relativePath, e);
            }
        }
    }

    /**
     * Get lists of completed chunks from SQLite
     */
    public void resetCompletedChunks(String path) {
        String relativePath = getRelativePathString(path);
        synchronized (sqliteLock) {
            Map<String, Object> state = getDocumentStateByPath(relativePath);
            if (state == null) return;
            String sql = "UPDATE document_state SET completed_chunks = '', updated_at = ? WHERE path = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setTimestamp(1, Timestamp.from(Instant.now()));
                pstmt.setString(2, relativePath);
                pstmt.executeUpdate();
                log.info("Reset completed chunks for path: {}", relativePath);
            } catch (SQLException e) {
                log.error("Failed to reset completed chunks for path: {}", relativePath, e);
            }
        }
    }

    public List<String> getCompletedChunkIds(String path) {
        String relativePath = getRelativePathString(path);
        Map<String, Object> state = getDocumentStateByPath(relativePath);
        if (state == null) return Collections.emptyList();

        String completed = (String) state.get("completed_chunks");
        if (completed == null || completed.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(completed.split(","));
    }

    /**
     * Relativize absolute paths for clean local DB keys
     */
    public String getRelativePathString(String pathStr) {
        String normalized = pathStr.replace('\\', '/');
        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            normalized = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
        }
        Path path = Paths.get(normalized).toAbsolutePath().normalize();
        
        Path root = Paths.get("").toAbsolutePath().normalize();
        String rootStr = root.toString().replace('\\', '/');
        if (rootStr.length() >= 2 && rootStr.charAt(1) == ':') {
            rootStr = Character.toUpperCase(rootStr.charAt(0)) + rootStr.substring(1);
        }
        root = Paths.get(rootStr);
        
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return normalized;
        }
    }
}
