package com.noesis.cache;

import com.noesis.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentDeletionCache {

    private final DocumentRepository documentRepository;

    private final Map<String, Instant> deletedIds = new ConcurrentHashMap<>();

    private static final long TTL_SECONDS = 8 * 60 * 60;

    @PostConstruct
    public void loadFromDb() {
        int count = 0;
        for (com.noesis.entity.DocumentEntity doc : documentRepository.findAll()) {
            if (doc.getMarkedForDeletionAt() != null) {
                deletedIds.put(doc.getId(), doc.getMarkedForDeletionAt());
                count++;
            }
        }
        log.info("DocumentDeletionCache loaded {} deleted document(s) from database", count);
    }

    public boolean isDeleted(String documentId) {
        Instant expiry = deletedIds.get(documentId);
        if (expiry == null) return false;
        if (Instant.now().isAfter(expiry.plusSeconds(TTL_SECONDS))) {
            deletedIds.remove(documentId);
            return false;
        }
        return true;
    }

    public void markDeleted(String documentId, Instant deletionAt) {
        deletedIds.put(documentId, deletionAt);
    }

    public void restore(String documentId) {
        deletedIds.remove(documentId);
    }

    public int size() {
        return deletedIds.size();
    }
}
