package com.noesis.sgms.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle status of a semantic assertion.
 *
 * <ul>
 *   <li>{@link #ACTIVE} – the assertion represents current knowledge.</li>
 *   <li>{@link #SUPERSEDED} – the assertion has been replaced by a newer version.</li>
 * </ul>
 */
public enum AssertionStatus {

    ACTIVE("active"),
    SUPERSEDED("superseded");

    private final String value;

    AssertionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static AssertionStatus fromValue(String value) {
        for (AssertionStatus s : values()) {
            if (s.value.equalsIgnoreCase(value)) return s;
        }
        throw new IllegalArgumentException("Unknown assertion status: " + value);
    }
}
