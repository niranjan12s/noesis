package com.noesis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noesis.dto.CandidateAssertion;
import com.noesis.dto.PathExplanationResponse;
import com.noesis.dto.QueryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphToolService {

    private final QueryService queryService;
    private final OpenSearchClient openSearchClient;

    public List<CandidateAssertion> queryGraph(String text, int depth) {
        QueryRequest request = QueryRequest.builder()
                .text(text)
                .depth(depth)
                .build();
        return queryService.queryGraph(request);
    }

    public PathExplanationResponse explainPathByAssertionId(String assertionId) {
        try {
            // 1. Get seedAssertion
            Map<String, Object> seedAssertion = getAssertionById(assertionId);
            if (seedAssertion == null) {
                return null; // Not found
            }

            // 2. Extract seedNodes
            String subjectNodeId = (String) seedAssertion.get("subjectNodeId");
            String objectNodeId = (String) seedAssertion.get("objectNodeId");
            List<String> seedNodes = Arrays.asList(subjectNodeId, objectNodeId);

            List<FieldValue> seedValues = seedNodes.stream().map(FieldValue::of).collect(Collectors.toList());

            // 3. Find 1-hop traversed edges
            Query edgeQuery = Query.of(q -> q.bool(b -> b.should(List.of(
                    Query.of(s1 -> s1.terms(t -> t.field("fromNodeId").terms(ts -> ts.value(seedValues)))),
                    Query.of(s2 -> s2.terms(t -> t.field("toNodeId").terms(ts -> ts.value(seedValues))))
            ))));

            SearchRequest edgeSearch = SearchRequest.of(s -> s
                    .index("edge-index")
                    .query(edgeQuery)
                    .size(100)
            );

            SearchResponse<ObjectNode> edgeResponse = openSearchClient.search(edgeSearch, ObjectNode.class);

            List<String> traversedEdges = new ArrayList<>();
            Set<String> neighborNodes = new HashSet<>();

            for (Hit<ObjectNode> hit : edgeResponse.hits().hits()) {
                if (hit.source() != null) {
                    traversedEdges.add(hit.source().get("edgeId").asText());
                    String from = hit.source().get("fromNodeId").asText();
                    String to = hit.source().get("toNodeId").asText();
                    
                    if (!seedNodes.contains(from)) neighborNodes.add(from);
                    if (!seedNodes.contains(to)) neighborNodes.add(to);
                }
            }

            // 4. Fetch related assertions
            List<Map<String, Object>> relatedAssertions = new ArrayList<>();
            if (!neighborNodes.isEmpty()) {
                List<FieldValue> neighborValues = neighborNodes.stream().map(FieldValue::of).collect(Collectors.toList());
                Query neighborAssertionQuery = Query.of(q -> q.bool(b -> b.should(List.of(
                        Query.of(s1 -> s1.terms(t -> t.field("subjectNodeId").terms(ts -> ts.value(neighborValues)))),
                        Query.of(s2 -> s2.terms(t -> t.field("objectNodeId").terms(ts -> ts.value(neighborValues))))
                ))));

                SearchRequest neighborSearch = SearchRequest.of(s -> s
                        .index("assertion-index")
                        .query(neighborAssertionQuery)
                        .size(100)
                );

                SearchResponse<ObjectNode> neighborAssertionResponse = openSearchClient.search(neighborSearch, ObjectNode.class);
                for (Hit<ObjectNode> hit : neighborAssertionResponse.hits().hits()) {
                    if (hit.source() != null) {
                        Map<String, Object> ast = new HashMap<>();
                        ast.put("assertionId", hit.source().get("assertionId").asText());
                        ast.put("rawText", hit.source().has("rawText") ? hit.source().get("rawText").asText() : "");
                        relatedAssertions.add(ast);
                    }
                }
            }

            return PathExplanationResponse.builder()
                    .seedAssertion(seedAssertion)
                    .seedNodes(seedNodes)
                    .traversedEdges(traversedEdges)
                    .neighborNodes(new ArrayList<>(neighborNodes))
                    .relatedAssertions(relatedAssertions)
                    .build();

        } catch (Exception e) {
            log.error("Failed explain_path_by_assertion_id", e);
            throw new RuntimeException("Explain path failed", e);
        }
    }

    public Map<String, Object> getNodeNeighbour(String nodeId, int depth) {
        // Simplified BFS to gather neighboring nodes and edges up to depth
        try {
            Set<String> visitedNodes = new HashSet<>(Collections.singletonList(nodeId));
            Set<String> currentLayer = new HashSet<>(Collections.singletonList(nodeId));
            List<Map<String, Object>> edgesFound = new ArrayList<>();
            List<String> nodesFound = new ArrayList<>();

            for (int d = 1; d <= depth; d++) {
                if (currentLayer.isEmpty()) break;

                List<FieldValue> layerValues = currentLayer.stream().map(FieldValue::of).collect(Collectors.toList());
                Query edgeQuery = Query.of(q -> q.bool(b -> b.should(List.of(
                        Query.of(s1 -> s1.terms(t -> t.field("fromNodeId").terms(ts -> ts.value(layerValues)))),
                        Query.of(s2 -> s2.terms(t -> t.field("toNodeId").terms(ts -> ts.value(layerValues))))
                ))));

                SearchRequest edgeSearch = SearchRequest.of(s -> s
                        .index("edge-index")
                        .query(edgeQuery)
                        .size(1000)
                );

                SearchResponse<ObjectNode> edgeResponse = openSearchClient.search(edgeSearch, ObjectNode.class);
                
                Set<String> nextLayer = new HashSet<>();
                for (Hit<ObjectNode> hit : edgeResponse.hits().hits()) {
                    if (hit.source() != null) {
                        Map<String, Object> edgeMap = new HashMap<>();
                        edgeMap.put("edgeId", hit.source().get("edgeId").asText());
                        edgeMap.put("fromNodeId", hit.source().get("fromNodeId").asText());
                        edgeMap.put("toNodeId", hit.source().get("toNodeId").asText());
                        edgesFound.add(edgeMap);

                        String from = hit.source().get("fromNodeId").asText();
                        String to = hit.source().get("toNodeId").asText();
                        
                        if (!visitedNodes.contains(from)) {
                            nextLayer.add(from);
                            nodesFound.add(from);
                        }
                        if (!visitedNodes.contains(to)) {
                            nextLayer.add(to);
                            nodesFound.add(to);
                        }
                    }
                }
                visitedNodes.addAll(nextLayer);
                currentLayer = nextLayer;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("nodes", nodesFound);
            result.put("edges", edgesFound);
            return result;

        } catch (Exception e) {
            log.error("Failed get_node_neighbour", e);
            throw new RuntimeException("Get Node Neighbour failed", e);
        }
    }

    public Map<String, Object> getAssertionById(String assertionId) {
        try {
            GetRequest getRequest = GetRequest.of(g -> g
                    .index("assertion-index")
                    .id(assertionId)
            );

            GetResponse<ObjectNode> response = openSearchClient.get(getRequest, ObjectNode.class);
            if (response.found() && response.source() != null) {
                Map<String, Object> map = new HashMap<>();
                map.put("assertionId", response.source().get("assertionId").asText());
                map.put("documentId", response.source().get("documentId").asText());
                map.put("subjectText", response.source().get("subjectText").asText());
                map.put("predicate", response.source().get("predicate").asText());
                map.put("objectText", response.source().get("objectText").asText());
                map.put("normalizedText", response.source().get("normalizedText").asText());
                map.put("rawText", response.source().has("rawText") ? response.source().get("rawText").asText() : "");
                map.put("subjectNodeId", response.source().get("subjectNodeId").asText());
                map.put("objectNodeId", response.source().get("objectNodeId").asText());
                return map;
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get assertion by id", e);
            throw new RuntimeException("Get assertion by id failed", e);
        }
    }
}
