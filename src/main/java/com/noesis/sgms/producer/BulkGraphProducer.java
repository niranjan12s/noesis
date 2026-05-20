package com.noesis.sgms.producer;

import com.noesis.sgms.config.KafkaTopics;
import com.noesis.sgms.events.GraphUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BulkGraphProducer {

    private final KafkaTemplate<String, GraphUpdateEvent> kafkaTemplate;

    public void sendGraphUpdate(GraphUpdateEvent event) {
        kafkaTemplate.send(KafkaTopics.GRAPH_UPDATE_EVENTS, event.getDocumentId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) log.error("Failed to send graph update event", ex);
            });
    }
}
