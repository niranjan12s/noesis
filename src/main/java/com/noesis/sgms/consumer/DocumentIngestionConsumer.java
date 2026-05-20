package com.noesis.sgms.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.Map;
import org.springframework.kafka.support.Acknowledgment;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionConsumer {

    @KafkaListener(topics = "${app.kafka.topics.ingestion-events:neosis-ingestion-events}", groupId = "sgms-chunking-group")
    public void consumeDocumentEvent(Map<String, Object> payload, Acknowledgment ack) {
        log.debug("Kafka consumer disabled — pipeline driven by Spring events. Event: {}", payload != null ? payload.get("eventType") : null);
        if (ack != null) ack.acknowledge();
    }
}
