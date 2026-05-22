package com.noesis.consumer;

import com.noesis.config.KafkaTopics;
import com.noesis.events.ChunkCreatedEvent;
import com.noesis.service.AssertionExtractionService;
import com.noesis.service.ModeService;
import com.noesis.service.ReentrantRedisLock;
import com.noesis.service.WorkerRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkAssertionConsumer {

    private final AssertionExtractionService assertionExtractionService;
    private final ModeService modeService;
    private final StringRedisTemplate stringRedisTemplate;
    private final WorkerRegistryService workerRegistryService;
    private final ReentrantRedisLock reentrantRedisLock;

    private final Semaphore concurrencyLimiter = new Semaphore(8);
    private final AtomicInteger inFlight = new AtomicInteger(0);

    @KafkaListener(
        topics = KafkaTopics.CHUNK_CREATED_EVENTS,
        groupId = "noesis-bulk-assertion-group",
        containerFactory = "bulkKafkaListenerContainerFactory"
    )
    public void consume(ChunkCreatedEvent event, Acknowledgment ack) {
        if (!"bulk".equals(modeService.getCurrentMode()) || !modeService.isBulkJobActive()) {
            ack.acknowledge();
            return;
        }

        String lockKey = "noesis:lock:assertion:" + event.getDocumentId();
        ReentrantRedisLock.LockHandle lock = reentrantRedisLock.acquire(lockKey, workerRegistryService.getWorkerId(), Duration.ofMinutes(30));

        if (lock != null) {
            try {
                concurrencyLimiter.acquire();
                inFlight.incrementAndGet();
                workerRegistryService.incrementLoad();
                assertionExtractionService.processDocumentAssertions(event.getDocumentId());
            } catch (Exception e) {
                log.error("Bulk assertion extraction failed for chunk {}", event.getChunkId(), e);
            } finally {
                lock.release(stringRedisTemplate);
                ack.acknowledge();
                workerRegistryService.decrementLoad();
                concurrencyLimiter.release();
                inFlight.decrementAndGet();
            }
        } else {
            log.debug("Assertion extraction already in progress or completed for document: {}", event.getDocumentId());
            ack.acknowledge();
        }
    }
}
