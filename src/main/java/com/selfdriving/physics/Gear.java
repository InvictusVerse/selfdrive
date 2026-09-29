package com.selfdriving.physics;

/** Drive selector position, as on an electric car: Park, Reverse, Neutral, Drive. */
public enum Gear {

    PARK("P"),
    REVERSE("R"),
    NEUTRAL("N"),
    DRIVE("D");

    private final String letter;

    Gear(String letter) {
        this.letter = letter;
    }

    /** Single letter shown on the display. */
    public String letter() {
        return letter;
    }
}
