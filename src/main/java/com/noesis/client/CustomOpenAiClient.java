package com.noesis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.service.PredicateService;
import com.noesis.util.JsonPathResolver;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
public class CustomOpenAiClient extends AbstractBaseLlmClient {

    private final String apiKey;
    private final String baseUrl;
    private final String customRequestTemplate;
    private final String customResponsePath;

    public CustomOpenAiClient(ObjectMapper objectMapper, PredicateService predicateService,
                               LlmRateLimiter rateLimiter, String apiKey, String baseUrl,
                               String model, long timeoutSeconds,
                               String customRequestTemplate, String customResponsePath) {
        super(objectMapper, predicateService, rateLimiter, timeoutSeconds, model);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.customRequestTemplate = customRequestTemplate;
        this.customResponsePath = customResponsePath;
    }

    @Override
    protected boolean isApiKeyValid() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    protected String getApiKeyErrorMessage() {
        return "Custom API key is not configured. Cannot extract assertions.";
    }

    @Override
    protected HttpRequest buildRequest(String prompt) {
        String completionsUrl = baseUrl.trim();
        if (!completionsUrl.endsWith("/")) completionsUrl += "/";
        completionsUrl = completionsUrl.replaceAll("/+$", "") + "/chat/completions";

        // If a custom request template is provided, use it
        String requestBody;
        if (customRequestTemplate != null && !customRequestTemplate.isBlank()) {
            requestBody = JsonPathResolver.renderTemplate(customRequestTemplate, model, prompt, objectMapper);
            if (requestBody == null) {
                log.warn("Custom request template failed, falling back to OpenAI format");
                requestBody = buildOpenAiRequestBody(prompt);
            }
        } else {
            requestBody = buildOpenAiRequestBody(prompt);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(completionsUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private String buildOpenAiRequestBody(String prompt) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "model", model,
                    "temperature", 0.0,
                    "response_format", Map.of("type", "json_object")
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OpenAI request payload", e);
        }
    }

    @Override
    protected String unwrapResponse(String responseBody) {
        // If a custom response path is provided, use it
        if (customResponsePath != null && !customResponsePath.isBlank()) {
            try {
                var root = objectMapper.readTree(responseBody);
                String result = JsonPathResolver.resolve(root, customResponsePath);
                if (result != null) return result;
                log.warn("Custom response path '{}' returned null, falling back to OpenAI format", customResponsePath);
            } catch (Exception e) {
                log.warn("Custom response path '{}' failed, falling back to OpenAI format", customResponsePath);
            }
        }

        // Fall back to OpenAI-compatible response unwrapping
        try {
            var root = objectMapper.readTree(responseBody);
            var choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText();
            }
            log.warn("Unexpected custom API response structure: {}", responseBody);
            throw new RuntimeException("Unexpected response structure: " + responseBody);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse custom API response", e);
        }
    }
}
