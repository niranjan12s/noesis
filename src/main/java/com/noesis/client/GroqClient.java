package com.noesis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.service.PredicateService;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GroqClient extends AbstractBaseLlmClient {

    private final String apiKey;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    public GroqClient(ObjectMapper objectMapper, PredicateService predicateService,
                       LlmRateLimiter rateLimiter, String apiKey, String model, long timeoutSeconds) {
        super(objectMapper, predicateService, rateLimiter, timeoutSeconds, model);
        this.apiKey = apiKey;
    }

    @Override
    protected boolean isApiKeyValid() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("your_groq_api_key_here");
    }

    @Override
    protected String getApiKeyErrorMessage() {
        return "GROQ_API_KEY is not configured. Cannot extract assertions.";
    }

    @Override
    protected HttpRequest buildRequest(String prompt) {
        Map<String, Object> payload = Map.of(
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "model", model,
                "temperature", 0.0,
                "response_format", Map.of("type", "json_object")
        );
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Groq request payload", e);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    @Override
    protected String unwrapResponse(String responseBody) {
        try {
            var root = objectMapper.readTree(responseBody);
            var choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText();
            }
            log.warn("Unexpected Groq API response structure: {}", responseBody);
            throw new RuntimeException("Unexpected Groq API response structure: " + responseBody);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Groq response", e);
        }
    }

    @Override
    protected int handleRateLimit(String responseBody, int currentRetryDelayMs) {
        try {
            Matcher m = Pattern.compile("Please try again in ([\\d.]+)(m?s)").matcher(responseBody);
            if (m.find()) {
                double val = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                return "ms".equals(unit) ? (int) val + 500 : (int) (val * 1000) + 500;
            }
        } catch (Exception ignored) {}
        return currentRetryDelayMs;
    }
}
