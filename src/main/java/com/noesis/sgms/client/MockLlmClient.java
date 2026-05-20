package com.noesis.sgms.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.sgms.dto.AssertionExtractionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "sgms.llm.provider", havingValue = "mock")
@RequiredArgsConstructor
public class MockLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;

    @Override
    public List<AssertionExtractionResponse> extractAssertions(String sectionPath, String chunkContent) {
        log.info("MockLlmClient: Simulating successful LLM response...");
        
        // Construct a valid, successful JSON response string returned by an LLM
        String mockLlmResponseText = """
                [
                  {
                    "subject_text": "Mail Delivery Server",
                    "predicate": "CALLS",
                    "object_text": "HSM Security Module",
                    "raw_text": "2. The Mail Delivery Server CALLS the HSM Security Module.",
                    "normalized_text": "Mail Delivery Server CALLS HSM Security Module",
                    "attributes": {"source": "mock-llm-provider"}
                  },
                  {
                    "subject_text": "Mail Delivery Server",
                    "predicate": "DEPENDS_ON",
                    "object_text": "HSM Security Module",
                    "raw_text": "3. The Mail Delivery Server DEPENDS_ON the HSM Security Module.",
                    "normalized_text": "Mail Delivery Server DEPENDS_ON HSM Security Module",
                    "attributes": {"source": "mock-llm-provider"}
                  }
                ]
                """;
        
        try {
            // This tests the exact same JSON parsing/deserialization logic that runs in OllamaClient and GeminiClient
            return objectMapper.readValue(mockLlmResponseText, new TypeReference<List<AssertionExtractionResponse>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize mock LLM JSON response", e);
            return Collections.emptyList();
        }
    }
}
