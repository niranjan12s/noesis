package com.noesis.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkProgressStore {

    private final GraphSseService graphSseService;
    private final WorkerRegistryService workerRegistryService;
    private final StringRedisTemplate redisTemplate;

    private final AtomicInteger filesDiscovered = new AtomicInteger(0);
    private final AtomicInteger filesProcessed = new AtomicInteger(0);
    private final AtomicInteger filesFailed = new AtomicInteger(0);
    private final AtomicInteger assertionsGenerated = new AtomicInteger(0);
    private final AtomicInteger edgesGenerated = new AtomicInteger(0);
    private final AtomicInteger queueBacklog = new AtomicInteger(0);

    private volatile double throughputDocsSec = 0.0;
    private volatile int etaSeconds = 0;

    private volatile long jobStartTime = 0;
    private volatile int prevProcessed = 0;
    private volatile long prevTime = 0;

    @PostConstruct
    public void init() {
        loadFromRedis();
    }

    public void loadFromRedis() {
        try {
            filesDiscovered.set(getInt("noesis:bulk:discovered", 0));
            filesProcessed.set(getInt("noesis:bulk:processed", 0));
            filesFailed.set(getInt("noesis:bulk:failed", 0));
            assertionsGenerated.set(getInt("noesis:bulk:assertions", 0));
            edgesGenerated.set(getInt("noesis:bulk:edges", 0));
            queueBacklog.set(getInt("noesis:bulk:backlog", 0));
            throughputDocsSec = getDouble("noesis:bulk:throughput", 0.0);
            etaSeconds = getInt("noesis:bulk:eta", 0);
            log.info("Successfully loaded bulk progress metrics from Redis");
        } catch (Exception e) {
            log.error("Failed to load bulk progress from Redis", e);
        }
    }

    private int getInt(String key, int defaultValue) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    private double getDouble(String key, double defaultValue) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Double.parseDouble(val) : defaultValue;
    }

    public void reset() {
        filesDiscovered.set(0);
        filesProcessed.set(0);
        filesFailed.set(0);
        assertionsGenerated.set(0);
        edgesGenerated.set(0);
        queueBacklog.set(0);
        throughputDocsSec = 0.0;
        etaSeconds = 0;
        jobStartTime = System.currentTimeMillis();
        prevProcessed = 0;
        prevTime = System.currentTimeMillis();

        persistAll();
        broadcast();
    }

    private void persistAll() {
        try {
            redisTemplate.opsForValue().set("noesis:bulk:discovered", String.valueOf(filesDiscovered.get()));
            redisTemplate.opsForValue().set("noesis:bulk:processed", String.valueOf(filesProcessed.get()));
            redisTemplate.opsForValue().set("noesis:bulk:failed", String.valueOf(filesFailed.get()));
            redisTemplate.opsForValue().set("noesis:bulk:assertions", String.valueOf(assertionsGenerated.get()));
            redisTemplate.opsForValue().set("noesis:bulk:edges", String.valueOf(edgesGenerated.get()));
            redisTemplate.opsForValue().set("noesis:bulk:backlog", String.valueOf(queueBacklog.get()));
            redisTemplate.opsForValue().set("noesis:bulk:throughput", String.valueOf(throughputDocsSec));
            redisTemplate.opsForValue().set("noesis:bulk:eta", String.valueOf(etaSeconds));
        } catch (Exception e) {
            log.error("Failed to persist bulk progress to Redis", e);
        }
    }

    public void incrementDiscovered() {
        int val = filesDiscovered.incrementAndGet();
        redisTemplate.opsForValue().set("noesis:bulk:discovered", String.valueOf(val));
        recalc();
    }

    public void incrementProcessed() {
        int val = filesProcessed.incrementAndGet();
        redisTemplate.opsForValue().set("noesis:bulk:processed", String.valueOf(val));
        recalc();
    }

    public void incrementFailed() {
        int val = filesFailed.incrementAndGet();
        redisTemplate.opsForValue().set("noesis:bulk:failed", String.valueOf(val));
        recalc();
    }

    public void addAssertions(int count) {
        int val = assertionsGenerated.addAndGet(count);
        redisTemplate.opsForValue().set("noesis:bulk:assertions", String.valueOf(val));
        broadcast();
    }

    public void addEdges(int count) {
        int val = edgesGenerated.addAndGet(count);
        redisTemplate.opsForValue().set("noesis:bulk:edges", String.valueOf(val));
        broadcast();
    }

    public void setQueueBacklog(int backlog) {
        queueBacklog.set(backlog);
        redisTemplate.opsForValue().set("noesis:bulk:backlog", String.valueOf(backlog));
    }

    private void recalc() {
        recalcThroughput();
        broadcast();
    }

    public void broadcast() {
        recalcThroughput();
        graphSseService.broadcastBulkProgress(
            filesDiscovered.get(), filesProcessed.get(), filesFailed.get(),
            assertionsGenerated.get(), edgesGenerated.get(), queueBacklog.get(),
            throughputDocsSec, etaSeconds
        );
        graphSseService.broadcastWorkerUpdate(workerRegistryService.getActiveWorkers());
    }

    private void recalcThroughput() {
        long now = System.currentTimeMillis();
        long dt = now - prevTime;
        if (dt > 2000) {
            int processed = filesProcessed.get();
            if (processed > prevProcessed || throughputDocsSec <= 0) {
                throughputDocsSec = (processed - prevProcessed) / (dt / 1000.0);
                if (throughputDocsSec < 0) throughputDocsSec = 0;
            }
            prevProcessed = processed;
            prevTime = now;
            redisTemplate.opsForValue().set("noesis:bulk:throughput", String.valueOf(throughputDocsSec));
        }
        int remaining = filesDiscovered.get() - filesProcessed.get() - filesFailed.get();
        if (remaining > 0 && throughputDocsSec > 0) {
            etaSeconds = (int) (remaining / throughputDocsSec);
        } else {
            etaSeconds = 0;
        }
        redisTemplate.opsForValue().set("noesis:bulk:eta", String.valueOf(etaSeconds));
    }

    public int getFilesDiscovered() { return filesDiscovered.get(); }
    public int getFilesProcessed() { return filesProcessed.get(); }
    public int getFilesFailed() { return filesFailed.get(); }
    public int getAssertionsGenerated() { return assertionsGenerated.get(); }
    public int getEdgesGenerated() { return edgesGenerated.get(); }
    public int getQueueBacklog() { return queueBacklog.get(); }
    public double getThroughputDocsSec() { return throughputDocsSec; }
    public int getEtaSeconds() { return etaSeconds; }
}
