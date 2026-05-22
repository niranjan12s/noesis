package com.noesis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.dto.AssertionExtractionResponse;
import com.noesis.service.NoesisConfigService;
import com.noesis.service.PredicateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicLlmClient implements LlmClient {

    private final NoesisConfigService configService;
    private final ObjectMapper objectMapper;
    private final PredicateService predicateService;
    private final LlmRateLimiter rateLimiter;

    private volatile LlmClient delegate;
    private volatile String lastConfigHash;

    @Override
    public List<AssertionExtractionResponse> extractAssertions(String sectionPath, String chunkContent) {
        return getDelegate().extractAssertions(sectionPath, chunkContent);
    }

    private LlmClient getDelegate() {
        LlmSettings current = configService.getLlmSettings();
        String hash = configHash(current);
        if (delegate == null || !hash.equals(lastConfigHash)) {
            synchronized (this) {
                if (delegate == null || !hash.equals(lastConfigHash)) {
                    delegate = createDelegate(current);
                    lastConfigHash = hash;
                    log.info("Switched LLM provider to: {} (model: {})", current.getProvider(), current.getModel());
                }
            }
        }
        return delegate;
    }

    private LlmClient createDelegate(LlmSettings cfg) {
        return switch (cfg.getProvider()) {
            case "groq" -> new GroqClient(objectMapper, predicateService, rateLimiter,
                    cfg.getApiKey(), cfg.getModel(), cfg.getTimeoutSeconds());
            case "custom" -> new CustomOpenAiClient(objectMapper, predicateService, rateLimiter,
                    cfg.getApiKey(), cfg.getOpenaiBaseUrl(), cfg.getModel(), cfg.getTimeoutSeconds(),
                    cfg.getCustomRequestTemplate(), cfg.getCustomResponsePath());
            case "gemini" -> new GeminiClient(objectMapper, predicateService, rateLimiter,
                    cfg.getApiKey(), cfg.getModel(), cfg.getTimeoutSeconds());
            case "ollama" -> new OllamaClient(objectMapper, predicateService, rateLimiter,
                    cfg.getBaseUrl(), cfg.getModel(), cfg.getTimeoutSeconds());
            default -> {
                log.warn("Unknown provider '{}', falling back to Ollama", cfg.getProvider());
                yield new OllamaClient(objectMapper, predicateService, rateLimiter,
                        cfg.getBaseUrl(), cfg.getModel(), cfg.getTimeoutSeconds());
            }
        };
    }

    private static String configHash(LlmSettings cfg) {
        return String.join("|",
                cfg.getProvider(),
                cfg.getModel(),
                cfg.getBaseUrl(),
                cfg.getOpenaiBaseUrl(),
                cfg.getApiKey(),
                String.valueOf(cfg.getTimeoutSeconds()),
                cfg.getCustomRequestTemplate(),
                cfg.getCustomResponsePath(),
                String.valueOf(cfg.getRateLimiter().isEnabled()),
                String.valueOf(cfg.getRateLimiter().getMaxCallsPerMinute())
        );
    }
}
