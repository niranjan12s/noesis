package com.noesis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.noesis.config.NoesisProperties;

/**
 * Entry point for the Noesis Platform.
 *
 * <p>Noesis converts unstructured documents into a versioned, queryable semantic
 * knowledge graph backed by Kafka, OpenSearch, and a local Llama 3.2 LLM.</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(NoesisProperties.class)
public class NoesisApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoesisApplication.class, args);
    }
}
