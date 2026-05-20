package com.noesis.sgms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import java.util.Map;
import org.springframework.kafka.support.Acknowledgment;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenSearchAssertionWorker {

    @KafkaListener(topics = "${app.kafka.topics.ingestion-events:neosis-ingestion-events}", groupId = "sgms-os-assertion-group")
    public void consumeGraphEvent(Map<String, Object> payload, Acknowledgment ack) {
        log.debug("Kafka consumer disabled — OS indexing handled by GraphComponentService directly. Event: {}", payload != null ? payload.get("eventType") : null);
        if (ack != null) ack.acknowledge();
    }
}
