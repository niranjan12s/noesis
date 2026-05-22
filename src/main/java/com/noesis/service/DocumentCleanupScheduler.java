package com.noesis.service;

import com.noesis.entity.DocumentEntity;
import com.noesis.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentCleanupScheduler {

    private final DocumentRepository documentRepository;
    private final DocumentDeletionService deletionService;

    @Scheduled(fixedRate = 600000)
    public void cleanupExpiredDeletions() {
        try {
            List<DocumentEntity> expired = documentRepository.findByMarkedForDeletionAtBefore(Instant.now());
            for (DocumentEntity doc : expired) {
                log.info("Cleanup scheduler: hard-deleting expired document {}", doc.getName());
                deletionService.deleteDocumentData(doc);
            }
        } catch (Exception e) {
            log.warn("Document cleanup scheduler error: {}", e.getMessage());
        }
    }
}
