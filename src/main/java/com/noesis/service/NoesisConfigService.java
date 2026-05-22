package com.noesis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noesis.client.LlmSettings;
import com.noesis.util.ConfigEncryptionUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoesisConfigService {

    private final ObjectMapper objectMapper;
    private final List<PathMatcher> includeMatchers = new ArrayList<>();
    private final List<PathMatcher> excludeMatchers = new ArrayList<>();

    @Value("${noesis.llm.base-url:http://localhost:11434}")
    private String defaultBaseUrl;

    @Value("${noesis.llm.model:llama-3.1-8b-instant}")
    private String defaultModel;

    @Value("${noesis.llm.openai-base-url:https://api.openai.com/v1}")
    private String defaultOpenaiBaseUrl;

    @Value("${noesis.llm.timeout-seconds:120}")
    private long defaultTimeoutSeconds;

    @Value("${noesis.llm.rate-limiter.enabled:false}")
    private boolean defaultRateLimiterEnabled;

    @Value("${noesis.llm.rate-limiter.max-calls-per-minute:5}")
    private int defaultRateLimiterMaxCalls;

    private volatile LlmSettings llmSettings;

    private static final String CONFIG_FILE_PATH = ".noesis/config.json";

    @PostConstruct
    public void init() {
        loadConfig();
    }

    public synchronized void loadConfig() {
        includeMatchers.clear();
        excludeMatchers.clear();

        Path configPath = Paths.get(CONFIG_FILE_PATH).toAbsolutePath();
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        LlmSettings loaded = new LlmSettings();

        if (Files.exists(configPath)) {
            try {
                log.info("Loading Noesis configuration from: {}", configPath);
                JsonNode rootNode = objectMapper.readTree(configPath.toFile());

                JsonNode includeNode = rootNode.path("include");
                if (includeNode.isArray()) {
                    for (JsonNode node : includeNode) {
                        includes.add(node.asText());
                    }
                }

                JsonNode excludeNode = rootNode.path("exclude");
                if (excludeNode.isArray()) {
                    for (JsonNode node : excludeNode) {
                        excludes.add(node.asText());
                    }
                }

                JsonNode llmNode = rootNode.path("llm");
                if (llmNode.isObject()) {
                    loaded = parseLlmNode(llmNode);
                }
            } catch (IOException e) {
                log.error("Failed to parse .noesis/config.json, falling back to default rules", e);
            }
        } else {
            log.info(".noesis/config.json not found. Using default rules.");
        }

        // Fill gaps from environment / application defaults
        mergeLlmDefaults(loaded);

        this.llmSettings = loaded;

        // Fallbacks if lists are empty
        if (includes.isEmpty()) {
            includes.addAll(List.of("**/*.md", "docs/**/*.md", "README.md"));
        }
        if (excludes.isEmpty()) {
            excludes.addAll(List.of("node_modules/**", ".git/**", "dist/**", "build/**", ".noesis/**", "**/.*/**"));
        }

        FileSystem fs = FileSystems.getDefault();
        for (String pattern : includes) {
            includeMatchers.add(fs.getPathMatcher("glob:" + pattern));
        }
        for (String pattern : excludes) {
            excludeMatchers.add(fs.getPathMatcher("glob:" + pattern));
        }

        log.info("Configured {} include and {} exclude matchers", includeMatchers.size(), excludeMatchers.size());
        log.info("LLM settings: provider={}, model={}", loaded.getProvider(), loaded.getModel());
    }

    private LlmSettings parseLlmNode(JsonNode llmNode) {
        LlmSettings s = new LlmSettings();
        boolean hasRateLimiter = false;
        if (llmNode.has("provider")) s.setProvider(llmNode.get("provider").asText());
        if (llmNode.has("model")) s.setModel(llmNode.get("model").asText());
        if (llmNode.has("base_url")) s.setBaseUrl(llmNode.get("base_url").asText());
        if (llmNode.has("openai_base_url")) s.setOpenaiBaseUrl(llmNode.get("openai_base_url").asText());
        if (llmNode.has("timeout_seconds")) s.setTimeoutSeconds(llmNode.get("timeout_seconds").asLong());
        if (llmNode.has("custom_request_template")) s.setCustomRequestTemplate(llmNode.get("custom_request_template").asText());
        if (llmNode.has("custom_response_path")) s.setCustomResponsePath(llmNode.get("custom_response_path").asText());

        // API key: try encrypted (api_key, from Python setup) first, then plain text (apiKey, from UI)
        String apiKey = "";
        if (llmNode.has("api_key")) {
            apiKey = ConfigEncryptionUtil.decryptApiKey(llmNode.get("api_key").asText());
        }
        if (llmNode.has("apiKey")) {
            apiKey = llmNode.get("apiKey").asText();
        }
        s.setApiKey(apiKey != null ? apiKey : "");

        // Rate limiter sub-node
        JsonNode rl = llmNode.path("rate_limiter");
        if (rl.isObject()) {
            LlmSettings.RateLimiterConfig rc = new LlmSettings.RateLimiterConfig();
            if (rl.has("enabled")) rc.setEnabled(rl.get("enabled").asBoolean());
            if (rl.has("max_calls_per_minute")) rc.setMaxCallsPerMinute(rl.get("max_calls_per_minute").asInt());
            s.setRateLimiter(rc);
        } else {
            s.setRateLimiter(null); // signal that no file-based config exists
        }
        return s;
    }

    private void mergeLlmDefaults(LlmSettings s) {
        if (s.getProvider() == null || s.getProvider().isBlank()) s.setProvider("ollama");
        if (s.getModel() == null || s.getModel().isBlank()) s.setModel(defaultModel);
        if (s.getBaseUrl() == null || s.getBaseUrl().isBlank()) s.setBaseUrl(defaultBaseUrl);
        if (s.getOpenaiBaseUrl() == null || s.getOpenaiBaseUrl().isBlank()) s.setOpenaiBaseUrl(defaultOpenaiBaseUrl);
        if (s.getTimeoutSeconds() <= 0) s.setTimeoutSeconds(defaultTimeoutSeconds);
        if (s.getRateLimiter() == null) {
            s.setRateLimiter(new LlmSettings.RateLimiterConfig(
                    defaultRateLimiterEnabled, defaultRateLimiterMaxCalls));
        }
    }

    public LlmSettings getLlmSettings() {
        return llmSettings;
    }

    public synchronized void updateLlmSettings(LlmSettings settings) {
        this.llmSettings = settings;
        persistConfig();
    }

    private void persistConfig() {
        try {
            Path configPath = Paths.get(CONFIG_FILE_PATH).toAbsolutePath();
            ObjectNode root;
            if (Files.exists(configPath)) {
                root = (ObjectNode) objectMapper.readTree(configPath.toFile());
            } else {
                root = objectMapper.createObjectNode();
                var includes = root.putArray("include");
                for (var s : List.of("**/*.md", "docs/**/*.md", "README.md")) {
                    includes.add(s);
                }
                var excludes = root.putArray("exclude");
                for (var s : List.of("node_modules/**", ".git/**", "dist/**", "build/**", ".noesis/**", "**/.*/**")) {
                    excludes.add(s);
                }
            }

            ObjectNode llmNode = objectMapper.createObjectNode();
            llmNode.put("provider", llmSettings.getProvider());
            llmNode.put("model", llmSettings.getModel());
            llmNode.put("apiKey", llmSettings.getApiKey());
            llmNode.put("base_url", llmSettings.getBaseUrl());
            llmNode.put("openai_base_url", llmSettings.getOpenaiBaseUrl());
            llmNode.put("timeout_seconds", llmSettings.getTimeoutSeconds());
            llmNode.put("custom_request_template", llmSettings.getCustomRequestTemplate());
            llmNode.put("custom_response_path", llmSettings.getCustomResponsePath());

            ObjectNode rlNode = objectMapper.createObjectNode();
            rlNode.put("enabled", llmSettings.getRateLimiter().isEnabled());
            rlNode.put("max_calls_per_minute", llmSettings.getRateLimiter().getMaxCallsPerMinute());
            llmNode.set("rate_limiter", rlNode);

            root.set("llm", llmNode);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), root);
            log.info("LLM configuration saved to {}", configPath);
        } catch (IOException e) {
            log.error("Failed to persist LLM config to .noesis/config.json", e);
        }
    }

    public boolean shouldIndex(Path filePath) {
        Path absoluteRoot = Paths.get("").toAbsolutePath().normalize();
        Path absoluteFilePath = filePath.toAbsolutePath().normalize();
        Path relativePath;
        try {
            relativePath = absoluteRoot.relativize(absoluteFilePath);
        } catch (IllegalArgumentException e) {
            relativePath = absoluteFilePath;
        }

        String pathStr = relativePath.toString().replace('\\', '/');

        Path normalizedRelativePath = Paths.get(pathStr);
        for (PathMatcher matcher : excludeMatchers) {
            if (matcher.matches(normalizedRelativePath)) {
                log.debug("Path {} is excluded", pathStr);
                return false;
            }
        }

        for (PathMatcher matcher : includeMatchers) {
            if (matcher.matches(normalizedRelativePath)) {
                log.debug("Path {} matched include rules", pathStr);
                return true;
            }
        }

        log.debug("Path {} did not match any include rules", pathStr);
        return false;
    }
}
