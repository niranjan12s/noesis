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
import java.util.List;
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
        startWatching();
    }

    public synchronized void startWatching() {
        if (watchService != null || watchThread != null) {
            log.warn("FileWatcherService is already running");
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            List<String> customDirs = noesisConfigService.getWatchDirectories();
            
            if (customDirs == null || customDirs.isEmpty()) {
                Path rootPath = Paths.get("").toAbsolutePath();
                registerRecursive(rootPath);
                log.info("Successfully registered recursive watcher at repository root: {}", rootPath);
            } else {
                for (String dirStr : customDirs) {
                    Path customPath = Paths.get(dirStr).toAbsolutePath().normalize();
                    if (Files.isDirectory(customPath)) {
                        registerRecursive(customPath);
                        log.info("Successfully registered recursive watcher at configured path: {}", customPath);
                    } else {
                        log.warn("Configured watch directory does not exist or is not a directory: {}", dirStr);
                    }
                }
            }
            
            watchThread = Thread.ofVirtual()
                .name("noesis-file-watcher")
                .start(this::watchDirectory);
        } catch (IOException e) {
            log.error("Failed to initialize recursive file watcher", e);
        }
    }

    public synchronized void stopWatching() {
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Error closing watch service", e);
            }
            watchService = null;
        }
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        log.info("FileWatcherService stopped");
    }

    public synchronized void restart() {
        log.info("Restarting FileWatcherService to apply new directories...");
        stopWatching();
        startWatching();
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isExcludedDirectory(dir, root)) {
                    log.debug("Skipping excluded directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                
                dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                log.debug("Registered watcher for: {}", dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcludedDirectory(Path dir, Path root) {
        Path relativePath;
        try {
            relativePath = root.toAbsolutePath().normalize().relativize(dir.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            relativePath = dir.toAbsolutePath().normalize();
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
                            Path root = findWatchRootFor(child);
                            if (!isExcludedDirectory(child, root)) {
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

    private Path findWatchRootFor(Path child) {
        Path absoluteChild = child.toAbsolutePath().normalize();
        List<String> customDirs = noesisConfigService.getWatchDirectories();
        if (customDirs != null && !customDirs.isEmpty()) {
            for (String dirStr : customDirs) {
                Path path = Paths.get(dirStr).toAbsolutePath().normalize();
                if (absoluteChild.startsWith(path)) {
                    return path;
                }
            }
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    @PreDestroy
    public void destroy() {
        stopWatching();
        scheduler.shutdownNow();
        workerPool.shutdownNow();
    }
}
