package com.selfdriving.ui.driver;

import javafx.scene.AccessibleRole;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Small vector tell-tale symbols in the style of a car's instrument panel (ISO 2575 shapes:
 * indicator arrows, dipped and main beam, hazard triangle, driver-assistance wheel).
 * Colours come from the style sheet ({@code .icon}, {@code .icon.lit-green} and so on).
 */
final class Icons {

    /** Headlamp body shared by the beam symbols. */
    private static final String LAMP = "M11 3 C17 3 19 7 19 10 C19 13 17 17 11 17 Z";

    private Icons() {
    }

    static Group indicatorLeft() {
        return filled("M1 10 L8 3 L8 7 L15 7 L15 13 L8 13 L8 17 Z");
    }

    static Group indicatorRight() {
        return filled("M19 10 L12 3 L12 7 L5 7 L5 13 L12 13 L12 17 Z");
    }

    /** Dipped beam: rays angled down. */
    static Group lowBeam() {
        return stroked(LAMP, "M1 5 L8 7 M1 9 L8 11 M1 13 L8 15");
    }

    /** Main beam: rays straight ahead. */
    static Group highBeam() {
        return stroked(LAMP, "M1 6 L8 6 M1 10 L8 10 M1 14 L8 14");
    }

    static Group hazard() {
        return stroked("M10 2 L19 17 L1 17 Z", "M10 7 L14 14 L6 14 Z");
    }

    /** Steering wheel: shown when the autopilot is available (grey) or driving (blue). */
    static Group autopilot() {
        return stroked("M10 1 A9 9 0 1 1 9.99 1 Z",
                "M1.5 9 L7 10 M18.5 9 L13 10 M10 13 L10 19 M7 10 A3 3 0 1 0 13 10 A3 3 0 1 0 7 10");
    }

    /** Wraps an icon as a clickable, focus-free button with a tooltip and accessible text. */
    static StackPane button(Group icon, String text, Runnable action) {
        StackPane box = new StackPane(icon);
        box.getStyleClass().add("icon-button");
        box.setAccessibleRole(AccessibleRole.BUTTON);
        box.setAccessibleText(text);
        Tooltip.install(box, new Tooltip(text));
        box.setOnMouseClicked(e -> action.run());
        return box;
    }

    /** Applies a lit colour class (e.g. "lit-green"), or none to show the symbol dimmed. */
    static void light(Node icon, String litClass) {
        for (Node part : ((Group) icon).getChildren()) {
            part.getStyleClass().removeAll("lit-green", "lit-blue", "lit-amber", "lit-red");
            if (litClass != null) {
                part.getStyleClass().add(litClass);
            }
        }
    }

    private static Group filled(String path) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().addAll("icon", "icon-filled");
        return new Group(p);
    }

    private static Group stroked(String... paths) {
        Group g = new Group();
        for (String path : paths) {
            SVGPath p = new SVGPath();
            p.setContent(path);
            p.getStyleClass().addAll("icon", "icon-stroked");
            g.getChildren().add(p);
        }
        return g;
    }
}
