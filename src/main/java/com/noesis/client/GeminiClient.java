package com.noesis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.service.PredicateService;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
public class GeminiClient extends AbstractBaseLlmClient {

    private final String apiKey;

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";

    public GeminiClient(ObjectMapper objectMapper, PredicateService predicateService,
                         LlmRateLimiter rateLimiter, String apiKey, String model, long timeoutSeconds) {
        super(objectMapper, predicateService, rateLimiter, timeoutSeconds, model);
        this.apiKey = apiKey;
    }

    @Override
    protected boolean isApiKeyValid() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    protected String getApiKeyErrorMessage() {
        return "Gemini API key is not configured. Cannot extract assertions.";
    }

    @Override
    protected HttpRequest buildRequest(String prompt) {
        Map<String, Object> payload = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json")
        );
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Gemini request payload", e);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    @Override
    protected String unwrapResponse(String responseBody) {
        try {
            var root = objectMapper.readTree(responseBody);
            var candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                var parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText();
                }
            }
            log.warn("Unexpected Gemini API response structure: {}", responseBody);
            throw new RuntimeException("Unexpected Gemini API response structure: " + responseBody);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini response", e);
        }
    }
}
