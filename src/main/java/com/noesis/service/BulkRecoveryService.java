package com.noesis.service;

import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkRecoveryService {

    private final DocumentRepository documentRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final DocumentRecoveryService documentRecoveryService;
    private final ModeService modeService;
    private final BulkProgressStore bulkProgressStore;

    @PostConstruct
    public void init() {
        new Thread(this::recoverBulkState, "bulk-recovery").start();
    }

    private void recoverBulkState() {
        try {
            Thread.sleep(30000); // wait for full app startup
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!"bulk".equals(modeService.getCurrentMode()) || !modeService.isBulkJobActive()) {
            log.info("BulkRecoveryService: not in active bulk mode, skipping bulk recovery");
            return;
        }

        log.info("BulkRecoveryService: starting bulk state recovery...");

        // 1. Release any stale Redis locks (from previous instance)
        releaseStaleLocks();

        // 2. Find documents in non-terminal state and recover them
        List<DocumentEntity> stuckDocs = documentRepository.findAll().stream()
                .filter(d -> d.getStatus() != DocumentStatus.QUERYABLE && d.getStatus() != DocumentStatus.FAILED_FATAL)
                .toList();

        for (DocumentEntity doc : stuckDocs) {
            log.info("Bulk recovery resuming document: {} ({})", doc.getName(), doc.getStatus());
            documentRecoveryService.recoverDocument(doc);
        }

        if (stuckDocs.isEmpty()) {
            log.info("BulkRecoveryService: no stuck documents found");
        } else {
            log.info("BulkRecoveryService: recovered {} stuck document(s)", stuckDocs.size());
        }

        // 3. Reload persisted metrics from Redis
        bulkProgressStore.loadFromRedis();
    }

    private void releaseStaleLocks() {
        try {
            Set<String> assertionLocks = stringRedisTemplate.keys("noesis:lock:assertion:*");
            if (assertionLocks != null) {
                stringRedisTemplate.delete(assertionLocks);
                log.info("Released {} stale assertion locks", assertionLocks.size());
            }
            Set<String> graphLocks = stringRedisTemplate.keys("noesis:lock:graph:*");
            if (graphLocks != null) {
                stringRedisTemplate.delete(graphLocks);
                log.info("Released {} stale graph locks", graphLocks.size());
            }
        } catch (Exception e) {
            log.warn("Failed to release stale locks during bulk recovery", e);
        }
    }
}
