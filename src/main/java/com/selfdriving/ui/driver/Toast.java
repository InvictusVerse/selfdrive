package com.selfdriving.ui.driver;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** Short message that appears at the top of the 3D view and fades away. */
final class Toast {

    private final StackPane root = new StackPane();
    private final Label label = new Label();
    private final SequentialTransition animation;

    Toast() {
        label.getStyleClass().add("toast");
        label.setOpacity(0);
        label.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        root.getChildren().add(label);
        StackPane.setAlignment(label, Pos.TOP_CENTER);
        StackPane.setMargin(label, new Insets(120, 0, 0, 0));
        root.setMouseTransparent(true);
        root.setPickOnBounds(false);

        FadeTransition in = new FadeTransition(Duration.millis(150), label);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(3.5));
        FadeTransition out = new FadeTransition(Duration.millis(600), label);
        out.setToValue(0);
        animation = new SequentialTransition(in, hold, out);
    }

    StackPane node() {
        return root;
    }

    void show(String message) {
        label.setText(message);
        animation.stop();
        animation.playFromStart();
    }
}
