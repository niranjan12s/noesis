package com.noesis.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorkerRegistryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ModeService modeService;

    private static final String HEARTBEAT_PREFIX = "noesis:worker:";
    private static final String INFO_PREFIX = "noesis:worker-info:";
    private static final String OWNERSHIP_PREFIX = "noesis:file-owner:";
    private static final String FILE_CHECKSUM_PREFIX = "noesis:file-checksum:";

    @Getter
    private final String workerId = UUID.randomUUID().toString().substring(0, 8);
    @Getter
    private final String instanceName;

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { Thread t = new Thread(r, "worker-heartbeat"); t.setDaemon(true); return t; }
    );

    private final ScheduledExecutorService workerCheckScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { Thread t = new Thread(r, "worker-check"); t.setDaemon(true); return t; }
    );

    @Getter
    private volatile int currentLoad = 0;

    public WorkerRegistryService(StringRedisTemplate stringRedisTemplate, ModeService modeService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.modeService = modeService;
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {}
        this.instanceName = host + "-" + workerId;
    }

    @PostConstruct
    public void init() {
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, 5, TimeUnit.SECONDS);
        workerCheckScheduler.scheduleAtFixedRate(this::checkStaleWorkers, 0, 10, TimeUnit.SECONDS);
        log.info("WorkerRegistryService initialized: workerId={}, instanceName={}", workerId, instanceName);
    }

    private void sendHeartbeat() {
        try {
            stringRedisTemplate.opsForValue().set(
                HEARTBEAT_PREFIX + workerId,
                Instant.now().toString(),
                java.time.Duration.ofSeconds(15)
            );
            stringRedisTemplate.opsForValue().set(
                INFO_PREFIX + workerId,
                instanceName + "|" + currentLoad + "|" + Instant.now().toString(),
                java.time.Duration.ofSeconds(15)
            );
        } catch (Exception e) {
            log.debug("Heartbeat send failed: {}", e.getMessage());
        }
    }

    public boolean isAlive(String workerId) {
        String val = stringRedisTemplate.opsForValue().get(HEARTBEAT_PREFIX + workerId);
        return val != null;
    }

    public List<Map<String, String>> getActiveWorkers() {
        Set<String> keys = stringRedisTemplate.keys(INFO_PREFIX + "*");
        if (keys == null) return List.of();
        List<Map<String, String>> workers = new ArrayList<>();
        for (String key : keys) {
            String wid = key.substring(INFO_PREFIX.length());
            if (!isAlive(wid)) continue;
            String val = stringRedisTemplate.opsForValue().get(key);
            if (val == null) continue;
            String[] parts = val.split("\\|");
            Map<String, String> info = new LinkedHashMap<>();
            info.put("workerId", wid);
            info.put("instanceName", parts.length > 0 ? parts[0] : wid);
            info.put("currentLoad", parts.length > 1 ? parts[1] : "0");
            info.put("lastHeartbeat", parts.length > 2 ? parts[2] : "");
            workers.add(info);
        }
        workers.sort((a, b) -> a.get("instanceName").compareTo(b.get("instanceName")));
        return workers;
    }

    public void incrementLoad() {
        currentLoad++;
    }

    public void decrementLoad() {
        if (currentLoad > 0) currentLoad--;
    }

    private String normalizePath(String filePath) {
        if (filePath == null) return "";
        String normalized = filePath.replace('\\', '/');
        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            normalized = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
        }
        return normalized;
    }

    public String resolveOwner(String filePath) {
        List<Map<String, String>> workers = getActiveWorkers();
        if (workers.isEmpty()) return workerId;
        if (workers.size() == 1) return workers.get(0).get("workerId");

        String normalized = normalizePath(filePath);

        String bestWorker = null;
        long bestWeight = Long.MIN_VALUE;
        for (Map<String, String> w : workers) {
            long weight = hashWeight(w.get("workerId"), normalized);
            if (weight > bestWeight) {
                bestWeight = weight;
                bestWorker = w.get("workerId");
            }
        }
        return bestWorker != null ? bestWorker : workerId;
    }

    public boolean isOwner(String filePath) {
        return workerId.equals(resolveOwner(filePath));
    }

    private long hashWeight(String workerId, String filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((workerId + ":" + filePath).getBytes(StandardCharsets.UTF_8));
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            return (workerId + ":" + filePath).hashCode() & Long.MAX_VALUE;
        }
    }

    public boolean isFileOwnedBy(String filePath, String workerId) {
        String owner = resolveOwner(filePath);
        return owner.equals(workerId);
    }

    private void checkStaleWorkers() {
        if (!"bulk".equals(modeService.getCurrentMode())) return;
        try {
            Set<String> keys = stringRedisTemplate.keys(HEARTBEAT_PREFIX + "*");
            if (keys == null) return;
            for (String key : keys) {
                String wid = key.substring(HEARTBEAT_PREFIX.length());
                String val = stringRedisTemplate.opsForValue().get(key);
                if (val == null) continue;
                try {
                    Instant heartbeat = Instant.parse(val);
                    if (heartbeat.isBefore(Instant.now().minusSeconds(15))) {
                        log.warn("Worker {} is stale (last heartbeat {}), releasing its locks and reassigning files", wid, val);
                        stringRedisTemplate.delete(key);
                        stringRedisTemplate.delete(INFO_PREFIX + wid);

                        // Release all Redis locks held by this stale worker so other workers can pick up the work
                        releaseLocksForWorker(wid);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("Worker check failed: {}", e.getMessage());
        }
    }

    private void releaseLocksForWorker(String workerId) {
        try {
            Set<String> assertionLocks = stringRedisTemplate.keys("noesis:lock:assertion:*");
            if (assertionLocks != null) {
                for (String lockKey : assertionLocks) {
                    String lockVal = stringRedisTemplate.opsForValue().get(lockKey);
                    if (workerId.equals(lockVal)) {
                        stringRedisTemplate.delete(lockKey);
                        log.info("Released assertion lock {} held by stale worker {}", lockKey, workerId);
                    }
                }
            }
            Set<String> graphLocks = stringRedisTemplate.keys("noesis:lock:graph:*");
            if (graphLocks != null) {
                for (String lockKey : graphLocks) {
                    String lockVal = stringRedisTemplate.opsForValue().get(lockKey);
                    if (workerId.equals(lockVal)) {
                        stringRedisTemplate.delete(lockKey);
                        log.info("Released graph lock {} held by stale worker {}", lockKey, workerId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to release locks for stale worker {}: {}", workerId, e.getMessage());
        }
    }
}
