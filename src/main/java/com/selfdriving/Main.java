package com.selfdriving;

import com.selfdriving.app.SelfDrivingApp;
import javafx.application.Application;

/**
 * Program entry point.
 *
 * <p>Kept separate from the JavaFX {@link Application} subclass so the app also starts
 * from a plain classpath (IDE run button, packaged jar) without the
 * "JavaFX runtime components are missing" error.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(SelfDrivingApp.class, args);
    }
}
