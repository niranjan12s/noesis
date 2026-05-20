package com.noesis.sgms.service;

import com.noesis.sgms.service.NeosisStateService;
import com.noesis.sgms.entity.DocumentEntity;
import com.noesis.sgms.entity.DocumentStatus;
import com.noesis.sgms.event.DocumentEvent;
import com.noesis.sgms.event.DocumentEventType;
import com.noesis.sgms.producer.DocumentEventProducer;
import com.noesis.sgms.repository.DocumentRepository;
import com.noesis.sgms.entity.IngestionRunEntity;
import com.noesis.sgms.repository.IngestionRunRepository;
import com.noesis.sgms.event.PipelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentEventProducer documentEventProducer;
    private final IngestionRunRepository ingestionRunRepository;
    private final NeosisStateService neosisStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ModeService modeService;
    private final RedisDedupService redisDedupService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Transactional
    public String processFileEvent(Path filePath) {
        String absolutePath;
        try {
            absolutePath = filePath.toRealPath().toString();
        } catch (Exception e) {
            absolutePath = filePath.toAbsolutePath().normalize().toString();
        }
        absolutePath = absolutePath.replace('\\', '/');
        if (absolutePath.length() >= 2 && absolutePath.charAt(1) == ':') {
            absolutePath = Character.toUpperCase(absolutePath.charAt(0)) + absolutePath.substring(1);
        }

        String relativePath = neosisStateService.getRelativePathString(absolutePath);
        
        String lockKey = "neosis:lock:ingest:path:" + org.springframework.util.DigestUtils.md5DigestAsHex(absolutePath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", java.time.Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Ingestion already in progress for file path: {}. Skipping concurrent event.", relativePath);
            return null;
        }

        log.info("Processing file event for path: {}", relativePath);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("File does not exist or is not a regular file: {}", relativePath);
            return null;
        }

        try {
            String checksum = computeChecksum(filePath);

            // In bulk mode, use Redis dedup instead of SQLite
            if ("bulk".equals(modeService.getCurrentMode())) {
                if (redisDedupService.isChecksumKnown(checksum)) {
                    log.info("Checksum unchanged (Redis dedup) for document: {}. Skipping.", relativePath);
                    return null;
                }
            } else {
                // Check state in SQLite (realtime mode)
                Map<String, Object> sqliteState = neosisStateService.getDocumentStateByPath(relativePath);
                if (sqliteState != null) {
                    String status = (String) sqliteState.get("status");
                    String oldChecksum = (String) sqliteState.get("checksum");
                    if (DocumentStatus.QUERYABLE.name().equals(status) && checksum.equals(oldChecksum)) {
                        log.info("Checksum unchanged for document: {} and status is QUERYABLE. Skipping Ingestion.", relativePath);
                        return null;
                    }
                }
            }

            Optional<DocumentEntity> existingDocOpt = documentRepository.findByAbsolutePath(absolutePath);
            DocumentEntity doc;
            boolean isNew = existingDocOpt.isEmpty();

            String projectRoot = deriveProjectRoot(filePath);

            if (isNew) {
                // New Document
                doc = DocumentEntity.builder()
                        .name(filePath.getFileName().toString())
                        .absolutePath(absolutePath)
                        .projectRoot(projectRoot)
                        .status(DocumentStatus.DISCOVERED)
                        .version(1)
                        .checksum(checksum)
                        .ingestionRunId(UUID.randomUUID().toString())
                        .build();

                doc = documentRepository.save(doc);
                log.info("Created new document record: {}", doc.getId());
            } else {
                // Existing Document
                doc = existingDocOpt.get();

                log.info("Checksum changed or indexing incomplete for document: {}, updating record.", doc.getId());
                doc.setVersion(doc.getVersion() + 1);
                doc.setStatus(DocumentStatus.QUEUED);
                doc.setChecksum(checksum);
                doc.setIngestionRunId(UUID.randomUUID().toString());

                doc = documentRepository.save(doc);
            }

            // Sync to SQLite state.db (only in realtime mode)
            if (!"bulk".equals(modeService.getCurrentMode())) {
                neosisStateService.upsertDocumentState(
                        relativePath,
                        DocumentStatus.QUEUED,
                        "DISCOVERED",
                        0,
                        null,
                        null,
                        "",
                        0,
                        checksum
                );
            }

            // In bulk mode, record checksum in Redis dedup
            if ("bulk".equals(modeService.getCurrentMode())) {
                redisDedupService.recordChecksum(checksum, doc.getId());
            }

            saveIngestionRun(doc);

            // Decoupled pipeline starting via synchronous local event
            eventPublisher.publishEvent(PipelineEvent.builder()
                    .eventType(isNew ? "DOCUMENT_CREATED" : "DOCUMENT_UPDATED")
                    .documentId(doc.getId())
                    .ingestionRunId(doc.getIngestionRunId())
                    .build());

            // Async Kafka for durability/replay (fire-and-forget)
            emitEvent(doc, isNew ? DocumentEventType.DOCUMENT_CREATED : DocumentEventType.DOCUMENT_UPDATED);

            return doc.getId();

        } catch (Exception e) {
            log.error("Error processing file event for: {}", relativePath, e);
            return null;
        }
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
                        documentEventProducer.sendDocumentEvent(event);
                    }
                }
            );
        } else {
            documentEventProducer.sendDocumentEvent(event);
        }
    }

    private void saveIngestionRun(DocumentEntity doc) {
        IngestionRunEntity run = IngestionRunEntity.builder()
                .id(UUID.fromString(doc.getIngestionRunId()))
                .documentId(UUID.fromString(doc.getId()))
                .runStatus("STARTED")
                .triggeredBy("FILE_WATCHER")
                .build();
        ingestionRunRepository.save(run);
    }

    private String computeChecksum(Path filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = digest.digest();
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

    public static String deriveProjectRoot(java.nio.file.Path filePath) {
        java.nio.file.Path parent = filePath.getParent();
        if (parent == null) return "";
        String parentName = parent.getFileName() != null ? parent.getFileName().toString() : "";
        if ("docs".equalsIgnoreCase(parentName) || "doc".equalsIgnoreCase(parentName)
                || "documentation".equalsIgnoreCase(parentName)) {
            java.nio.file.Path grandParent = parent.getParent();
            if (grandParent != null && grandParent.getFileName() != null) {
                return grandParent.getFileName().toString();
            }
        }
        return parentName;
    }
}
