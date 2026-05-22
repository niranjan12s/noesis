package com.noesis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ReentrantRedisLock {

    private final StringRedisTemplate redis;

    public ReentrantRedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public LockHandle acquire(String key, String owner, Duration ttl) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key, owner, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return null;
        }
        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "lock-refresh-" + key.hashCode()); t.setDaemon(true); return t; }
        );
        long refreshMs = ttl.toMillis() / 3;
        refresher.scheduleAtFixedRate(() -> {
            try {
                String currentOwner = redis.opsForValue().get(key);
                if (owner.equals(currentOwner)) {
                    redis.expire(key, ttl);
                }
            } catch (Exception e) {
                log.debug("Lock refresh failed for {}", key);
            }
        }, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
        return new LockHandle(key, refresher);
    }

    public record LockHandle(String key, ScheduledExecutorService refresher) {
        public void release(StringRedisTemplate redis) {
            refresher.shutdown();
            redis.delete(key);
        }
    }
}
