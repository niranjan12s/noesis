package com.noesis.controller;

import com.noesis.dto.CandidateAssertion;
import com.noesis.dto.PathExplanationResponse;
import com.noesis.entity.DocumentEntity;
import com.noesis.repository.DocumentRepository;
import com.noesis.service.DocumentIngestionService;
import com.noesis.service.GraphToolService;
import com.noesis.service.MarkdownChunkingService;
import com.noesis.service.AssertionExtractionService;
import com.noesis.service.GraphComponentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class McpToolController {

    private final GraphToolService graphToolService;
    private final DocumentIngestionService documentIngestionService;
    private final MarkdownChunkingService markdownChunkingService;
    private final AssertionExtractionService assertionExtractionService;
    private final GraphComponentService graphComponentService;
    private final DocumentRepository documentRepository;

    @GetMapping("/trigger_ingest")
    public ResponseEntity<String> triggerIngest(@RequestParam String path) {
        log.info("Tool called: trigger_ingest(path={})", path);
        Path filePath = Path.of(path);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ResponseEntity.badRequest().body("File not found: " + path);
        }
        try {
            log.info("Registering document via processFileEvent...");
            String docId = documentIngestionService.processFileEvent(filePath);
            if (docId == null) {
                return ResponseEntity.ok("Already in progress or unchanged: " + path);
            }
            log.info("Running synchronous chunking...");
            markdownChunkingService.processDocumentChunking(docId);
            log.info("Running synchronous assertion extraction...");
            assertionExtractionService.processDocumentAssertions(docId);
            log.info("Running synchronous graph compilation...");
            documentRepository.findById(docId).ifPresent(doc ->
                graphComponentService.buildGraphComponents(doc.getIngestionRunId())
            );
            return ResponseEntity.ok("Ingested: " + path);
        } catch (Exception e) {
            log.error("Failed to ingest document: {}", path, e);
            return ResponseEntity.internalServerError().body("Ingestion failed: " + e.getMessage());
        }
    }

    @GetMapping("/query_graph")
    public ResponseEntity<List<CandidateAssertion>> queryGraph(
            @RequestParam String text,
            @RequestParam(defaultValue = "1") int depth) {
        log.info("Tool called: query_graph(text={}, depth={})", text, depth);
        return ResponseEntity.ok(graphToolService.queryGraph(text, depth));
    }

    @GetMapping("/explain_path_by_assertion_id")
    public ResponseEntity<PathExplanationResponse> explainPathByAssertionId(@RequestParam String assertionId) {
        log.info("Tool called: explain_path_by_assertion_id(assertionId={})", assertionId);
        PathExplanationResponse response = graphToolService.explainPathByAssertionId(assertionId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/get_node_neighbour")
    public ResponseEntity<Map<String, Object>> getNodeNeighbour(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "1") int depth) {
        log.info("Tool called: get_node_neighbour(nodeId={}, depth={})", nodeId, depth);
        return ResponseEntity.ok(graphToolService.getNodeNeighbour(nodeId, depth));
    }

    @GetMapping("/get_assertion_by_id")
    public ResponseEntity<Map<String, Object>> getAssertionById(@RequestParam String assertionId) {
        log.info("Tool called: get_assertion_by_id(assertionId={})", assertionId);
        Map<String, Object> response = graphToolService.getAssertionById(assertionId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
