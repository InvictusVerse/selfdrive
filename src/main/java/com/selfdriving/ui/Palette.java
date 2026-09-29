package com.selfdriving.ui;

import javafx.scene.paint.Color;

/** Theme colours for widgets drawn on a canvas. Matches the variables in {@code theme.css}. */
public final class Palette {

    public static final Color BACKGROUND = Color.web("#0b0c0e");
    public static final Color SURFACE = Color.web("#15171b");
    public static final Color SURFACE_RAISED = Color.web("#1f2228");
    public static final Color BORDER = Color.web("#2a2e35");
    public static final Color TEXT = Color.web("#f2f3f5");
    public static final Color TEXT_MUTED = Color.web("#9aa0a8");
    public static final Color ACCENT = Color.web("#3e6ae1");
    public static final Color SUCCESS = Color.web("#2ecc71");
    public static final Color WARNING = Color.web("#f5a623");
    public static final Color DANGER = Color.web("#e5484d");
    public static final Color ROAD = Color.web("#3a3e45");
    public static final Color FORCE = Color.web("#37d6ff");

    private Palette() {
    }

    /** Green below 70 % grip use, amber up to 95 %, red at the limit. */
    public static Color gripColour(double usage) {
        if (usage < 0.7) {
            return SUCCESS;
        }
        if (usage < 0.95) {
            return WARNING;
        }
        return DANGER;
    }
}
