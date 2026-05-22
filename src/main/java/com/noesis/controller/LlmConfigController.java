package com.noesis.controller;

import com.noesis.client.LlmSettings;
import com.noesis.service.NoesisConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmConfigController {

    private final NoesisConfigService configService;

    @GetMapping("/config")
    public ResponseEntity<LlmSettings> getConfig() {
        return ResponseEntity.ok(configService.getLlmSettings());
    }

    @PutMapping("/config")
    public ResponseEntity<Void> updateConfig(@RequestBody LlmSettings settings) {
        log.info("Updating LLM config: provider={}, model={}", settings.getProvider(), settings.getModel());
        configService.updateLlmSettings(settings);
        return ResponseEntity.ok().build();
    }
}
