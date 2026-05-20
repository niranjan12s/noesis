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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "sgms.llm.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiClient implements LlmClient {

    @Value("${sgms.llm.gemini-api-key:}")
    private String apiKey;

    @Value("${sgms.llm.timeout-seconds:120}")
    private long timeoutSeconds;

    private final ObjectMapper objectMapper;
    private final PredicateService predicateService;
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";

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
            
            Return a JSON array.
            
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
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Gemini API key is not configured. Cannot extract assertions.");
            throw new RuntimeException("Gemini API key is not configured. Cannot extract assertions.");
        }

        rateLimiter.acquire();
        String predicateList = "[\n" + predicateService.getActivePredicates().stream()
                .map(name -> "  \"" + name + "\"")
                .collect(Collectors.joining(",\n")) + "\n]";
        String prompt = String.format(PROMPT_TEMPLATE, predicateList, sectionPath, chunkContent);

        try {
            // Build the Gemini API payload
            Map<String, Object> payload = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", prompt)
                            ))
                    ),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json"
                    )
            );
            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                // Extract the text from candidates[0].content.parts[0].text
                JsonNode candidates = root.path("candidates");
                if (candidates.isArray() && !candidates.isEmpty()) {
                    JsonNode parts = candidates.get(0).path("content").path("parts");
                    if (parts.isArray() && !parts.isEmpty()) {
                        String llmResponseText = parts.get(0).path("text").asText();
                        return parseJsonResponse(llmResponseText);
                    }
                }
                log.warn("Unexpected Gemini API response structure: {}", response.body());
                throw new RuntimeException("Unexpected Gemini API response structure: " + response.body());
            } else {
                log.error("Gemini API failed with status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Gemini API failed with status " + response.statusCode() + ": " + response.body());
            }

        } catch (Exception e) {
            log.error("Error during assertion extraction from Gemini.", e);
            throw new RuntimeException("Error during assertion extraction from Gemini", e);
        }
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
            log.warn("Could not find JSON array in Gemini response: {}", jsonString);
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Gemini JSON response: {}", jsonString, e);
            throw new RuntimeException("Failed to parse Gemini JSON response", e);
        }
    }

}
