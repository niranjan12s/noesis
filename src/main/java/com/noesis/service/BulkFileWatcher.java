package com.noesis.service;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkFileWatcher {

    private final DocumentIngestionService documentIngestionService;
    private final MarkdownChunkingService markdownChunkingService;
    private final WorkerRegistryService workerRegistryService;
    private final RedisDedupService redisDedupService;
    private final ModeService modeService;
    private final GraphSseService graphSseService;
    private final BulkProgressStore bulkProgressStore;

    private WatchService watchService;
    private Thread watchThread;

    @Getter
    private volatile boolean running = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { Thread t = new Thread(r, "bulk-watcher-debouncer"); t.setDaemon(true); return t; }
    );
    private final ExecutorService workerPool = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public synchronized void startWatching(String directoryPath) {
        if (running) {
            log.warn("BulkFileWatcher already running");
            return;
        }
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.isDirectory(dir)) {
                log.error("Bulk directory does not exist: {}", directoryPath);
                return;
            }
            watchService = FileSystems.getDefault().newWatchService();
            registerRecursive(dir);
            watchThread = Thread.ofVirtual()
                .name("bulk-file-watcher")
                .start(this::watchDirectory);
            running = true;
            log.info("BulkFileWatcher started on directory: {}", directoryPath);
            scanExistingFiles(dir);
        } catch (IOException e) {
            log.error("Failed to start BulkFileWatcher", e);
        }
    }

    public synchronized void stopWatching() {
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Error closing bulk watch service", e);
            }
            watchService = null;
        }
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        log.info("BulkFileWatcher stopped");
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || name.equals("node_modules") || name.equals("build") || name.equals("logs")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchDirectory() {
        while (!Thread.currentThread().isInterrupted() && running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException x) {
                return;
            }

            Path parentDir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path child = parentDir.resolve(ev.context());
                if (Files.isDirectory(child)) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        try { registerRecursive(child); } catch (IOException e) {}
                    }
                    continue;
                }
                handleFile(child);
            }
            if (!key.reset()) break;
        }
    }

    private void handleFile(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        if (!(name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".xlsx"))) return;

        ScheduledFuture<?> existing = scheduledTasks.remove(filePath);
        if (existing != null) existing.cancel(false);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            scheduledTasks.remove(filePath);
            workerPool.submit(() -> processFile(filePath));
        }, 200, TimeUnit.MILLISECONDS);
        scheduledTasks.put(filePath, future);
    }

    private void processFile(Path filePath) {
        workerRegistryService.incrementLoad();
        try {
            if (!"bulk".equals(modeService.getCurrentMode()) || !modeService.isBulkJobActive()) return;
            if (!workerRegistryService.isOwner(filePath.toAbsolutePath().toString())) {
                log.debug("Skipping file not owned by this worker: {}", filePath.getFileName());
                return;
            }
            bulkProgressStore.incrementDiscovered();
            String docId = documentIngestionService.processFileEvent(filePath);
            if (docId != null) {
                markdownChunkingService.processDocumentChunking(docId);
            } else {
                bulkProgressStore.incrementProcessed();
            }
        } catch (Exception e) {
            log.error("Failed to process bulk file: {}", filePath, e);
            bulkProgressStore.incrementFailed();
        } finally {
            workerRegistryService.decrementLoad();
        }
    }

    private void scanExistingFiles(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".xlsx")) {
                        workerPool.submit(() -> processFile(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || name.equals("node_modules") || name.equals("build") || name.equals("logs")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan existing files in {}", root, e);
        }
    }

    @PreDestroy
    public void destroy() {
        stopWatching();
        scheduler.shutdownNow();
        workerPool.shutdownNow();
    }
}
