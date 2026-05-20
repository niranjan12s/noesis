package com.noesis.sgms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.noesis.sgms.config.SgmsProperties;

/**
 * Entry point for the Semantic Graph Memory System (SGMS).
 *
 * <p>SGMS converts unstructured documents into a versioned, queryable semantic
 * knowledge graph backed by Kafka, OpenSearch, and a local Llama 3.2 LLM.</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(SgmsProperties.class)
public class SgmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SgmsApplication.class, args);
    }
}
