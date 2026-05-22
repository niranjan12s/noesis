package com.noesis.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileWatcherService {

    private final DocumentIngestionService documentIngestionService;
    private final NoesisConfigService noesisConfigService;
    private final ModeService modeService;

    @Value("${noesis.watch.debounce-create-ms:100}")
    private long debounceCreateMs;

    @Value("${noesis.watch.debounce-modify-ms:200}")
    private long debounceModifyMs;

    private WatchService watchService;
    private Thread watchThread;
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "noesis-watcher-debouncer");
            t.setDaemon(true);
            return t;
        }
    );
    private final ExecutorService workerPool = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            Path rootPath = Paths.get("").toAbsolutePath();
            watchService = FileSystems.getDefault().newWatchService();
            
            // Recursively register all directories
            registerRecursive(rootPath);
            
            watchThread = Thread.ofVirtual()
                .name("noesis-file-watcher")
                .start(this::watchDirectory);
            
            log.info("Successfully registered recursive watcher at repository root: {}", rootPath);
        } catch (IOException e) {
            log.error("Failed to initialize recursive file watcher", e);
        }
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isExcludedDirectory(dir)) {
                    log.debug("Skipping excluded directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                
                dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                log.debug("Registered watcher for: {}", dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcludedDirectory(Path dir) {
        Path root = Paths.get("").toAbsolutePath();
        Path relativePath;
        try {
            relativePath = root.relativize(dir.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            relativePath = dir.toAbsolutePath();
        }
        
        String pathStr = relativePath.toString().replace('\\', '/');
        if (pathStr.isEmpty()) return false;
        
        // Match specific root directories that must never be traversed
        if (pathStr.equals(".git") || pathStr.startsWith(".git/") ||
            pathStr.equals("node_modules") || pathStr.startsWith("node_modules/") ||
            pathStr.equals("build") || pathStr.startsWith("build/") ||
            pathStr.equals("logs") || pathStr.startsWith("logs/") ||
            pathStr.equals(".noesis") || pathStr.startsWith(".noesis/")) {
            return true;
        }
        
        return false;
    }

    private void watchDirectory() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                log.info("File watcher thread interrupted");
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException x) {
                log.info("File watcher closed");
                return;
            }

            Path parentDir = (Path) key.watchable();

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path child = parentDir.resolve(filename);
                
                if (Files.isDirectory(child)) {
                    // If a new directory is created, register it and all its children
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        try {
                            if (!isExcludedDirectory(child)) {
                                registerRecursive(child);
                            }
                        } catch (IOException e) {
                            log.error("Failed to register recursive watch for newly created directory: {}", child, e);
                        }
                    }
                    continue;
                }

                // Match against include/exclude rules
                if ("bulk".equals(modeService.getCurrentMode())) {
                    log.debug("Skipping standard file watcher event in bulk mode: {}", child.getFileName());
                    continue;
                }
                if (noesisConfigService.shouldIndex(child)) {
                    log.info("File matched indexing rules: {}", child.getFileName());
                    handleEventWithDebounce(child, kind);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("Watch key no longer valid for directory: {}", parentDir);
            }
        }
    }

    private void handleEventWithDebounce(Path filePath, WatchEvent.Kind<?> kind) {
        // True debounce: Cancel any active scheduled task for this file
        ScheduledFuture<?> existing = scheduledTasks.remove(filePath);
        if (existing != null) {
            existing.cancel(false);
            log.debug("Debounce: cancelled pending index task for {}", filePath.getFileName());
        }

        // Different debounce for CREATE (100ms) vs MODIFY (200ms) to balance speed vs safety
        long delayMs = kind == StandardWatchEventKinds.ENTRY_CREATE ? debounceCreateMs : debounceModifyMs;

        // Schedule new execution task — offload to worker pool so debouncer thread is never blocked
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            scheduledTasks.remove(filePath);
            log.info("Debounce elapsed after {}ms. Processing file event for {}", delayMs, filePath.getFileName());
            workerPool.submit(() -> {
                try {
                    if ("bulk".equals(modeService.getCurrentMode())) {
                        log.info("Skipping debounced standard file watcher task as system is now in bulk mode: {}", filePath.getFileName());
                        return;
                    }
                    documentIngestionService.processFileEvent(filePath);
                } catch (Exception e) {
                    log.error("Failed to process debounced file event", e);
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);

        scheduledTasks.put(filePath, future);
    }

    @PreDestroy
    public void destroy() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Error closing watch service", e);
            }
        }
        scheduler.shutdownNow();
        workerPool.shutdownNow();
    }
}
