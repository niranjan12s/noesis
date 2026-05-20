package com.noesis.sgms.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.sgms.dto.AssertionExtractionResponse;
import com.noesis.sgms.service.PredicateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "sgms.llm.provider", havingValue = "groq")
@RequiredArgsConstructor
public class GroqClient implements LlmClient {

    @Value("${GROQ_API_KEY:${GROK_API_KEY:}}")
    private String apiKey;

    @Value("${sgms.llm.timeout-seconds:120}")
    private long timeoutSeconds;

    private final ObjectMapper objectMapper;
    private final PredicateService predicateService;
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String PROMPT_TEMPLATE = """
            You are an assertion extraction engine.
            
            Your responsibility is to extract operational semantic assertions from a bounded documentation chunk.
            
            You MUST obey all extraction rules strictly.
            
            # OBJECTIVE
            
            Extract atomic operational propositions from the provided chunk.
            
            Each assertion must represent exactly ONE operational fact.
            
            # EXTRACTION RULES
            
            1. Assertions MUST be grounded ONLY in the provided chunk text.
            
            2. Do NOT infer information not explicitly present.
            
            3. Do NOT summarize.
            
            4. Do NOT combine multiple operations into one assertion.
            
            5. Each assertion MUST contain:
               - subject_text
               - predicate
               - object_text
               - raw_text
               - normalized_text
            
            6. Predicate selection (CRITICAL — read carefully):
               - You MUST pick the predicate EXACTLY as written in the allowed list below.
               - Do NOT change the tense, number, or spelling of the predicate.
               - Do NOT add or remove suffixes (e.g., "CREATES" is allowed, "CREATED" or "CREATING" or "CREATE" are NOT).
               - If the chunk describes a past or ongoing action, still use the present-tense form from the list.
               - If none of the predicates accurately captures the relationship, return an empty array.
               - Never invent a predicate that is not in the list.
            
            7. If no valid assertion exists, return an empty array.
            
            8. Preserve technical entity names exactly when possible.
            
            9. normalized_text MUST:
               - be concise
               - use canonical operational phrasing
               - remove filler language
               - preserve semantic meaning
            
            10. raw_text MUST contain the original supporting sentence fragment from the chunk.
            
            11. attributes are optional and should only include explicitly stated operational metadata.
            
            # ALLOWED PREDICATES (pick from these exactly)
            
            %s
            
            # OUTPUT FORMAT
            
            Return STRICT JSON ONLY.
            
            Return a JSON array of objects, each representing an assertion.
            
            Do NOT include markdown.
            Do NOT include explanations.
            Do NOT include prose.
            
            # CHUNK
            
            Section Path:
            %s
            
            Chunk Content:
            %s
            """;

    private final LlmRateLimiter rateLimiter;

    @Override
    public List<AssertionExtractionResponse> extractAssertions(String sectionPath, String chunkContent) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your_groq_api_key_here")) {
            log.error("GROQ_API_KEY is not configured. Cannot extract assertions.");
            throw new RuntimeException("GROQ_API_KEY is not configured. Cannot extract assertions.");
        }

        String predicateList = "[\n" + predicateService.getActivePredicates().stream()
                .map(name -> "  \"" + name + "\"")
                .collect(Collectors.joining(",\n")) + "\n]";
        String prompt = String.format(PROMPT_TEMPLATE, predicateList, sectionPath, chunkContent);

        int maxRetries = 5;
        int retryDelayMs = 2000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            rateLimiter.acquire();
            try {
                Map<String, Object> payload = Map.of(
                        "messages", List.of(
                                Map.of("role", "user", "content", prompt)
                        ),
                        "model", "llama-3.1-8b-instant",
                        "temperature", 0.0,
                        "response_format", Map.of("type", "json_object")
                );
                String requestBody = objectMapper.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GROQ_URL))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && !choices.isEmpty()) {
                        JsonNode message = choices.get(0).path("message");
                        String llmResponseText = message.path("content").asText();
                        return parseJsonResponse(llmResponseText);
                    }
                    log.warn("Unexpected Groq API response structure: {}", response.body());
                    throw new RuntimeException("Unexpected Groq API response structure: " + response.body());
                } else if (response.statusCode() == 429 && attempt < maxRetries) {
                    int waitMs = parseRetryAfter(response.body(), retryDelayMs);
                    log.warn("Groq API 429 rate limited (attempt {}/{}). Retrying in {}ms.", attempt, maxRetries, waitMs);
                    Thread.sleep(waitMs);
                    retryDelayMs = Math.min(retryDelayMs * 2, 30000);
                } else {
                    log.error("Groq API failed with status {}: {}.", response.statusCode(), response.body());
                    throw new RuntimeException("Groq API failed with status " + response.statusCode() + ": " + response.body());
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Groq assertion extraction interrupted", ie);
            } catch (Exception e) {
                log.error("Error during assertion extraction from Groq API (attempt {}/{}).", attempt, maxRetries, e);
                if (attempt == maxRetries) {
                    throw new RuntimeException("Error during assertion extraction from Groq API after all retries", e);
                }
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                retryDelayMs = Math.min(retryDelayMs * 2, 30000);
            }
        }
        throw new RuntimeException("Groq API failed to respond successfully after " + maxRetries + " attempts");
    }

    private int parseRetryAfter(String responseBody, int defaultMs) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "Please try again in ([\\d.]+)(m?s)"
            ).matcher(responseBody);
            if (m.find()) {
                double val = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                if ("ms".equals(unit)) {
                    return (int) val + 500;
                } else {
                    return (int) (val * 1000) + 500;
                }
            }
        } catch (Exception ignored) {}
        return defaultMs;
    }

    private List<AssertionExtractionResponse> parseJsonResponse(String jsonString) {
        try {
            // Find JSON array bounds in case LLM added extra text around it
            int startIdx = jsonString.indexOf('[');
            int endIdx = jsonString.lastIndexOf(']');
            if (startIdx >= 0 && endIdx >= startIdx) {
                String pureJson = jsonString.substring(startIdx, endIdx + 1);
                return objectMapper.readValue(pureJson, new TypeReference<List<AssertionExtractionResponse>>() {});
            }
            
            // Check if it's a JSON object wrapping the array, e.g. {"assertions": [...]}
            JsonNode root = objectMapper.readTree(jsonString);
            for (JsonNode child : root) {
                if (child.isArray()) {
                    return objectMapper.convertValue(child, new TypeReference<List<AssertionExtractionResponse>>() {});
                }
            }
            
            log.warn("Could not find JSON array in Groq response: {}", jsonString);
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Groq JSON response: {}", jsonString, e);
            throw new RuntimeException("Failed to parse Groq JSON response", e);
        }
    }

}
