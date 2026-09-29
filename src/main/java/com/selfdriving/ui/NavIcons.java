package com.selfdriving.ui;

import javafx.scene.Group;
import javafx.scene.shape.SVGPath;

/** Line icons (20 x 20) for the navigation rail. Colours come from {@code .rail-icon} in the style sheet. */
public final class NavIcons {

    public static final String DRIVE = "M10 1 A9 9 0 1 1 9.99 1 Z M1.5 9 L7 10 M18.5 9 L13 10 M10 13 L10 19 "
            + "M7 10 A3 3 0 1 0 13 10 A3 3 0 1 0 7 10";
    public static final String TRIPS = "M4 14.5 A2 2 0 1 1 3.99 14.5 Z M16 2.5 A2 2 0 1 1 15.99 2.5 Z "
            + "M5.5 15 C10 13 6 9 10 8 C13 7 12 6 14.5 5.5";
    public static final String USERS = "M10 2.5 A3.5 3.5 0 1 1 9.99 2.5 Z M3 18 C3 11.5 17 11.5 17 18";
    public static final String SETTINGS = "M3 5 L17 5 M3 10 L17 10 M3 15 L17 15 M7 3.3 A1.7 1.7 0 1 1 6.99 3.3 Z "
            + "M13 8.3 A1.7 1.7 0 1 1 12.99 8.3 Z M9 13.3 A1.7 1.7 0 1 1 8.99 13.3 Z";
    public static final String ALERTS = "M10 2 C6.5 2 5 4.5 5 8 L5 12 L3 15 L17 15 L15 12 L15 8 C15 4.5 13.5 2 10 2 Z "
            + "M8 17 C8.5 19 11.5 19 12 17";
    public static final String AUDIT = "M4 2 L16 2 L16 18 L4 18 Z M7 6 L13 6 M7 10 L13 10 M7 14 L11 14";
    public static final String PERFORMANCE = "M3 17 L17 17 M3 17 L3 3 M5.5 13.5 L9 9 L12 12 L17 5.5";
    public static final String UPDATES = "M10 2.5 L10 13 M6 9 L10 13 L14 9 M3.5 16.5 L16.5 16.5";
    public static final String DIAGNOSTICS = "M1.5 10 L6 10 L8 4.5 L12 15.5 L14 10 L18.5 10";
    public static final String ISSUES = "M5 18.5 L5 2.5 M5 3 L15.5 3 L13 7 L15.5 11 L5 11";
    public static final String TESTS = "M10 1 A9 9 0 1 1 9.99 1 Z M6 10 L9 13 L14.5 7";
    public static final String MAINTENANCE = "M12.5 2.5 A4.5 4.5 0 0 0 8.5 8.5 L2.5 14.5 L5.5 17.5 L11.5 11.5 "
            + "A4.5 4.5 0 0 0 17.5 7.5 L14.5 9 L11 5.5 Z";
    public static final String DASHBOARD = "M2.5 2.5 L8.5 2.5 L8.5 10.5 L2.5 10.5 Z M11.5 2.5 L17.5 2.5 L17.5 7.5 "
            + "L11.5 7.5 Z M11.5 10.5 L17.5 10.5 L17.5 17.5 L11.5 17.5 Z M2.5 13.5 L8.5 13.5 L8.5 17.5 L2.5 17.5 Z";
    public static final String PASSWORD = "M5.5 10.5 A3.5 3.5 0 1 1 5.49 10.5 Z M8.5 12 L17.5 12 M14.5 12 L14.5 15 "
            + "M17 12 L17 14.5";
    public static final String LOGOUT = "M8 2.5 L3 2.5 L3 17.5 L8 17.5 M12.5 6 L16.5 10 L12.5 14 M16.5 10 L7.5 10";

    private NavIcons() {
    }

    public static Group icon(String path) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("rail-icon");
        return new Group(p);
    }
}
