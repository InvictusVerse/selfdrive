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
import com.selfdriving.vehicle.Lights;

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
 * e.g. 1280x720), {@code destination} (place name), {@code autopilot} (0/1),
 * {@code scenario} (pedestrian/stopped/closed/clear), {@code lidar} (0/1),
 * {@code speed} (time scale, e.g. 4), {@code indicator} (LEFT/RIGHT), {@code hazard},
 * {@code headlights} (OFF/AUTO/ON), {@code mainbeam}, {@code traffic} (number of vehicles), {@code clock} (e.g. 21.30),
 * {@code fps} (seconds to measure the frame rate), {@code shot} (PNG path), {@code exit}. (The step separator ':' means clock times use '.'.)
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
            case "destination" -> simulation.world().places().stream()
                    .filter(p -> p.name().equalsIgnoreCase(value)).findFirst()
                    .ifPresent(place -> simulation.submit(sim -> sim.setDestination(place)));
            case "autopilot" -> simulation.submit(on ? Simulation::engageAutopilot : Simulation::disengageAutopilot);
            case "scenario" -> simulation.submit(switch (value) {
                case "pedestrian" -> Simulation::scenarioPedestrian;
                case "stopped" -> Simulation::scenarioStoppedVehicle;
                case "slow" -> Simulation::scenarioSlowVehicle;
                case "closed" -> Simulation::scenarioRoadClosed;
                default -> Simulation::clearScenarios;
            });
            case "lidar" -> screen.drivingView().setLidarVisible(on);
            case "speed" -> simulation.submit(sim -> sim.setTimeScale(Double.parseDouble(value)));
            case "indicator" -> simulation.submit(sim -> sim.toggleIndicator(
                    Lights.Indicator.valueOf(value.toUpperCase(Locale.ROOT))));
            case "hazard" -> simulation.submit(Simulation::toggleHazard);
            case "traffic" -> simulation.submit(sim -> sim.setTrafficCount(Integer.parseInt(value)));
            case "headlights" -> simulation.submit(sim -> sim.setHeadlightMode(
                    Lights.HeadlightMode.valueOf(value.toUpperCase(Locale.ROOT))));
            case "mainbeam" -> simulation.submit(Simulation::toggleMainBeam);
            case "clock" -> {
                String[] hm = value.split("[.h]");
                double seconds = Integer.parseInt(hm[0]) * 3600.0 + (hm.length > 1 ? Integer.parseInt(hm[1]) * 60 : 0);
                simulation.submit(sim -> sim.setTimeOfDay(seconds));
            }
            case "size" -> {
                String[] wh = value.toLowerCase(Locale.ROOT).split("x");
                javafx.stage.Stage stage = (javafx.stage.Stage) scene.getWindow();
                stage.setMaximized(false);
                stage.setWidth(Double.parseDouble(wh[0]));
                stage.setHeight(Double.parseDouble(wh[1]));
            }
            case "shot" -> saveScreenshot(scene, Path.of(value));
            case "fps" -> measureFrameRate(Double.parseDouble(value));
            case "exit" -> Platform.exit();
            default -> LOG.log(Level.WARNING, "Unknown script action: {0}", action);
        }
    }

    /** Counts rendered frames for a while and logs the average and worst frame time. */
    private static void measureFrameRate(double seconds) {
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            private long first;
            private long last;
            private long worst;
            private int frames;

            @Override
            public void handle(long now) {
                if (first == 0) {
                    first = now;
                } else {
                    worst = Math.max(worst, now - last);
                    frames++;
                }
                last = now;
                if (now - first > seconds * 1e9) {
                    stop();
                    LOG.log(Level.INFO, String.format("Frame rate: %.1f fps average, worst frame %.1f ms",
                            frames / ((now - first) / 1e9), worst / 1e6));
                }
            }
        };
        timer.start();
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
