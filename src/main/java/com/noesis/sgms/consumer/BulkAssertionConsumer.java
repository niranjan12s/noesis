package com.noesis.sgms.consumer;

import com.noesis.sgms.config.KafkaTopics;
import com.noesis.sgms.events.ChunkCreatedEvent;
import com.noesis.sgms.service.AssertionExtractionService;
import com.noesis.sgms.service.ModeService;
import com.noesis.sgms.service.WorkerRegistryService;
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

    private final Semaphore concurrencyLimiter = new Semaphore(8);
    private final AtomicInteger inFlight = new AtomicInteger(0);

    @KafkaListener(
        topics = KafkaTopics.CHUNK_CREATED_EVENTS,
        groupId = "sgms-bulk-assertion-group",
        containerFactory = "bulkKafkaListenerContainerFactory"
    )
    public void consume(ChunkCreatedEvent event, Acknowledgment ack) {
        if (!"bulk".equals(modeService.getCurrentMode()) || !modeService.isBulkJobActive()) {
            ack.acknowledge();
            return;
        }

        String lockKey = "neosis:lock:assertion:" + event.getDocumentId();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofMinutes(30));

        if (Boolean.TRUE.equals(acquired)) {
            try {
                concurrencyLimiter.acquire();
                inFlight.incrementAndGet();
                workerRegistryService.incrementLoad();
                assertionExtractionService.processDocumentAssertions(event.getDocumentId());
            } catch (Exception e) {
                log.error("Bulk assertion extraction failed for chunk {}", event.getChunkId(), e);
            } finally {
                stringRedisTemplate.delete(lockKey);
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
