package com.noesis.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.event.DocumentEvent;
import com.noesis.event.DocumentEventType;
import com.noesis.service.DashboardMetricsStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventConsumer {

    private final DashboardMetricsStore metricsStore;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.ingestion-events:noesis-ingestion-events}",
            groupId = "noesis-dashboard-group"
    )
    public void consumeIngestionEvent(Map<String, Object> payload, Acknowledgment ack) {
        log.info("Dashboard received ingestion event via Kafka: {}", payload);
        try {
            // Map payloads gracefully since Kafka payloads might be map structures
            String eventId = (String) payload.get("eventId");
            String documentId = (String) payload.get("documentId");
            String ingestionRunId = (String) payload.get("ingestionRunId");
            
            DocumentEventType eventType = null;
            if (payload.get("eventType") != null) {
                eventType = DocumentEventType.valueOf(payload.get("eventType").toString());
            }

            Integer version = null;
            if (payload.get("version") != null) {
                version = Double.valueOf(payload.get("version").toString()).intValue();
            }

            Instant timestamp = Instant.now();
            if (payload.get("timestamp") != null) {
                try {
                    timestamp = Instant.parse(payload.get("timestamp").toString());
                } catch (Exception ignored) {}
            }

            DocumentEvent event = DocumentEvent.builder()
                    .eventId(eventId)
                    .documentId(documentId)
                    .ingestionRunId(ingestionRunId)
                    .eventType(eventType)
                    .version(version)
                    .timestamp(timestamp)
                    .build();

            metricsStore.recordEvent(event);

            // Record latency estimates if we can trace timestamps
            if (eventType == DocumentEventType.DOCUMENT_GRAPH_READY && event.getTimestamp() != null) {
                long duration = Instant.now().toEpochMilli() - event.getTimestamp().toEpochMilli();
                if (duration > 0) {
                    metricsStore.recordChunkLatency(duration);
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse and record Kafka ingestion event in metrics store.", e);
        } finally {
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }
}
