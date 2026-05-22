package com.noesis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PredicateServiceTest {

    // ── levenshteinDistance ──────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "'',      '',       0",
        "'',      'A',      1",
        "'A',     '',       1",
        "'A',     'A',      0",
        "'A',     'B',      1",
        "'AB',    'AB',     0",
        "'AB',    'A',      1",
        "'A',     'AB',     1",
        "'KITTEN','SITTING', 3",
        "'ABC',   'ABC',    0",
    })
    void levenshteinDistance_basic(String a, String b, int expected) {
        assertEquals(expected, PredicateService.levenshteinDistance(a, b));
    }

    @Test
    void exact_match_is_0() {
        assertEquals(0, PredicateService.levenshteinDistance("IMPLEMENTS", "IMPLEMENTS"));
    }

    @Test
    void IMPLEMENTES_to_IMPLEMENTS_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("IMPLEMENTES", "IMPLEMENTS"));
    }

    @Test
    void AUTHENTICATE_to_AUTHENTICATES_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("AUTHENTICATE", "AUTHENTICATES"));
    }

    @Test
    void RECIEVE_to_RECEIVE_is_2() {
        assertEquals(2, PredicateService.levenshteinDistance("RECIEVE", "RECEIVE"));
    }

    @Test
    void WRITERS_to_WRITES_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("WRITERS", "WRITES"));
    }

    @Test
    void CALL_to_CALLS_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("CALL", "CALLS"));
    }

    @Test
    void STORAGE_to_STORES_is_3() {
        assertEquals(3, PredicateService.levenshteinDistance("STORAGE", "STORES"));
    }

    @Test
    void VALIDATE_to_VALIDATES_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("VALIDATE", "VALIDATES"));
    }

    @Test
    void CONFIGRE_to_CONFIGURE_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("CONFIGRE", "CONFIGURE"));
    }

    @Test
    void PROCESSS_to_PROCESSES_is_1() {
        assertEquals(1, PredicateService.levenshteinDistance("PROCESSS", "PROCESSES"));
    }

    @Test
    void case_sensitive() {
        assertEquals(5, PredicateService.levenshteinDistance("calls", "CALLS"));
    }

    @Test
    void far_apart() {
        assertTrue(PredicateService.levenshteinDistance("COMPLETELY_DIFFERENT", "READS") > 10);
    }

    @Test
    void empty_vs_nonempty() {
        assertEquals(5, PredicateService.levenshteinDistance("", "HELLO"));
        assertEquals(5, PredicateService.levenshteinDistance("HELLO", ""));
    }
}
