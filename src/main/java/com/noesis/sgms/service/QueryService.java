package com.noesis.sgms.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noesis.sgms.dto.CandidateAssertion;
import com.noesis.sgms.dto.QueryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    private final OpenSearchClient openSearchClient;

    @Value("${sgms.query.top-k:20}")
    private int topK;

    @Value("${sgms.query.lambda:1.0}")
    private double lambda;

    @Value("${sgms.query.alpha:0.3}")
    private double alpha;

    public List<CandidateAssertion> queryGraph(QueryRequest request) {
        log.info("Executing query_graph with text: '{}' at depth: {}", request.getText(), request.getDepth());
        
        Map<String, CandidateAssertion> candidatesMap = new HashMap<>();
        Set<String> allVisitedNodes = new HashSet<>();
        Set<String> currentSeedNodes = new HashSet<>();

        try {
            Query matchQuery = Query.of(q -> q
                    .match(m -> m
                            .field("rawText")
                            .query(FieldValue.of(request.getText()))
                    )
            );

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index("assertion-index")
                    .query(matchQuery)
                    .size(topK)
            );

            SearchResponse<ObjectNode> response = openSearchClient.search(searchRequest, ObjectNode.class);

            for (Hit<ObjectNode> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    String assertionId = hit.source().get("assertionId").asText();
                    String rawText = hit.source().has("rawText") ? hit.source().get("rawText").asText() : "";
                    String subjectNodeId = hit.source().get("subjectNodeId").asText();
                    String objectNodeId = hit.source().get("objectNodeId").asText();

                    double bm25Score = hit.score() != null ? hit.score() : 0.0;
                    
                    CandidateAssertion candidate = CandidateAssertion.builder()
                            .assertionId(assertionId)
                            .rawText(rawText)
                            .depth(0)
                            .bm25Score(bm25Score)
                            .build();

                    candidatesMap.put(assertionId, candidate);
                    
                    currentSeedNodes.add(subjectNodeId);
                    currentSeedNodes.add(objectNodeId);
                }
            }
            
            allVisitedNodes.addAll(currentSeedNodes);
            log.info("Depth 0 found {} seed assertions and {} seed nodes", candidatesMap.size(), currentSeedNodes.size());

            // Graph Traversal for depth > 0
            for (int d = 1; d <= request.getDepth(); d++) {
                if (currentSeedNodes.isEmpty()) {
                    break;
                }
                
                List<FieldValue> seedNodeValues = currentSeedNodes.stream()
                        .map(FieldValue::of)
                        .collect(Collectors.toList());

                // Fetch adjacent edges
                Query edgeQuery = Query.of(q -> q.bool(b -> b.should(List.of(
                        Query.of(s1 -> s1.terms(t -> t.field("fromNodeId").terms(ts -> ts.value(seedNodeValues)))),
                        Query.of(s2 -> s2.terms(t -> t.field("toNodeId").terms(ts -> ts.value(seedNodeValues))))
                ))));

                SearchRequest edgeSearch = SearchRequest.of(s -> s
                        .index("edge-index")
                        .query(edgeQuery)
                        .size(1000)
                );

                SearchResponse<ObjectNode> edgeResponse = openSearchClient.search(edgeSearch, ObjectNode.class);

                Set<String> neighborNodes = new HashSet<>();
                for (Hit<ObjectNode> hit : edgeResponse.hits().hits()) {
                    if (hit.source() != null) {
                        String fromNode = hit.source().get("fromNodeId").asText();
                        String toNode = hit.source().get("toNodeId").asText();
                        
                        if (!allVisitedNodes.contains(fromNode)) neighborNodes.add(fromNode);
                        if (!allVisitedNodes.contains(toNode)) neighborNodes.add(toNode);
                    }
                }

                log.info("Depth {} expanded to {} new neighbor nodes", d, neighborNodes.size());
                
                if (neighborNodes.isEmpty()) {
                    break;
                }

                // Fetch assertions for neighbor nodes
                List<FieldValue> neighborValues = neighborNodes.stream()
                        .map(FieldValue::of)
                        .collect(Collectors.toList());

                Query neighborAssertionQuery = Query.of(q -> q.bool(b -> b.should(List.of(
                        Query.of(s1 -> s1.terms(t -> t.field("subjectNodeId").terms(ts -> ts.value(neighborValues)))),
                        Query.of(s2 -> s2.terms(t -> t.field("objectNodeId").terms(ts -> ts.value(neighborValues))))
                ))));

                SearchRequest neighborSearch = SearchRequest.of(s -> s
                        .index("assertion-index")
                        .query(neighborAssertionQuery)
                        .size(1000)
                );

                SearchResponse<ObjectNode> neighborAssertionResponse = openSearchClient.search(neighborSearch, ObjectNode.class);

                for (Hit<ObjectNode> hit : neighborAssertionResponse.hits().hits()) {
                    if (hit.source() != null) {
                        String assertionId = hit.source().get("assertionId").asText();
                        
                        if (!candidatesMap.containsKey(assertionId)) {
                            String rawText = hit.source().has("rawText") ? hit.source().get("rawText").asText() : "";
                            
                            CandidateAssertion candidate = CandidateAssertion.builder()
                                    .assertionId(assertionId)
                                    .rawText(rawText)
                                    .depth(d)
                                    .bm25Score(0.0) 
                                    .build();

                            candidatesMap.put(assertionId, candidate);
                        }
                    }
                }

                allVisitedNodes.addAll(neighborNodes);
                currentSeedNodes = neighborNodes;
            }

            // Calculate final scores and sort
            List<CandidateAssertion> results = new ArrayList<>(candidatesMap.values());
            for (CandidateAssertion c : results) {
                double graphScore = 1.0 / (1.0 + c.getDepth());
                c.setGraphScore(graphScore);
                
                double finalScore = (c.getBm25Score() * Math.exp(-lambda * c.getDepth())) + (alpha * graphScore);
                c.setFinalScore(finalScore);
            }

            results.sort((c1, c2) -> Double.compare(c2.getFinalScore(), c1.getFinalScore()));
            
            return results;

        } catch (Exception e) {
            log.error("Failed to execute query_graph", e);
            throw new RuntimeException("Query Graph execution failed", e);
        }
    }
}
