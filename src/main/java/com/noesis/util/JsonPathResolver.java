package com.noesis.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class JsonPathResolver {

    private static final Pattern BRACKET_INDEX = Pattern.compile("^(\\w+)\\[(\\d+)\\]$");

    public static String resolve(JsonNode root, String path) {
        if (path == null || path.isBlank()) return null;
        String[] segments = path.replace("$.", "").split("\\.");
        JsonNode current = root;
        for (String seg : segments) {
            if (current == null || current.isNull()) return null;
            seg = seg.trim();
            Matcher m = BRACKET_INDEX.matcher(seg);
            if (m.matches()) {
                current = current.path(m.group(1));
                int idx = Integer.parseInt(m.group(2));
                if (current.isArray() && idx < current.size()) {
                    current = current.get(idx);
                } else {
                    return null;
                }
            } else {
                current = current.path(seg);
            }
        }
        return current != null && !current.isNull() ? current.asText() : null;
    }

    public static JsonNode resolveNode(JsonNode root, String path) {
        if (path == null || path.isBlank()) return null;
        String[] segments = path.replace("$.", "").split("\\.");
        JsonNode current = root;
        for (String seg : segments) {
            if (current == null || current.isNull()) return null;
            seg = seg.trim();
            Matcher m = BRACKET_INDEX.matcher(seg);
            if (m.matches()) {
                current = current.path(m.group(1));
                int idx = Integer.parseInt(m.group(2));
                if (current.isArray() && idx < current.size()) {
                    current = current.get(idx);
                } else {
                    return null;
                }
            } else {
                current = current.path(seg);
            }
        }
        return current;
    }

    public static String renderTemplate(String template, String model, String prompt, ObjectMapper objectMapper) {
        if (template == null || template.isBlank()) return null;
        try {
            JsonNode templateNode = objectMapper.readTree(template);
            String json = templateNode.toString();
            json = json.replace("{{model}}", model);
            json = json.replace("{{prompt}}", escapeJson(prompt));
            return json;
        } catch (Exception e) {
            log.error("Failed to render custom request template", e);
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
