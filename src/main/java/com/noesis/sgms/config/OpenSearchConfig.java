package com.noesis.sgms.config;

import jakarta.annotation.PreDestroy;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;

/**
 * Configures the official {@link OpenSearchClient} (opensearch-java 2.x)
 * backed by the Apache HTTP Client 5 transport.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OpenSearchConfig {

    private final SgmsProperties properties;
    private org.opensearch.client.transport.OpenSearchTransport transport;

    @Bean
    public OpenSearchClient openSearchClient() throws URISyntaxException {
        SgmsProperties.OpenSearchProperties os = properties.getOpensearch();
        String url = os.getScheme() + "://" + os.getHost() + ":" + os.getPort();

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        transport = ApacheHttpClient5TransportBuilder
                .builder(HttpHost.create(url))
                .setMapper(new JacksonJsonpMapper(mapper))
                .build();

        log.info("OpenSearch client initialised → {}", url);
        return new OpenSearchClient(transport);
    }

    @Bean
    public OpenSearchAsyncClient openSearchAsyncClient() throws URISyntaxException {
        if (transport == null) {
            openSearchClient();
        }
        return new OpenSearchAsyncClient(transport);
    }

    @PreDestroy
    public void closeTransport() {
        if (transport != null) {
            try {
                transport.close();
                log.info("OpenSearch transport closed");
            } catch (Exception e) {
                log.error("Error closing OpenSearch transport", e);
            }
        }
    }
}
