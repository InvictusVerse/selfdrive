package com.selfdriving.autopilot;

/**
 * Classic PID feedback controller: {@code u = Kp*e + Ki*integral(e) + Kd*de/dt}, with the
 * integral clamped to stop it winding up while the output is saturated.
 */
public final class PidController {

    private final double kp;
    private final double ki;
    private final double kd;
    private final double integralLimit;
    private double integral;
    private double previousError = Double.NaN;

    public PidController(double kp, double ki, double kd, double integralLimit) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
        this.integralLimit = integralLimit;
    }

    public double update(double error, double dt) {
        integral = Math.max(-integralLimit, Math.min(integralLimit, integral + error * dt));
        double derivative = Double.isNaN(previousError) || dt <= 0 ? 0 : (error - previousError) / dt;
        previousError = error;
        return kp * error + ki * integral + kd * derivative;
    }

    public void reset() {
        integral = 0;
        previousError = Double.NaN;
    }

    double integral() {
        return integral;
    }
}
