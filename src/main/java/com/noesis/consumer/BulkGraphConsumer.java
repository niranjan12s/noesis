package com.noesis.consumer;

import com.noesis.config.KafkaTopics;
import com.noesis.events.AssertionGeneratedEvent;
import com.noesis.service.GraphComponentService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkGraphConsumer {

    private final GraphComponentService graphComponentService;
    private final ModeService modeService;
    private final StringRedisTemplate stringRedisTemplate;
    private final WorkerRegistryService workerRegistryService;
    private final ReentrantRedisLock reentrantRedisLock;

    @KafkaListener(
        topics = KafkaTopics.ASSERTION_GENERATED_EVENTS,
        groupId = "noesis-bulk-graph-group",
        containerFactory = "bulkKafkaListenerContainerFactory"
    )
    public void consume(AssertionGeneratedEvent event, Acknowledgment ack) {
        if (!"bulk".equals(modeService.getCurrentMode()) || !modeService.isBulkJobActive()) {
            ack.acknowledge();
            return;
        }

        String lockKey = "noesis:lock:graph:" + event.getIngestionRunId();
        ReentrantRedisLock.LockHandle lock = reentrantRedisLock.acquire(lockKey, workerRegistryService.getWorkerId(), Duration.ofMinutes(30));

        if (lock != null) {
            try {
                workerRegistryService.incrementLoad();
                graphComponentService.buildGraphComponents(event.getIngestionRunId());
            } catch (Exception e) {
                log.error("Bulk graph building failed for run {}", event.getIngestionRunId(), e);
            } finally {
                lock.release(stringRedisTemplate);
                ack.acknowledge();
                workerRegistryService.decrementLoad();
            }
        } else {
            log.debug("Graph building already in progress or completed for run: {}", event.getIngestionRunId());
            ack.acknowledge();
        }
    }
}
