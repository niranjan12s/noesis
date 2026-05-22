package com.noesis.service;

import com.noesis.entity.AssertionEntity;
import com.noesis.entity.DocumentEntity;
import com.noesis.entity.DocumentStatus;
import com.noesis.entity.EdgeEntity;
import com.noesis.entity.NodeEntity;
import com.noesis.event.DocumentEvent;
import com.noesis.event.DocumentEventType;
import com.noesis.events.GraphUpdateEvent;
import com.noesis.producer.BulkGraphProducer;
import com.noesis.producer.DocumentEventProducer;
import com.noesis.repository.AssertionJpaRepository;
import com.noesis.repository.DocumentRepository;
import com.noesis.repository.EdgeJpaRepository;
import com.noesis.repository.NodeJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphComponentService {

    private final AssertionJpaRepository assertionRepository;
    private final NodeJpaRepository nodeRepository;
    private final EdgeJpaRepository edgeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentEventProducer documentEventProducer;
    private final NoesisStateService noesisStateService;
    private final OpenSearchAsyncClient openSearchAsyncClient;
    private final ModeService modeService;
    private final BulkGraphProducer bulkGraphProducer;
    private final GraphSseService graphSseService;
    private final BulkProgressStore bulkProgressStore;
    private final PipelineRetryService pipelineRetryService;

    @Transactional
    public void buildGraphComponents(String ingestionRunIdStr) {
        log.info("Building graph components for ingestion run: {}", ingestionRunIdStr);
        if (ingestionRunIdStr == null || ingestionRunIdStr.isBlank()) {
            log.warn("Ingestion run ID is null or blank, skipping graph component building.");
            return;
        }
        UUID ingestionRunId = UUID.fromString(ingestionRunIdStr);

        List<AssertionEntity> assertions = assertionRepository.findByIngestionRunId(ingestionRunId);
        if (assertions.isEmpty()) {
            log.info("No assertions found for ingestion run: {}", ingestionRunIdStr);
            return;
        }

        String documentId = assertions.get(0).getDocumentId().toString();
        DocumentEntity document = documentRepository.findById(documentId).orElse(null);
        if (document != null && document.getStatus() == DocumentStatus.QUERYABLE) {
            log.info("Document {} is already QUERYABLE. Skipping graph component building.", documentId);
            return;
        }

        try {
            // --- BATCH PROCESS NODES ---
            Map<String, String> checksumToCanonicalName = new HashMap<>();
            for (AssertionEntity assertion : assertions) {
                if (assertion.getSubject() != null) {
                    checksumToCanonicalName.put(computeSha256(normalize(assertion.getSubject())), assertion.getSubject());
                }
                if (assertion.getObject() != null) {
                    checksumToCanonicalName.put(computeSha256(normalize(assertion.getObject())), assertion.getObject());
                }
            }

            List<NodeEntity> existingNodes = nodeRepository.findBySemanticChecksumIn(checksumToCanonicalName.keySet());
            Map<String, NodeEntity> nodeCache = new HashMap<>();
            for (NodeEntity node : existingNodes) {
                nodeCache.put(node.getSemanticChecksum(), node);
            }

            List<NodeEntity> nodesToCreate = new ArrayList<>();
            for (Map.Entry<String, String> entry : checksumToCanonicalName.entrySet()) {
                String checksum = entry.getKey();
                String canonicalName = entry.getValue();
                if (!nodeCache.containsKey(checksum)) {
                    NodeEntity newNode = NodeEntity.builder()
                            .canonicalName(canonicalName)
                            .normalizedName(normalize(canonicalName))
                            .semanticChecksum(checksum)
                            .projectRoot(assertions.isEmpty() ? null : assertions.get(0).getProjectRoot())
                            .build();
                    nodesToCreate.add(newNode);
                }
            }

            if (!nodesToCreate.isEmpty()) {
                List<NodeEntity> savedNodes = nodeRepository.saveAll(nodesToCreate);
                for (NodeEntity node : savedNodes) {
                    nodeCache.put(node.getSemanticChecksum(), node);
                }
            }

            // --- BATCH PROCESS EDGES ---
            Map<String, EdgeEntity> edgesToVerify = new HashMap<>();
            for (AssertionEntity assertion : assertions) {
                String subjectChecksum = computeSha256(normalize(assertion.getSubject()));
                String objectChecksum = computeSha256(normalize(assertion.getObject()));
                NodeEntity subjectNode = nodeCache.get(subjectChecksum);
                NodeEntity objectNode = nodeCache.get(objectChecksum);

                if (subjectNode != null && objectNode != null) {
                    String edgeChecksumInput = subjectNode.getSemanticChecksum() + assertion.getPredicate() + objectNode.getSemanticChecksum();
                    String edgeChecksum = computeSha256(edgeChecksumInput);

                    if (!edgesToVerify.containsKey(edgeChecksum)) {
                        EdgeEntity edge = EdgeEntity.builder()
                                .fromNodeId(subjectNode.getId())
                                .predicate(assertion.getPredicate())
                                .toNodeId(objectNode.getId())
                                .semanticChecksum(edgeChecksum)
                                .ingestionRunId(ingestionRunId)
                                .projectRoot(assertion.getProjectRoot())
                                .build();
                        edgesToVerify.put(edgeChecksum, edge);
                    }
                }
            }

            List<EdgeEntity> existingEdges = edgeRepository.findBySemanticChecksumIn(edgesToVerify.keySet());
            Map<String, EdgeEntity> edgeCache = new HashMap<>();
            for (EdgeEntity edge : existingEdges) {
                edgeCache.put(edge.getSemanticChecksum(), edge);
            }

            List<EdgeEntity> edgesToCreate = new ArrayList<>();
            for (Map.Entry<String, EdgeEntity> entry : edgesToVerify.entrySet()) {
                if (!edgeCache.containsKey(entry.getKey())) {
                    edgesToCreate.add(entry.getValue());
                }
            }

            if (!edgesToCreate.isEmpty()) {
                List<EdgeEntity> savedEdges = edgeRepository.saveAll(edgesToCreate);
                for (EdgeEntity edge : savedEdges) {
                    edgeCache.put(edge.getSemanticChecksum(), edge);
                }
            }

            // --- BATCH UPDATE ASSERTIONS ---
            for (AssertionEntity assertion : assertions) {
                String subjectChecksum = computeSha256(normalize(assertion.getSubject()));
                String objectChecksum = computeSha256(normalize(assertion.getObject()));
                NodeEntity subjectNode = nodeCache.get(subjectChecksum);
                NodeEntity objectNode = nodeCache.get(objectChecksum);

                if (subjectNode != null) {
                    assertion.setSubjectNodeId(subjectNode.getId());
                }
                if (objectNode != null) {
                    assertion.setObjectNodeId(objectNode.getId());
                }
            }
            assertionRepository.saveAll(assertions);

            int nodesCount = checksumToCanonicalName.size();
            int edgesCount = edgesToCreate.size() + existingEdges.size();

            if ("bulk".equals(modeService.getCurrentMode()) && modeService.isBulkJobActive()) {
                bulkProgressStore.addEdges(edgesCount);
            }

            // In bulk mode, broadcast SSE with graph growth (document ID from assertions)
            if ("bulk".equals(modeService.getCurrentMode()) && modeService.isBulkJobActive()) {
                String docId = assertions.get(0).getDocumentId().toString();
                bulkGraphProducer.sendGraphUpdate(GraphUpdateEvent.builder()
                        .ingestionRunId(ingestionRunIdStr)
                        .documentId(docId)
                        .nodesCount(nodesCount)
                        .edgesCount(edgesCount)
                        .assertionIds(assertions.stream().map(a -> a.getId().toString()).toList())
                        .createdAt(System.currentTimeMillis())
                        .build());
                graphSseService.broadcastGraphUpdate(
                    nodesCount + (int) nodeRepository.count(),
                    edgesCount + (int) edgeRepository.count(),
                    nodesCount, edgesCount);
                log.info("Published graph update to graph.update.events for bulk mode");
            }

        } catch (Exception e) {
            log.error("Failed to build graph components in batch for ingestion run: {}", ingestionRunIdStr, e);
            if (document != null) {
                pipelineRetryService.onFailure(document, "PROCESSING_GRAPH", e, new RetryablePipelineStage() {
                    @Override public String stageName() { return "PROCESSING_GRAPH"; }
                    @Override public void execute(String docId) { buildGraphComponents(ingestionRunIdStr); }
                });
            }
            return;
        }

        // Bulk index assertions and edges to OpenSearch
        bulkIndexAssertions(assertions);
        List<EdgeEntity> edges = edgeRepository.findByIngestionRunId(ingestionRunId);
        bulkIndexEdges(edges);

        // Mark document QUERYABLE
        if (document != null) {
            document.setStatus(DocumentStatus.QUERYABLE);
            documentRepository.save(document);

            String relativePath = noesisStateService.getRelativePathString(document.getAbsolutePath());
            noesisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.QUERYABLE,
                    "QUERYABLE",
                    0,
                    null,
                    null,
                    String.join(",", noesisStateService.getCompletedChunkIds(relativePath)),
                    0,
                    document.getChecksum()
            );

            log.info("Document fully indexed and Graph is Ready: {}", document.getId());
            emitDocumentGraphReady(document);
            if ("bulk".equals(modeService.getCurrentMode()) && modeService.isBulkJobActive()) {
                bulkProgressStore.incrementProcessed();
            }
        }

        log.info("Completed building graph components for run: {}", ingestionRunIdStr);
    }

    public void retryBuildGraphByDocumentId(String documentId) {
        DocumentEntity doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || doc.getIngestionRunId() == null) {
            log.warn("Cannot retry graph build for doc {}: no ingestion run ID", documentId);
            return;
        }
        buildGraphComponents(doc.getIngestionRunId());
    }

    private void bulkIndexAssertions(List<AssertionEntity> assertions) {
        List<BulkOperation> operations = new ArrayList<>();
        for (AssertionEntity assertion : assertions) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("assertionId", assertion.getId().toString());
            doc.put("documentId", assertion.getDocumentId().toString());
            doc.put("subjectText", assertion.getSubject());
            doc.put("predicate", assertion.getPredicate());
            doc.put("objectText", assertion.getObject());
            doc.put("normalizedText", assertion.getNormalizedText());
            doc.put("rawText", assertion.getRawText());
            doc.put("subjectNodeId", assertion.getSubjectNodeId().toString());
            doc.put("objectNodeId", assertion.getObjectNodeId().toString());
            doc.put("timestamp", Instant.now().toString());

            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Map<String, Object>>()
                    .index("assertion-index")
                    .id(assertion.getId().toString())
                    .document(doc)
                    .build()
            ).build());
        }

        if (!operations.isEmpty()) {
            indexWithRetry("assertions", operations, 3);
        }
    }

    private void indexWithRetry(String label, List<BulkOperation> operations, int maxAttempts) {
        try {
            BulkRequest bulk = new BulkRequest.Builder().operations(operations).build();
            openSearchAsyncClient.bulk(bulk)
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        log.warn("OpenSearch bulk index failed for {} (async): {}", label, throwable.getMessage());
                    } else {
                        log.info("Bulk indexed {} {} to OpenSearch", operations.size(), label);
                    }
                });
        } catch (Exception e) {
            log.warn("Failed to submit OpenSearch bulk index for {}: {}", label, e.getMessage());
        }
    }

    private void bulkIndexEdges(List<EdgeEntity> edges) {
        List<BulkOperation> operations = new ArrayList<>();
        for (EdgeEntity edge : edges) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("edgeId", edge.getId().toString());
            doc.put("fromNodeId", edge.getFromNodeId().toString());
            doc.put("toNodeId", edge.getToNodeId().toString());
            doc.put("predicate", edge.getPredicate());

            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Map<String, Object>>()
                    .index("edge-index")
                    .id(edge.getId().toString())
                    .document(doc)
                    .build()
            ).build());
        }

        if (!operations.isEmpty()) {
            indexWithRetry("edges", operations, 3);
        }
    }

    private void emitDocumentGraphReady(DocumentEntity document) {
        DocumentEvent docEvent = DocumentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .documentId(document.getId())
                .ingestionRunId(document.getIngestionRunId())
                .eventType(DocumentEventType.DOCUMENT_GRAPH_READY)
                .version(document.getVersion())
                .timestamp(Instant.now())
                .build();
        documentEventProducer.sendDocumentEvent(docEvent);
    }



    private String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("_", "")
                .replaceAll("-", "");
    }

    private String computeSha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte h : hash) {
            String hex = Integer.toHexString(0xff & h);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
