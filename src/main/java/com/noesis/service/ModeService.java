package com.noesis.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModeService {

    private static final String MODE_KEY = "noesis:mode";
    private static final String MODE_DIR_KEY = "noesis:mode:bulk-dir";
    private static final String MODE_BULK_ACTIVE_KEY = "noesis:mode:bulk-active";
    private static final Duration MODE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;

    @Getter
    private volatile String currentMode = "realtime";
    @Getter
    private volatile String bulkDirectory = "";
    @Getter
    private volatile boolean bulkJobActive = false;

    @PostConstruct
    public void init() {
        String stored = stringRedisTemplate.opsForValue().get(MODE_KEY);
        if (stored != null) {
            currentMode = stored;
        }
        String dir = stringRedisTemplate.opsForValue().get(MODE_DIR_KEY);
        if (dir != null) {
            bulkDirectory = dir;
        }
        String active = stringRedisTemplate.opsForValue().get(MODE_BULK_ACTIVE_KEY);
        if (active != null) {
            bulkJobActive = "true".equals(active);
        }
        log.info("ModeService initialized: mode={}, bulkDir={}, jobActive={}", currentMode, bulkDirectory, bulkJobActive);
    }

    public void setMode(String mode) {
        if (!"realtime".equals(mode) && !"bulk".equals(mode)) {
            throw new IllegalArgumentException("Mode must be 'realtime' or 'bulk'");
        }
        this.currentMode = mode;
        stringRedisTemplate.opsForValue().set(MODE_KEY, mode, MODE_TTL);
        log.info("Mode switched to: {}", mode);
    }

    public void setBulkDirectory(String dir) {
        this.bulkDirectory = dir;
        stringRedisTemplate.opsForValue().set(MODE_DIR_KEY, dir, MODE_TTL);
        log.info("Bulk directory set to: {}", dir);
    }

    public boolean startBulkJob() {
        if (!"bulk".equals(currentMode)) {
            log.warn("Cannot start bulk job: not in bulk mode");
            return false;
        }
        this.bulkJobActive = true;
        stringRedisTemplate.opsForValue().set(MODE_BULK_ACTIVE_KEY, "true", MODE_TTL);
        log.info("Bulk job started");
        return true;
    }

    public void stopBulkJob() {
        this.bulkJobActive = false;
        stringRedisTemplate.delete(MODE_BULK_ACTIVE_KEY);
        log.info("Bulk job stopped");
    }
}
