package com.noesis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.service.PredicateService;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

@Slf4j
public class OllamaClient extends AbstractBaseLlmClient {

    private final String baseUrl;

    public OllamaClient(ObjectMapper objectMapper, PredicateService predicateService,
                         LlmRateLimiter rateLimiter, String baseUrl, String model, long timeoutSeconds) {
        super(objectMapper, predicateService, rateLimiter, timeoutSeconds, model);
        this.baseUrl = baseUrl;
    }

    @Override
    protected boolean isApiKeyValid() {
        return true;
    }

    @Override
    protected String getApiKeyErrorMessage() {
        return "";
    }

    @Override
    protected HttpRequest buildRequest(String prompt) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Ollama request payload", e);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    @Override
    protected String unwrapResponse(String responseBody) {
        try {
            var root = objectMapper.readTree(responseBody);
            return root.path("response").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ollama response", e);
        }
    }
}
