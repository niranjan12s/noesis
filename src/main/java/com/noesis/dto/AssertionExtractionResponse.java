package com.noesis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssertionExtractionResponse {

    @NotBlank
    @JsonProperty("subject_text")
    private String subjectText;

    @NotBlank
    @JsonProperty("predicate")
    private String predicate;

    @NotBlank
    @JsonProperty("object_text")
    private String objectText;

    @NotBlank
    @JsonProperty("raw_text")
    private String rawText;

    @NotBlank
    @JsonProperty("normalized_text")
    private String normalizedText;

    @JsonProperty("attributes")
    private Map<String, Object> attributes;
}
