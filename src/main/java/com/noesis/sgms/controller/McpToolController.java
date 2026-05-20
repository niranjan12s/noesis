package com.noesis.sgms.controller;

import com.noesis.sgms.dto.CandidateAssertion;
import com.noesis.sgms.dto.PathExplanationResponse;
import com.noesis.sgms.service.GraphToolService;
import com.noesis.sgms.entity.DocumentEntity;
import com.noesis.sgms.repository.DocumentRepository;
import com.noesis.sgms.service.MarkdownChunkingService;
import com.noesis.sgms.service.AssertionExtractionService;
import com.noesis.sgms.service.GraphComponentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class McpToolController {

    private final GraphToolService graphToolService;
    private final MarkdownChunkingService markdownChunkingService;
    private final AssertionExtractionService assertionExtractionService;
    private final GraphComponentService graphComponentService;
    private final DocumentRepository documentRepository;

    @GetMapping("/trigger_ingest")
    public ResponseEntity<String> triggerIngest(@RequestParam String docName) {
        log.info("Tool called: trigger_ingest(docName={})", docName);
        Optional<DocumentEntity> docOpt = documentRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase(docName))
                .findFirst();

        if (docOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Document not found: " + docName);
        }

        DocumentEntity doc = docOpt.get();
        try {
            log.info("Running synchronous chunking...");
            markdownChunkingService.processDocumentChunking(doc.getId());
            log.info("Running synchronous assertion extraction...");
            assertionExtractionService.processDocumentAssertions(doc.getId());
            log.info("Running synchronous graph compilation...");
            graphComponentService.buildGraphComponents(doc.getIngestionRunId());
            return ResponseEntity.ok("Successfully ingested document: " + docName);
        } catch (Exception e) {
            log.error("Failed to synchronously ingest document: {}", docName, e);
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
