package com.noesis.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.KeywordProperty;
import org.opensearch.client.opensearch._types.mapping.TextProperty;
import org.opensearch.client.opensearch._types.mapping.DateProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchIndexInitializer {

    private final OpenSearchClient openSearchClient;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndices() {
        log.info("Initializing OpenSearch indices and mappings...");
        try {
            ensureAssertionIndex();
            ensureEdgeIndex();
            log.info("OpenSearch indices successfully initialized.");
        } catch (Exception e) {
            log.error("Failed to initialize OpenSearch indices. Mappings may fallback to dynamic.", e);
        }
    }

    private void ensureAssertionIndex() throws Exception {
        String indexName = "assertion-index";
        boolean exists = openSearchClient.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
        if (!exists) {
            log.info("Creating OpenSearch index: '{}' with explicit mappings...", indexName);

            Map<String, Property> properties = new HashMap<>();
            properties.put("assertionId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("documentId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("subjectText", Property.of(p -> p.text(TextProperty.of(t -> t.fields("keyword", f -> f.keyword(k -> k))))));
            properties.put("predicate", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("objectText", Property.of(p -> p.text(TextProperty.of(t -> t.fields("keyword", f -> f.keyword(k -> k))))));
            properties.put("normalizedText", Property.of(p -> p.text(TextProperty.of(t -> t))));
            properties.put("rawText", Property.of(p -> p.text(TextProperty.of(t -> t))));
            properties.put("subjectNodeId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("objectNodeId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("timestamp", Property.of(p -> p.date(DateProperty.of(d -> d))));

            TypeMapping mapping = TypeMapping.of(m -> m.properties(properties));

            CreateIndexRequest request = CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .mappings(mapping)
            );

            openSearchClient.indices().create(request);
            log.info("Successfully created index: '{}'", indexName);
        } else {
            log.info("OpenSearch index '{}' already exists.", indexName);
        }
    }

    private void ensureEdgeIndex() throws Exception {
        String indexName = "edge-index";
        boolean exists = openSearchClient.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
        if (!exists) {
            log.info("Creating OpenSearch index: '{}' with explicit mappings...", indexName);

            Map<String, Property> properties = new HashMap<>();
            properties.put("edgeId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("fromNodeId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("toNodeId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
            properties.put("predicate", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));

            TypeMapping mapping = TypeMapping.of(m -> m.properties(properties));

            CreateIndexRequest request = CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .mappings(mapping)
            );

            openSearchClient.indices().create(request);
            log.info("Successfully created index: '{}'", indexName);
        } else {
            log.info("OpenSearch index '{}' already exists.", indexName);
        }
    }
}
