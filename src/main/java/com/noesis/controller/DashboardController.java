package com.noesis.controller;

import com.noesis.cache.DocumentDeletionCache;
import com.noesis.entity.AssertionEntity;
import com.noesis.entity.DocumentEntity;
import com.noesis.entity.EdgeEntity;
import com.noesis.entity.IngestionRunEntity;
import com.noesis.entity.NodeEntity;
import com.noesis.repository.AssertionJpaRepository;
import com.noesis.repository.DocumentRepository;
import com.noesis.repository.EdgeJpaRepository;
import com.noesis.repository.IngestionRunRepository;
import com.noesis.repository.NodeJpaRepository;
import com.noesis.service.DashboardMetricsStore;
import com.noesis.service.MetricsSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardMetricsStore metricsStore;
    private final MetricsSseService metricsSseService;
    private final NodeJpaRepository nodeRepository;
    private final EdgeJpaRepository edgeRepository;
    private final AssertionJpaRepository assertionRepository;
    private final DocumentRepository documentRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final DocumentDeletionCache deletionCache;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(metricsStore.getMetricsSummary());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return metricsSseService.createEmitter();
    }

    @GetMapping("/graph")
    public ResponseEntity<Map<String, Object>> getGraph(@RequestParam(required = false) String project) {
        List<EdgeEntity> allEdges = edgeRepository.findAll();
        List<EdgeEntity> activeEdges = allEdges;

        // Filter by project if specified
        if (project != null && !project.isBlank()) {
            Set<UUID> projectRunIds = new HashSet<>();
            for (DocumentEntity doc : documentRepository.findAll()) {
                String docProject = doc.getProjectRoot();
                if (docProject == null || docProject.isBlank()) {
                    try {
                        docProject = com.noesis.service.DocumentIngestionService
                                .deriveProjectRoot(java.nio.file.Paths.get(doc.getAbsolutePath()));
                    } catch (Exception ignored) {}
                }
                if (project.equals(docProject)) {
                    try {
                        projectRunIds.addAll(
                            ingestionRunRepository.findByDocumentId(UUID.fromString(doc.getId()))
                                .stream().map(IngestionRunEntity::getId).toList()
                        );
                    } catch (Exception ignored) {}
                }
            }
            activeEdges = allEdges.stream()
                    .filter(e -> project.equals(e.getProjectRoot()) || projectRunIds.contains(e.getIngestionRunId()))
                    .toList();
        }

        // Filter out edges from deleted documents
        if (deletionCache.size() > 0) {
            Set<UUID> activeRunIds = assertionRepository.findAll().stream()
                    .filter(a -> !deletionCache.isDeleted(a.getDocumentId().toString()))
                    .map(AssertionEntity::getIngestionRunId)
                    .collect(Collectors.toSet());
            activeEdges = activeEdges.stream()
                    .filter(e -> activeRunIds.contains(e.getIngestionRunId()))
                    .toList();
        }

        Set<UUID> activeNodeIds = activeEdges.stream()
                .flatMap(e -> Stream.of(e.getFromNodeId(), e.getToNodeId()))
                .collect(Collectors.toSet());

        List<NodeEntity> allNodes = nodeRepository.findAll();
        List<NodeEntity> activeNodes = allNodes.stream()
                .filter(n -> activeNodeIds.contains(n.getId()))
                .toList();

        List<Map<String, Object>> d3Nodes = new ArrayList<>();
        for (NodeEntity node : activeNodes) {
            d3Nodes.add(Map.of(
                    "id", node.getId().toString(),
                    "name", node.getCanonicalName(),
                    "normalizedName", node.getNormalizedName()
            ));
        }

        List<Map<String, Object>> d3Links = new ArrayList<>();
        for (EdgeEntity edge : activeEdges) {
            d3Links.add(Map.of(
                    "id", edge.getId().toString(),
                    "source", edge.getFromNodeId().toString(),
                    "target", edge.getToNodeId().toString(),
                    "label", edge.getPredicate()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "nodes", d3Nodes,
                "links", d3Links
        ));
    }

    @GetMapping("/node/{id}/trace")
    public ResponseEntity<List<Map<String, Object>>> traceNode(@PathVariable String id) {
        UUID nodeId = UUID.fromString(id);

        List<AssertionEntity> assertions = assertionRepository.findAll();
        List<Map<String, Object>> traceList = new ArrayList<>();

        for (AssertionEntity ass : assertions) {
            if (deletionCache.isDeleted(ass.getDocumentId().toString())) continue;
            if (nodeId.equals(ass.getSubjectNodeId()) || nodeId.equals(ass.getObjectNodeId())) {
                DocumentEntity doc = documentRepository.findById(ass.getDocumentId().toString()).orElse(null);
                String docName = doc != null ? doc.getName() : "Unknown Document";
                String docPath = doc != null ? doc.getAbsolutePath() : "";

                Map<String, Object> map = new HashMap<>();
                map.put("assertionId", ass.getId().toString());
                map.put("subject", ass.getSubject());
                map.put("predicate", ass.getPredicate());
                map.put("object", ass.getObject());
                map.put("rawText", ass.getRawText());
                map.put("normalizedText", ass.getNormalizedText());
                map.put("documentName", docName);
                map.put("documentPath", docPath);
                traceList.add(map);
            }
        }
        return ResponseEntity.ok(traceList);
    }

    @GetMapping("/edge/{id}/trace")
    public ResponseEntity<List<Map<String, Object>>> traceEdge(@PathVariable String id) {
        UUID edgeId = UUID.fromString(id);
        EdgeEntity edge = edgeRepository.findById(edgeId).orElse(null);
        if (edge == null) {
            return ResponseEntity.notFound().build();
        }

        List<AssertionEntity> assertions = assertionRepository.findAll();
        List<Map<String, Object>> traceList = new ArrayList<>();

        for (AssertionEntity ass : assertions) {
            if (deletionCache.isDeleted(ass.getDocumentId().toString())) continue;
            boolean subjectMatches = edge.getFromNodeId().equals(ass.getSubjectNodeId());
            boolean objectMatches = edge.getToNodeId().equals(ass.getObjectNodeId());
            boolean predicateMatches = edge.getPredicate().equalsIgnoreCase(ass.getPredicate());

            if (subjectMatches && objectMatches && predicateMatches) {
                DocumentEntity doc = documentRepository.findById(ass.getDocumentId().toString()).orElse(null);
                String docName = doc != null ? doc.getName() : "Unknown Document";
                String docPath = doc != null ? doc.getAbsolutePath() : "";

                Map<String, Object> map = new HashMap<>();
                map.put("assertionId", ass.getId().toString());
                map.put("subject", ass.getSubject());
                map.put("predicate", ass.getPredicate());
                map.put("object", ass.getObject());
                map.put("rawText", ass.getRawText());
                map.put("normalizedText", ass.getNormalizedText());
                map.put("documentName", docName);
                map.put("documentPath", docPath);
                traceList.add(map);
            }
        }
        return ResponseEntity.ok(traceList);
    }
}
