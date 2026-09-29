package com.selfdriving.auth;

/** Level within a role: FULL adds the role's extra permissions (see {@link Role}). */
public enum AccessLevel {
    STANDARD("Standard"), FULL("Full");

    private final String label;

    AccessLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
