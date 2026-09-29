package com.selfdriving.ui.driver;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;

/** Keyboard reference shown over the 3D view. */
final class HelpOverlay {

    private static final String[][] KEYS = {
            {"W  /  \u2191", "Accelerate"},
            {"S  /  \u2193", "Brake (hold to press harder)"},
            {"A D  /  \u2190 \u2192", "Steer"},
            {"Space", "Full brake (start a braking test)"},
            {"1  2  3  4", "Park, Reverse, Neutral, Drive (hold brake to leave Park)"},
            {"G", "Change road surface"},
            {"B  /  T", "ABS  /  traction control on or off"},
            {"C", "Change camera"},
            {"F", "Show tyre force arrows"},
            {"M  /  P", "Slow motion  /  pause"},
            {"Backspace", "Reset to the start line"},
            {"E", "Autopilot on or off (choose a destination first)"},
            {"X", "Emergency stop"},
            {"L", "Show lidar points"},
            {"O", "On-screen wheel and pedals on or off"},
            {"F11", "Full screen on or off"},
            {"H", "Show or hide this panel"},
    };

    private final StackPane root = new StackPane();

    HelpOverlay() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("help-panel");
        Label title = new Label("Driving controls");
        title.getStyleClass().add("help-title");
        grid.add(title, 0, 0, 2, 1);
        for (int i = 0; i < KEYS.length; i++) {
            Label key = new Label(KEYS[i][0]);
            key.getStyleClass().add("help-key");
            Label action = new Label(KEYS[i][1]);
            grid.add(key, 0, i + 1);
            grid.add(action, 1, i + 1);
        }
        Label hint = new Label("To drive off: hold S, press 4 (Drive), then W.  "
                + "Or tap D on the screen and hold ACCEL; drag the wheel to steer.");
        hint.getStyleClass().add("muted");
        grid.add(hint, 0, KEYS.length + 1, 2, 1);
        grid.setMaxSize(GridPane.USE_PREF_SIZE, GridPane.USE_PREF_SIZE);

        root.getChildren().add(grid);
        StackPane.setAlignment(grid, Pos.CENTER);
        root.setMouseTransparent(true);
        root.setPickOnBounds(false);
    }

    StackPane node() {
        return root;
    }

    boolean isVisible() {
        return root.isVisible();
    }

    void setVisible(boolean visible) {
        root.setVisible(visible);
    }
}
