package com.noesis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.client.LlmSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoesisConfigServiceTest {

    private NoesisConfigService configService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        configService = new NoesisConfigService(objectMapper);
        
        // Add test watch directories
        LlmSettings settings = new LlmSettings();
        settings.setWatchDirectories(List.of(
            Paths.get("C:/workspace/project1").toAbsolutePath().toString(),
            Paths.get("D:/external-docs").toAbsolutePath().toString()
        ));
        configService.updateLlmSettings(settings);

        // Populate includeMatchers and excludeMatchers using reflection
        var fs = FileSystems.getDefault();
        
        Field includeField = NoesisConfigService.class.getDeclaredField("includeMatchers");
        includeField.setAccessible(true);
        List<PathMatcher> includeMatchers = (List<PathMatcher>) includeField.get(configService);
        includeMatchers.add(fs.getPathMatcher("glob:**/*.md"));
        includeMatchers.add(fs.getPathMatcher("glob:docs/**/*.md"));
        includeMatchers.add(fs.getPathMatcher("glob:README.md"));

        Field excludeField = NoesisConfigService.class.getDeclaredField("excludeMatchers");
        excludeField.setAccessible(true);
        List<PathMatcher> excludeMatchers = (List<PathMatcher>) excludeField.get(configService);
        excludeMatchers.add(fs.getPathMatcher("glob:node_modules/**"));
    }

    @Test
    void testShouldIndex_insideWatchDirectories() {
        Path matchedFile1 = Paths.get("C:/workspace/project1/docs/architecture/design.md");
        System.out.println("MatchedFile1 absolute path: " + matchedFile1.toAbsolutePath().normalize());
        System.out.println("MatchedFile1 shouldIndex result: " + configService.shouldIndex(matchedFile1));
        assertTrue(configService.shouldIndex(matchedFile1), "Should index a valid md file in watch directory");

        // File in project1 watched root matching excludes
        Path excludedFile = Paths.get("C:/workspace/project1/node_modules/package/README.md");
        assertFalse(configService.shouldIndex(excludedFile), "Should exclude node_modules files");

        // File in external-docs watched root matching includes
        Path matchedFile2 = Paths.get("D:/external-docs/README.md");
        assertTrue(configService.shouldIndex(matchedFile2), "Should index README in external watch directory");
    }

    @Test
    void testShouldIndex_fallbackToWorkspaceRoot() {
        // File outside watch directories should fallback to default workspace root
        Path matchedWorkspaceFile = Paths.get("docs/developer-guide.md").toAbsolutePath();
        assertTrue(configService.shouldIndex(matchedWorkspaceFile), "Should fallback to workspace root relativization and match");
    }
}
