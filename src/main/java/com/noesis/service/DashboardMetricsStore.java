package com.noesis.service;

import com.noesis.event.DocumentEvent;
import com.noesis.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardMetricsStore {

    private final DocumentRepository documentRepository;
    private final ChunkJpaRepository chunkJpaRepository;
    private final AssertionJpaRepository assertionJpaRepository;
    private final NodeJpaRepository nodeJpaRepository;
    private final EdgeJpaRepository edgeJpaRepository;

    private final AtomicInteger documentCount = new AtomicInteger(0);
    private final AtomicInteger chunkCount = new AtomicInteger(0);
    private final AtomicInteger assertionCount = new AtomicInteger(0);
    private final AtomicInteger nodeCount = new AtomicInteger(0);
    private final AtomicInteger edgeCount = new AtomicInteger(0);

    private final AtomicInteger failedExtractions = new AtomicInteger(0);
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);
    private final AtomicInteger processedChunksCount = new AtomicInteger(0);

    private final Map<String, AtomicInteger> eventTypeCounts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> recentEvents = new ConcurrentLinkedQueue<>();
    private final List<Map<String, Object>> throughputHistory = Collections.synchronizedList(new ArrayList<>());

    private static final int MAX_RECENT_EVENTS = 50;

    @Scheduled(fixedRate = 10000)
    public void refreshDatabaseCounts() {
        try {
            documentCount.set((int) documentRepository.count());
            chunkCount.set((int) chunkJpaRepository.count());
            assertionCount.set((int) assertionJpaRepository.count());
            nodeCount.set((int) nodeJpaRepository.count());
            edgeCount.set((int) edgeJpaRepository.count());

            // Add a throughput history data point
            Map<String, Object> dataPoint = Map.of(
                    "timestamp", Instant.now().toString(),
                    "documents", documentCount.get(),
                    "chunks", chunkCount.get(),
                    "assertions", assertionCount.get(),
                    "nodes", nodeCount.get(),
                    "edges", edgeCount.get()
            );
            throughputHistory.add(dataPoint);
            if (throughputHistory.size() > 60) { // keep last 10 minutes (60 points * 10 seconds)
                throughputHistory.remove(0);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh database metrics: {}", e.getMessage());
        }
    }

    public void recordEvent(DocumentEvent event) {
        if (event == null) return;

        String typeStr = event.getEventType() != null ? event.getEventType().name() : "UNKNOWN";
        eventTypeCounts.computeIfAbsent(typeStr, k -> new AtomicInteger(0)).incrementAndGet();

        // Track stats from specific events
        if ("ASSERTION_EXTRACTION_FAILED".equals(typeStr)) {
            failedExtractions.incrementAndGet();
        }

        // Add to rolling events buffer
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("eventId", event.getEventId());
        eventMap.put("documentId", event.getDocumentId());
        eventMap.put("ingestionRunId", event.getIngestionRunId());
        eventMap.put("eventType", typeStr);
        eventMap.put("version", event.getVersion());
        eventMap.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : Instant.now().toString());

        recentEvents.add(eventMap);
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.poll();
        }
    }

    public void recordChunkLatency(long durationMs) {
        totalProcessingTimeMs.addAndGet(durationMs);
        processedChunksCount.incrementAndGet();
    }

    public Map<String, Object> getMetricsSummary() {
        // Fallback checks if scheduling hasn't populated yet
        if (documentCount.get() == 0) {
            refreshDatabaseCounts();
        }

        int docs = documentCount.get();
        int chunks = chunkCount.get();
        int assertions = assertionCount.get();
        int nodes = nodeCount.get();
        int edges = edgeCount.get();

        Double avgLatency = null;
        int chunkProcessed = processedChunksCount.get();
        if (chunkProcessed > 0) {
            avgLatency = (double) totalProcessingTimeMs.get() / chunkProcessed;
        }

        // Compute Knowledge Density
        double assertionsPerDoc = docs > 0 ? (double) assertions / docs : 0.0;
        double assertionsPerChunk = chunks > 0 ? (double) assertions / chunks : 0.0;

        // Estimate tokens: sum of actual per-chunk token estimates from the database
        long estimatedTokens = chunkJpaRepository.sumTokenEstimates();

        Map<String, Integer> eventCountsMap = new HashMap<>();
        eventTypeCounts.forEach((k, v) -> eventCountsMap.put(k, v.get()));

        List<Map<String, Object>> eventsList = new ArrayList<>(recentEvents);
        Collections.reverse(eventsList); // newest first

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDocuments", docs);
        summary.put("totalChunks", chunks);
        summary.put("totalAssertions", assertions);
        summary.put("totalNodes", nodes);
        summary.put("totalEdges", edges);
        summary.put("averageLatencyMs", avgLatency);
        summary.put("assertionsPerDoc", assertionsPerDoc);
        summary.put("assertionsPerChunk", assertionsPerChunk);
        summary.put("failedExtractions", failedExtractions.get());
        summary.put("estimatedTokens", estimatedTokens);
        summary.put("eventCounts", eventCountsMap);
        summary.put("recentEvents", eventsList);
        summary.put("throughputHistory", new ArrayList<>(throughputHistory));

        return summary;
    }
}
