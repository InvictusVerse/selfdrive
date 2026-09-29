package com.selfdriving.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.selfdriving.auth.Permission;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.SettingsRepository;

/**
 * System settings: typed, range-checked, stored in the database and applied to the running car.
 * Only users with {@link Permission#EDIT_SETTINGS} can change them; software updates may change
 * them too (recorded as done by the update).
 */
public final class SettingsService {

    /** Kinds of values. */
    public enum Type { INTEGER, DECIMAL, BOOLEAN }

    /** A setting and its allowed values. */
    public record Definition(String key, String label, Type type, double min, double max, String defaultValue,
                             String unit, String help) {
    }

    public static final String MAX_AUTOPILOT_SPEED = "autopilot.max_speed_kmh";
    public static final String EMERGENCY_BRAKING = "safety.emergency_braking";
    public static final String AEB_SENSITIVITY = "safety.aeb_margin_s";
    public static final String FOLLOW_TIME_GAP = "autopilot.follow_time_gap_s";
    public static final String TRAFFIC = "world.traffic_vehicles";
    public static final String SOFTWARE_VERSION = "system.software_version";

    public static final List<Definition> DEFINITIONS = List.of(
            new Definition(MAX_AUTOPILOT_SPEED, "Maximum autopilot speed", Type.INTEGER, 10, 130, "100", "km/h",
                    "The autopilot never drives faster than this, whatever the speed limit"),
            new Definition(FOLLOW_TIME_GAP, "Following distance", Type.DECIMAL, 0.8, 3.0, "1.2", "s",
                    "Time gap the autopilot keeps to the vehicle ahead (plus 4 m)"),
            new Definition(EMERGENCY_BRAKING, "Automatic emergency braking", Type.BOOLEAN, 0, 1, "true", "",
                    "Brake automatically when a collision is imminent"),
            new Definition(AEB_SENSITIVITY, "Emergency braking reaction allowance", Type.DECIMAL, 0.1, 0.8, "0.25", "s",
                    "Extra time added to the stopping distance before emergency braking starts"),
            new Definition(TRAFFIC, "Traffic in the city", Type.INTEGER, 0, 240, "160", "vehicles",
                    "Other vehicles driving around the map"));

    private final SettingsRepository repository;
    private final AuditRepository audit;
    private final List<Consumer<Map<String, String>>> listeners = new CopyOnWriteArrayList<>();

    public SettingsService(SettingsRepository repository, AuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public static Definition definition(String key) {
        for (Definition d : DEFINITIONS) {
            if (d.key().equals(key)) {
                return d;
            }
        }
        throw new ValidationException("Unknown setting " + key);
    }

    /** Called with all values after every change. */
    public void onChange(Consumer<Map<String, String>> listener) {
        listeners.add(listener);
    }

    public String get(String key) {
        return repository.get(key).orElseGet(() -> key.equals(SOFTWARE_VERSION) ? "2.0.0" : definition(key).defaultValue());
    }

    public double number(String key) {
        return Double.parseDouble(get(key));
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(get(key));
    }

    /** Every setting with its current value. */
    public Map<String, String> all() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Definition d : DEFINITIONS) {
            result.put(d.key(), get(d.key()));
        }
        result.put(SOFTWARE_VERSION, get(SOFTWARE_VERSION));
        return result;
    }

    /** Changes a setting (Admin). */
    public void set(Session session, String key, String value) {
        session.require(Permission.EDIT_SETTINGS);
        String clean = validate(key, value);
        String before = get(key);
        repository.put(key, clean, session.userId());
        audit.log(session.userId(), session.user().username(), "SETTING_CHANGED",
                definition(key).label() + ": " + before + " -> " + clean);
        notifyListeners();
    }

    /** Changes a setting as part of a software update (no user permission involved). */
    void setBySystem(String key, String value, String reason) {
        String clean = key.equals(SOFTWARE_VERSION) ? value : validate(key, value);
        repository.put(key, clean, null);
        audit.log(null, "system", "SETTING_CHANGED", reason + ": " + key + " = " + clean);
        notifyListeners();
    }

    private void notifyListeners() {
        Map<String, String> values = all();
        for (Consumer<Map<String, String>> l : listeners) {
            l.accept(values);
        }
    }

    /** Checks a value against its definition and returns it in canonical form. */
    public static String validate(String key, String value) {
        Definition d = definition(key);
        String v = value == null ? "" : value.trim();
        switch (d.type()) {
            case BOOLEAN -> {
                String lower = v.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new ValidationException(d.label() + ": choose on or off");
                }
                return lower;
            }
            case INTEGER, DECIMAL -> {
                double n;
                try {
                    n = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw new ValidationException(d.label() + ": enter a number");
                }
                if (Double.isNaN(n) || n < d.min() || n > d.max()) {
                    throw new ValidationException(String.format(Locale.ROOT, "%s: between %s and %s %s", d.label(),
                            format(d.min(), d.type()), format(d.max(), d.type()), d.unit()).trim());
                }
                return format(n, d.type());
            }
            default -> throw new IllegalStateException();
        }
    }

    private static String format(double n, Type type) {
        return type == Type.INTEGER ? Long.toString(Math.round(n)) : String.format(Locale.ROOT, "%.2f", n)
                .replaceAll("0+$", "").replaceAll("\\.$", ".0");
    }

    /** Keys in display order. */
    public static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (Definition d : DEFINITIONS) {
            keys.add(d.key());
        }
        return keys;
    }
}
