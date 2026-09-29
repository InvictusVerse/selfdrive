package com.selfdriving.app;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.ui.driver.DriverScreen;
import com.selfdriving.ui.render.CameraRig;
import com.selfdriving.vehicle.DriverInput;

/**
 * Developer tool: plays a scripted drive and can save screenshots, for checking visuals and
 * making documentation images without a person at the keyboard.
 *
 * <p>Enabled with the system property {@code selfdrive.script}, a list of
 * {@code seconds:action[:value]} steps separated by semicolons, for example:
 * <pre>
 * -Dselfdrive.script="0.5:brake:1;0.8:gear:D;1:brake:0;1:throttle:1;6:shot:target/drive.png;6.5:exit"
 * </pre>
 * Actions: {@code throttle, brake, fullbrake, left, right} (0 or 1), {@code gear} (P/R/N/D),
 * {@code surface} (DRY/WET/SNOW/ICE), {@code camera} (CHASE/AUTOPILOT/TOP/SIDE),
 * {@code forces} (0/1), {@code abs} (0/1), {@code help} (0/1), {@code size} (window size,
 * e.g. 1280x720), {@code shot} (PNG path), {@code exit}.
 */
final class DevAutomation {

    static final String PROPERTY = "selfdrive.script";

    private static final System.Logger LOG = System.getLogger(DevAutomation.class.getName());

    private DevAutomation() {
    }

    static boolean isEnabled() {
        String script = System.getProperty(PROPERTY);
        return script != null && !script.isBlank();
    }

    static void run(Scene scene, Simulation simulation, DriverScreen screen) {
        Timeline timeline = new Timeline();
        for (String step : System.getProperty(PROPERTY).split(";")) {
            String[] parts = step.trim().split(":", 3);
            if (parts.length < 2) {
                continue;
            }
            double at = Double.parseDouble(parts[0]);
            String action = parts[1].toLowerCase(Locale.ROOT);
            String value = parts.length > 2 ? parts[2] : "";
            timeline.getKeyFrames().add(new KeyFrame(Duration.seconds(at),
                    e -> perform(action, value, scene, simulation, screen)));
        }
        timeline.play();
    }

    private static void perform(String action, String value, Scene scene, Simulation simulation,
                                DriverScreen screen) {
        DriverInput input = simulation.driverInput();
        boolean on = "1".equals(value);
        switch (action) {
            case "throttle" -> input.setAccelerate(on);
            case "brake" -> input.setBrake(on);
            case "fullbrake" -> input.setFullBrake(on);
            case "left" -> input.setSteerLeft(on);
            case "right" -> input.setSteerRight(on);
            case "gear" -> simulation.submit(sim -> sim.requestGear(parseGear(value)));
            case "surface" -> simulation.submit(sim -> sim.setSurface(Surface.valueOf(value.toUpperCase(Locale.ROOT))));
            case "abs" -> simulation.submit(sim -> sim.setAbsEnabled(on));
            case "camera" -> screen.drivingView().cameraRig().setMode(
                    CameraRig.Mode.valueOf(value.toUpperCase(Locale.ROOT)));
            case "forces" -> screen.drivingView().car().setForcesVisible(on);
            case "help" -> screen.setHelpVisible(on);
            case "size" -> {
                String[] wh = value.toLowerCase(Locale.ROOT).split("x");
                javafx.stage.Stage stage = (javafx.stage.Stage) scene.getWindow();
                stage.setMaximized(false);
                stage.setWidth(Double.parseDouble(wh[0]));
                stage.setHeight(Double.parseDouble(wh[1]));
            }
            case "shot" -> saveScreenshot(scene, Path.of(value));
            case "exit" -> Platform.exit();
            default -> LOG.log(Level.WARNING, "Unknown script action: {0}", action);
        }
    }

    private static Gear parseGear(String letter) {
        for (Gear gear : Gear.values()) {
            if (gear.letter().equalsIgnoreCase(letter)) {
                return gear;
            }
        }
        throw new IllegalArgumentException("Unknown gear: " + letter);
    }

    private static void saveScreenshot(Scene scene, Path file) {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImageIO.write(buffered, "png", file.toFile());
            LOG.log(Level.INFO, "Saved screenshot {0}", file.toAbsolutePath());
        } catch (IOException e) {
            LOG.log(Level.ERROR, "Could not save screenshot " + file, e);
        }
    }
}
