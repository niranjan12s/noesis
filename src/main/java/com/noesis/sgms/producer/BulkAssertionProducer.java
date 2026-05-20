package com.noesis.sgms.producer;

import com.noesis.sgms.config.KafkaTopics;
import com.noesis.sgms.events.AssertionGeneratedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkAssertionProducer {

    private final KafkaTemplate<String, AssertionGeneratedEvent> kafkaTemplate;

    public void sendAssertionGenerated(AssertionGeneratedEvent event) {
        kafkaTemplate.send(KafkaTopics.ASSERTION_GENERATED_EVENTS, event.getDocumentId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) log.error("Failed to send assertion generated event", ex);
            });
    }
}
