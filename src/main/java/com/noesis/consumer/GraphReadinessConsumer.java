package com.noesis.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import java.util.Map;
import org.springframework.kafka.support.Acknowledgment;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphReadinessConsumer {

    @KafkaListener(topics = "${app.kafka.topics.ingestion-events:noesis-ingestion-events}", groupId = "noesis-graph-readiness-group")
    public void consumeIndexCompletion(Map<String, Object> payload, Acknowledgment ack) {
        log.debug("Kafka consumer disabled — QUERYABLE marking handled by GraphComponentService directly. Event: {}", payload != null ? payload.get("eventType") : null);
        if (ack != null) ack.acknowledge();
    }
}
