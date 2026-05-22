package com.noesis.producer;

import com.noesis.config.KafkaTopics;
import com.noesis.events.ChunkCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkChunkProducer {

    private final KafkaTemplate<String, ChunkCreatedEvent> kafkaTemplate;

    public void sendChunkCreated(ChunkCreatedEvent event) {
        sendWithRetry(KafkaTopics.CHUNK_CREATED_EVENTS, event.getDocumentId(), event, 3);
    }

    private void sendWithRetry(String topic, String key, ChunkCreatedEvent event, int maxAttempts) {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                kafkaTemplate.send(topic, key, event).get(10, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                lastEx = e;
                if (attempt < maxAttempts) {
                    long delayMs = (long) Math.min(1000L * (1L << (attempt - 1)), 10000L);
                    log.warn("Kafka send failed (attempt {}/{}), retrying in {}ms: {}", attempt, maxAttempts, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
        log.error("Failed to send event to {} after {} attempts", topic, maxAttempts, lastEx);
    }
}
