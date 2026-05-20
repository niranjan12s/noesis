package com.noesis.sgms.controller;

import com.noesis.sgms.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/bulk")
@RequiredArgsConstructor
public class BulkController {

    private final ModeService modeService;
    private final WorkerRegistryService workerRegistryService;
    private final BulkFileWatcher bulkFileWatcher;
    private final GraphSseService graphSseService;
    private final RedisDedupService redisDedupService;
    private final BulkProgressStore bulkProgressStore;

    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getMode() {
        return ResponseEntity.ok(Map.of(
            "mode", modeService.getCurrentMode(),
            "bulkDirectory", modeService.getBulkDirectory(),
            "bulkJobActive", modeService.isBulkJobActive()
        ));
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, String>> setMode(@RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode == null || (!"realtime".equals(mode) && !"bulk".equals(mode))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Mode must be 'realtime' or 'bulk'"));
        }
        if ("bulk".equals(mode)) {
            modeService.setMode("bulk");
        } else {
            if (modeService.isBulkJobActive()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot switch to realtime while bulk job is active"));
            }
            bulkFileWatcher.stopWatching();
            modeService.setMode("realtime");
        }
        return ResponseEntity.ok(Map.of("mode", modeService.getCurrentMode()));
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startBulk(@RequestBody Map<String, String> body) {
        if (!"bulk".equals(modeService.getCurrentMode())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not in bulk mode"));
        }
        String directory = body.get("directory");
        if (directory == null || directory.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "directory is required"));
        }
        modeService.setBulkDirectory(directory);
        modeService.startBulkJob();
        bulkProgressStore.reset();
        bulkFileWatcher.startWatching(directory);
        return ResponseEntity.ok(Map.of("status", "started", "directory", directory));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopBulk() {
        bulkFileWatcher.stopWatching();
        modeService.stopBulkJob();
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @GetMapping("/workers")
    public ResponseEntity<?> getWorkers() {
        return ResponseEntity.ok(workerRegistryService.getActiveWorkers());
    }

    @GetMapping("/progress")
    public ResponseEntity<SseEmitter> streamProgress() {
        SseEmitter emitter = graphSseService.createEmitter();
        bulkProgressStore.broadcast();
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    @PostMapping("/clear-dedup")
    public ResponseEntity<Map<String, String>> clearDedup() {
        redisDedupService.clearAll();
        return ResponseEntity.ok(Map.of("status", "cleared"));
    }
}
