package com.noesis.sgms.service;

import com.noesis.sgms.dto.AssertionExtractionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssertionValidationService {

    private final PredicateService predicateService;

    public boolean isValid(AssertionExtractionResponse assertion, String chunkContent) {
        if (assertion.getSubjectText() == null || assertion.getSubjectText().isBlank()) {
            log.warn("Validation failed: empty subject");
            return false;
        }

        if (assertion.getObjectText() == null || assertion.getObjectText().isBlank()) {
            log.warn("Validation failed: empty object");
            return false;
        }

        if (assertion.getNormalizedText() == null || assertion.getNormalizedText().isBlank()) {
            log.warn("Validation failed: empty normalized_text");
            return false;
        }

        String canonicalPredicate = resolvePredicate(assertion.getPredicate());
        if (canonicalPredicate == null) {
            log.warn("Validation failed: invalid predicate '{}'", assertion.getPredicate());
            return false;
        }
        assertion.setPredicate(canonicalPredicate);

        if (assertion.getRawText() == null || assertion.getRawText().isBlank()) {
            log.warn("Validation failed: empty raw_text");
            return false;
        }

        // Verify raw text is present in the chunk using flexible matching
        // Normalize whitespace and remove leading/trailing punctuation for robust comparison
        String normalizedChunk = chunkContent.replaceAll("\\s+", " ").replaceAll("[^\\w\\s]", "").trim().toLowerCase();
        String normalizedRawText = assertion.getRawText().replaceAll("\\s+", " ").replaceAll("[^\\w\\s]", "").trim().toLowerCase();
        
        if (!normalizedChunk.contains(normalizedRawText)) {
            // Try matching just the suffix if the LLM prepended entity names
            String[] rawWords = normalizedRawText.split("\\s+");
            if (rawWords.length > 4) {
                String suffix = String.join(" ", java.util.Arrays.copyOfRange(rawWords, Math.min(3, rawWords.length - 3), rawWords.length));
                if (!normalizedChunk.contains(suffix)) {
                    // Fallback: check if all words appear in order (allowing verb form differences)
                    if (!wordsMatchInOrder(normalizedChunk, rawWords)) {
                        log.warn("Validation failed: raw_text not found in chunk content. Raw text: '{}'", assertion.getRawText());
                        return false;
                    }
                }
            } else {
                // Check if all words appear in order (handles verb form mismatches like "support" vs "supports")
                if (!wordsMatchInOrder(normalizedChunk, rawWords)) {
                    log.warn("Validation failed: raw_text not found in chunk content. Raw text: '{}'", assertion.getRawText());
                    return false;
                }
            }
        }

        return true;
    }

    public boolean isPredicateInvalidOnly(AssertionExtractionResponse assertion, String chunkContent) {
        if (assertion.getSubjectText() == null || assertion.getSubjectText().isBlank()) return false;
        if (assertion.getObjectText() == null || assertion.getObjectText().isBlank()) return false;
        if (assertion.getNormalizedText() == null || assertion.getNormalizedText().isBlank()) return false;
        if (assertion.getRawText() == null || assertion.getRawText().isBlank()) return false;

        String normalizedChunk = chunkContent.replaceAll("\\s+", " ").replaceAll("[^\\w\\s]", "").trim().toLowerCase();
        String normalizedRawText = assertion.getRawText().replaceAll("\\s+", " ").replaceAll("[^\\w\\s]", "").trim().toLowerCase();
        
        boolean rawPresent = normalizedChunk.contains(normalizedRawText);
        if (!rawPresent) {
            String[] rawWords = normalizedRawText.split("\\s+");
            if (rawWords.length > 4) {
                String suffix = String.join(" ", java.util.Arrays.copyOfRange(rawWords, Math.min(3, rawWords.length - 3), rawWords.length));
                rawPresent = normalizedChunk.contains(suffix) || wordsMatchInOrder(normalizedChunk, rawWords);
            } else {
                rawPresent = wordsMatchInOrder(normalizedChunk, rawWords);
            }
        }

        if (!rawPresent) return false;

        // Valid assertion fields and grounded raw text, but unrecognized predicate
        return resolvePredicate(assertion.getPredicate()) == null;
    }

    public String resolvePredicate(String predicate) {
        if (predicate == null) return null;
        String upper = predicate.toUpperCase();
        Set<String> validPredicates = predicateService.getActivePredicates();

        // 1. Exact match
        if (validPredicates.contains(upper)) return upper;

        // Skip normalization for compound predicates with underscores
        if (!upper.contains("_")) {

            // 2. Plural/singular via trailing 'S'
            if (upper.endsWith("S") && !upper.endsWith("SS")) {
                String singular = upper.substring(0, upper.length() - 1);
                if (validPredicates.contains(singular)) return singular;
            } else {
                String plural = upper + "S";
                if (validPredicates.contains(plural)) return plural;
            }

            // 3. Past tense: strip "ED" (EXTRACTED → EXTRACT → EXTRACTS)
            if (upper.endsWith("ED") && upper.length() > 3) {
                String stem = upper.substring(0, upper.length() - 2);
                if (validPredicates.contains(stem)) return stem;
                if (validPredicates.contains(stem + "S")) return stem + "S";
                if (validPredicates.contains(stem + "ES")) return stem + "ES";
            }

            // 4. Present participle: strip "ING" (EXTRACTING → EXTRACT → EXTRACTS)
            if (upper.endsWith("ING") && upper.length() > 4) {
                String stem = upper.substring(0, upper.length() - 3);
                if (validPredicates.contains(stem)) return stem;
                if (validPredicates.contains(stem + "S")) return stem + "S";
                if (validPredicates.contains(stem + "ES")) return stem + "ES";
                // Handle doubled consonant: SPLITTING → SPLIT → try SPLITS
                if (stem.length() > 1 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2)) {
                    String singleStem = stem.substring(0, stem.length() - 1);
                    if (validPredicates.contains(singleStem)) return singleStem;
                    if (validPredicates.contains(singleStem + "S")) return singleStem + "S";
                }
            }

            // 5. Third-person "ES" (PASSES → PASS, WATCHES → WATCH)
            if (upper.endsWith("ES") && !upper.endsWith("SS") && upper.length() > 3) {
                String stem = upper.substring(0, upper.length() - 2);
                if (validPredicates.contains(stem)) return stem;
            }
        }

        return null;
    }

    private boolean wordsMatchInOrder(String text, String[] words) {
        int ti = 0;
        for (String w : words) {
            ti = text.indexOf(w, ti);
            if (ti < 0) return false;
            ti += w.length();
        }
        return true;
    }
}
