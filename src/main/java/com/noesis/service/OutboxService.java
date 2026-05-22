package com.noesis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.entity.OutboxEvent;
import com.noesis.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(String topic, String key, Object event, String aggregateType, String aggregateId) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(event.getClass().getSimpleName())
                    .payload(payload)
                    .topic(topic)
                    .keyStr(key)
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("Failed to persist outbox event: {}", e.getMessage(), e);
        }
    }
}
