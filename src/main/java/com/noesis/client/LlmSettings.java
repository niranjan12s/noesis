package com.noesis.client;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmSettings {
    private String provider = "ollama";
    private String model = "llama-3.1-8b-instant";
    private String baseUrl = "http://localhost:11434";
    private String apiKey = "";
    private String openaiBaseUrl = "https://api.openai.com/v1";
    private long timeoutSeconds = 120;
    private String customRequestTemplate = "";
    private String customResponsePath = "";
    private RateLimiterConfig rateLimiter = new RateLimiterConfig();
    private java.util.List<String> watchDirectories = new java.util.ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimiterConfig {
        private boolean enabled = false;
        private int maxCallsPerMinute = 5;
    }
}
