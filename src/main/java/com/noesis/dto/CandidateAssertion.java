package com.noesis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateAssertion {
    private String assertionId;
    private String rawText;
    private int depth;
    private double bm25Score;
    private double graphScore;
    private double finalScore;
}
