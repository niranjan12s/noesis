package com.noesis.sgms.service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Manages version tags for the append-only semantic graph.
 *
 * <p>Every document ingestion cycle receives a globally unique version tag composed
 * of a monotonically increasing counter and a random UUID suffix, ensuring:
 * <ul>
 *   <li>Lexicographic ordering for audit/rollback purposes.</li>
 *   <li>Collision-free version identification across parallel ingestion runs.</li>
 * </ul>
 * </p>
 *
 * <p>The active version pointer on nodes, edges, and assertions is updated by the
 * Graph Builder and OpenSearch Writer — this service only mints new tags.</p>
 */
@Slf4j
@Service
public class VersioningService {

    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Generates a new, globally unique version tag for a document ingestion round.
     *
     * @param documentId the document being processed
     * @return a version tag in the form {@code v<counter>-<uuid-prefix>}
     */
    public String newVersion(String documentId) {
        int seq = counter.incrementAndGet();
        String tag = "v%05d-%s".formatted(seq, UUID.randomUUID().toString().substring(0, 8));
        log.debug("Minted version tag: {} for document: {}", tag, documentId);
        return tag;
    }
}
