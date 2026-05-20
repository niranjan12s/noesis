package com.noesis.sgms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathExplanationResponse {
    private Map<String, Object> seedAssertion;
    private List<String> seedNodes;
    private List<String> traversedEdges;
    private List<String> neighborNodes;
    private List<Map<String, Object>> relatedAssertions;
}
