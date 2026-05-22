package com.noesis.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.dto.AssertionExtractionResponse;
import com.noesis.service.PredicateService;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractBaseLlmClient implements LlmClient {

    protected final ObjectMapper objectMapper;
    protected final PredicateService predicateService;
    protected final LlmRateLimiter rateLimiter;
    protected final HttpClient httpClient = HttpClient.newBuilder().build();
    protected final long timeoutSeconds;
    protected final String model;

    protected static final String PROMPT_TEMPLATE = """
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

    protected AbstractBaseLlmClient(ObjectMapper objectMapper, PredicateService predicateService,
                                     LlmRateLimiter rateLimiter, long timeoutSeconds, String model) {
        this.objectMapper = objectMapper;
        this.predicateService = predicateService;
        this.rateLimiter = rateLimiter;
        this.timeoutSeconds = timeoutSeconds;
        this.model = model;
    }

    protected abstract boolean isApiKeyValid();

    protected abstract String getApiKeyErrorMessage();

    protected abstract HttpRequest buildRequest(String prompt);

    protected abstract String unwrapResponse(String responseBody);

    /**
     * Override to provide custom 429 rate-limit delay parsing (e.g. Groq's Retry-After).
     */
    protected int handleRateLimit(String responseBody, int currentRetryDelayMs) {
        return currentRetryDelayMs;
    }

    @Override
    public List<AssertionExtractionResponse> extractAssertions(String sectionPath, String chunkContent) {
        if (!isApiKeyValid()) {
            log.error(getApiKeyErrorMessage());
            throw new RuntimeException(getApiKeyErrorMessage());
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
                HttpRequest request = buildRequest(prompt);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String llmResponseText = unwrapResponse(response.body());
                    return parseJsonResponse(llmResponseText);
                } else if (response.statusCode() == 429 && attempt < maxRetries) {
                    int waitMs = handleRateLimit(response.body(), retryDelayMs);
                    log.warn("Rate limited (attempt {}/{}). Retrying in {}ms.", attempt, maxRetries, waitMs);
                    Thread.sleep(waitMs);
                    retryDelayMs = Math.min(retryDelayMs * 2, 30000);
                } else {
                    log.error("API failed with status {}: {}.", response.statusCode(), response.body());
                    throw new RuntimeException("API failed with status " + response.statusCode() + ": " + response.body());
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Assertion extraction interrupted", ie);
            } catch (Exception e) {
                log.error("Error during assertion extraction (attempt {}/{}).", attempt, maxRetries, e);
                if (attempt == maxRetries) {
                    throw new RuntimeException("Error during assertion extraction after all retries", e);
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
        throw new RuntimeException("API failed to respond successfully after " + maxRetries + " attempts");
    }

    protected List<AssertionExtractionResponse> parseJsonResponse(String jsonString) {
        try {
            int startIdx = jsonString.indexOf('[');
            int endIdx = jsonString.lastIndexOf(']');
            if (startIdx >= 0 && endIdx >= startIdx) {
                String pureJson = jsonString.substring(startIdx, endIdx + 1);
                return objectMapper.readValue(pureJson, new TypeReference<List<AssertionExtractionResponse>>() {});
            }

            JsonNode root = objectMapper.readTree(jsonString);
            for (JsonNode child : root) {
                if (child.isArray()) {
                    return objectMapper.convertValue(child, new TypeReference<List<AssertionExtractionResponse>>() {});
                }
            }

            log.warn("Could not find JSON array in response: {}", jsonString);
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON response: {}", jsonString, e);
            throw new RuntimeException("Failed to parse JSON response", e);
        }
    }
}
