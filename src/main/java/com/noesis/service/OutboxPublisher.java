package com.noesis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.entity.OutboxEvent;
import com.noesis.repository.OutboxRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, JsonNode> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { Thread t = new Thread(r, "outbox-publisher"); t.setDaemon(true); return t; }
    );

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::publishPending, 5, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdown();
    }

    public void publishPending() {
        try {
            List<OutboxEvent> batch = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAt();
            for (OutboxEvent event : batch) {
                try {
                    JsonNode payload = objectMapper.readTree(event.getPayload());
                    kafkaTemplate.send(event.getTopic(), event.getKeyStr(), payload);
                    event.setPublishedAt(Instant.now());
                    outboxRepository.save(event);
                } catch (Exception e) {
                    log.error("Failed to publish outbox event {} to topic {}", event.getId(), event.getTopic(), e);
                }
            }
        } catch (Exception e) {
            log.error("Outbox publisher iteration failed", e);
        }
    }
}
