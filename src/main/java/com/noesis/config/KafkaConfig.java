package com.noesis.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.JsonMessageConverter;

/**
 * Kafka configuration: declares all six Noesis topics and configures
 * JSON message conversion for producers and consumers.
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    // ── Topic declarations ────────────────────────────────────────────────

    @Bean
    public NewTopic chunkCreatedEventsTopic() {
        return TopicBuilder.name(KafkaTopics.CHUNK_CREATED_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic assertionGeneratedEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ASSERTION_GENERATED_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic graphUpdateEventsTopic() {
        return TopicBuilder.name(KafkaTopics.GRAPH_UPDATE_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic clusterUpdateEventsTopic() {
        return TopicBuilder.name(KafkaTopics.CLUSTER_UPDATE_EVENTS)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic versionUpdateEventsTopic() {
        return TopicBuilder.name(KafkaTopics.VERSION_UPDATE_EVENTS)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /** JSON message converter for {@code @KafkaListener} methods. */
    @Bean
    public JsonMessageConverter jsonMessageConverter() {
        return new JsonMessageConverter();
    }
}
