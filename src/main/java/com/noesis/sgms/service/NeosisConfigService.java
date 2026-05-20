package com.noesis.sgms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NeosisConfigService {

    private final ObjectMapper objectMapper;
    private final List<PathMatcher> includeMatchers = new ArrayList<>();
    private final List<PathMatcher> excludeMatchers = new ArrayList<>();

    private static final String CONFIG_FILE_PATH = ".neosis/config.json";

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

        if (Files.exists(configPath)) {
            try {
                log.info("Loading Neosis configuration from: {}", configPath);
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
            } catch (IOException e) {
                log.error("Failed to parse .neosis/config.json, falling back to default rules", e);
            }
        } else {
            log.info(".neosis/config.json not found. Using default rules.");
        }

        // Fallbacks if lists are empty
        if (includes.isEmpty()) {
            includes.addAll(List.of("**/*.md", "docs/**/*.md", "README.md"));
        }
        if (excludes.isEmpty()) {
            excludes.addAll(List.of("node_modules/**", ".git/**", "dist/**", "build/**", ".neosis/**", "**/.*/**"));
        }

        FileSystem fs = FileSystems.getDefault();
        for (String pattern : includes) {
            includeMatchers.add(fs.getPathMatcher("glob:" + pattern));
        }
        for (String pattern : excludes) {
            excludeMatchers.add(fs.getPathMatcher("glob:" + pattern));
        }

        log.info("Configured {} include and {} exclude matchers", includeMatchers.size(), excludeMatchers.size());
    }

    public boolean shouldIndex(Path filePath) {
        // Relativize path to repository root
        Path absoluteRoot = Paths.get("").toAbsolutePath().normalize();
        Path absoluteFilePath = filePath.toAbsolutePath().normalize();
        Path relativePath;
        try {
            relativePath = absoluteRoot.relativize(absoluteFilePath);
        } catch (IllegalArgumentException e) {
            relativePath = absoluteFilePath;
        }

        // Replace windows backslash with forward slash for standard glob matching
        String pathStr = relativePath.toString().replace('\\', '/');

        // Check excludes first
        Path normalizedRelativePath = Paths.get(pathStr);
        for (PathMatcher matcher : excludeMatchers) {
            if (matcher.matches(normalizedRelativePath)) {
                log.debug("Path {} is excluded", pathStr);
                return false;
            }
        }

        // Check includes
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
