package com.noesis.producer;

import com.noesis.config.KafkaTopics;
import com.noesis.events.AssertionGeneratedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkAssertionProducer {

    private final KafkaTemplate<String, AssertionGeneratedEvent> kafkaTemplate;

    public void sendAssertionGenerated(AssertionGeneratedEvent event) {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                kafkaTemplate.send(KafkaTopics.ASSERTION_GENERATED_EVENTS, event.getDocumentId(), event).get(10, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                lastEx = e;
                if (attempt < 3) {
                    long delayMs = (long) Math.min(1000L * (1L << (attempt - 1)), 10000L);
                    log.warn("Kafka assertion send failed (attempt {}/3), retrying in {}ms: {}", attempt, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
        log.error("Failed to send assertion generated event after 3 attempts", lastEx);
    }
}
