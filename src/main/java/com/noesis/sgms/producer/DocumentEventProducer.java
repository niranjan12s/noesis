package com.noesis.sgms.producer;

import com.noesis.sgms.event.DocumentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentEventProducer {

    private final KafkaTemplate<String, DocumentEvent> kafkaTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Value("${app.kafka.topics.ingestion-events:neosis-ingestion-events}")
    private String ingestionTopic;

    public void sendDocumentEvent(DocumentEvent event) {
        log.info("Sending document event: {}", event);
        kafkaTemplate.send(ingestionTopic, event.getDocumentId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) log.error("Failed to send document event: {}", event, ex);
            });

        try {
            applicationEventPublisher.publishEvent(event);
            log.info("Successfully published local in-process event: {}", event.getEventType());
        } catch (Exception e) {
            log.error("Failed to publish local Spring event: {}", event, e);
        }
    }
}
