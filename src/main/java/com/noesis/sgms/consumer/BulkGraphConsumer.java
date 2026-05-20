package com.noesis.sgms.consumer;

import com.noesis.sgms.config.KafkaTopics;
import com.noesis.sgms.events.AssertionGeneratedEvent;
import com.noesis.sgms.service.GraphComponentService;
import com.noesis.sgms.service.ModeService;
import com.noesis.sgms.service.WorkerRegistryService;
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

    @KafkaListener(
        topics = KafkaTopics.ASSERTION_GENERATED_EVENTS,
        groupId = "sgms-bulk-graph-group",
        containerFactory = "bulkKafkaListenerContainerFactory"
    )
    public void consume(AssertionGeneratedEvent event, Acknowledgment ack) {
        if (!"bulk".equals(modeService.getCurrentMode()) || !modeService.isBulkJobActive()) {
            ack.acknowledge();
            return;
        }

        String lockKey = "neosis:lock:graph:" + event.getIngestionRunId();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofMinutes(30));

        if (Boolean.TRUE.equals(acquired)) {
            try {
                workerRegistryService.incrementLoad();
                graphComponentService.buildGraphComponents(event.getIngestionRunId());
            } catch (Exception e) {
                log.error("Bulk graph building failed for run {}", event.getIngestionRunId(), e);
            } finally {
                stringRedisTemplate.delete(lockKey);
                ack.acknowledge();
                workerRegistryService.decrementLoad();
            }
        } else {
            log.debug("Graph building already in progress or completed for run: {}", event.getIngestionRunId());
            ack.acknowledge();
        }
    }
}
