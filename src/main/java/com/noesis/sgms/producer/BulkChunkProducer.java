package com.noesis.sgms.producer;

import com.noesis.sgms.config.KafkaTopics;
import com.noesis.sgms.events.ChunkCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkChunkProducer {

    private final KafkaTemplate<String, ChunkCreatedEvent> kafkaTemplate;

    public void sendChunkCreated(ChunkCreatedEvent event) {
        kafkaTemplate.send(KafkaTopics.CHUNK_CREATED_EVENTS, event.getDocumentId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) log.error("Failed to send chunk created event", ex);
            });
    }
}
