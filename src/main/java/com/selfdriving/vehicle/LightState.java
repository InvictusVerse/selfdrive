package com.selfdriving.vehicle;

/**
 * The car's exterior lights at one moment. Immutable, published with every snapshot.
 *
 * @param indicator      indicator lever position
 * @param hazard         hazard warning lights switched on
 * @param blinkOn        the flashing lamps are lit at this moment (flash phase)
 * @param headlightMode  headlight switch position
 * @param lowBeam        dipped headlights lit
 * @param highBeam       main (high) beam lit
 * @param tailLights     tail and position lights lit
 * @param brakeLights    brake lights lit
 * @param reverseLights  reversing lights lit
 * @param dark           it is dark outside (drives the automatic headlights)
 */
public record LightState(
        Lights.Indicator indicator,
        boolean hazard,
        boolean blinkOn,
        Lights.HeadlightMode headlightMode,
        boolean lowBeam,
        boolean highBeam,
        boolean tailLights,
        boolean brakeLights,
        boolean reverseLights,
        boolean dark) {

    /** All off, daylight. */
    public static LightState off() {
        return new LightState(Lights.Indicator.OFF, false, false, Lights.HeadlightMode.AUTO, false, false, false,
                false, false, false);
    }

    /** Left indicator lamps are lit right now (indicator or hazard). */
    public boolean leftLit() {
        return blinkOn && (hazard || indicator == Lights.Indicator.LEFT);
    }

    /** Right indicator lamps are lit right now (indicator or hazard). */
    public boolean rightLit() {
        return blinkOn && (hazard || indicator == Lights.Indicator.RIGHT);
    }
}
