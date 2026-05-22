package com.noesis.controller;

import com.noesis.cache.DocumentDeletionCache;
import com.noesis.entity.DocumentEntity;
import com.noesis.repository.DocumentRepository;
import com.noesis.repository.IngestionRunRepository;
import com.noesis.service.DocumentDeletionService;
import com.noesis.service.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentRepository documentRepository;
    private final DocumentDeletionService deletionService;
    private final IngestionRunRepository ingestionRunRepository;
    private final DocumentDeletionCache deletionCache;
    private final DocumentIngestionService documentIngestionService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
            }
            Path target = Path.of("docs").resolve(filename).toAbsolutePath();
            if (!Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Uploaded file: {}", target);
            documentIngestionService.processFileEvent(target);
            return ResponseEntity.ok(Map.of("status", "uploaded", "path", target.toString()));
        } catch (Exception e) {
            log.error("File upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<String>> getDocumentRuns(@PathVariable String id) {
        try {
            List<com.noesis.entity.IngestionRunEntity> runs = ingestionRunRepository.findByDocumentId(UUID.fromString(id));
            return ResponseEntity.ok(runs.stream().map(r -> r.getId().toString()).toList());
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDocuments() {
        List<DocumentEntity> docs = documentRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DocumentEntity doc : docs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", doc.getId());
            entry.put("name", doc.getName());
            entry.put("status", doc.getStatus() != null ? doc.getStatus().name() : "UNKNOWN");
            entry.put("version", doc.getVersion());
            entry.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
            String projectRoot = doc.getProjectRoot();
            if (projectRoot == null || projectRoot.isBlank()) {
                try {
                    projectRoot = DocumentIngestionService.deriveProjectRoot(java.nio.file.Paths.get(doc.getAbsolutePath()));
                } catch (Exception ignored) {}
            }
            entry.put("projectRoot", projectRoot);
            entry.put("markedForDeletionAt", doc.getMarkedForDeletionAt() != null ? doc.getMarkedForDeletionAt().toString() : null);
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public ResponseEntity<Map<String, Object>> markForDeletion(@PathVariable String id) {
        Optional<DocumentEntity> opt = documentRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentEntity doc = opt.get();
        Instant deletionAt = Instant.now().plusSeconds(300);
        doc.setMarkedForDeletionAt(deletionAt);
        documentRepository.save(doc);
        deletionCache.markDeleted(id, deletionAt);
        log.info("Document {} marked for deletion, will be removed after 5 minutes", doc.getName());
        return ResponseEntity.ok(Map.of("status", "ok", "markedForDeletionAt", doc.getMarkedForDeletionAt().toString()));
    }

    @PostMapping("/{id}/restore")
    @Transactional
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String id) {
        Optional<DocumentEntity> opt = documentRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentEntity doc = opt.get();
        doc.setMarkedForDeletionAt(null);
        documentRepository.save(doc);
        deletionCache.restore(id);
        log.info("Document {} restored, deletion cancelled", doc.getName());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/{id}/hard-delete")
    @Transactional
    public ResponseEntity<Map<String, Object>> hardDelete(@PathVariable String id) {
        Optional<DocumentEntity> opt = documentRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentEntity doc = opt.get();
        deletionService.deleteDocumentData(doc);
        return ResponseEntity.ok(Map.of("status", "ok", "deleted", doc.getName()));
    }
}
