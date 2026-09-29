package com.selfdriving.alerts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Central alert channel (Observer pattern). Every subsystem publishes here; screens and, later,
 * the database subscribe.
 *
 * <p>The same message from the same source is not repeated within {@link #REPEAT_INTERVAL}
 * seconds, so a persistent condition does not flood the display. Critical alerts stay active
 * until acknowledged.
 *
 * <p>Owned by the simulation thread; snapshots carry immutable copies.
 */
public final class AlertBus {

    /** Minimum time before the same alert is raised again, s. */
    public static final double REPEAT_INTERVAL = 5.0;

    private static final int HISTORY = 100;

    private final Deque<Alert> history = new ArrayDeque<>();
    private final Map<String, Double> lastRaised = new HashMap<>();
    private final List<Consumer<Alert>> listeners = new ArrayList<>();
    private long nextId = 1;

    /** Subscribes to every new alert. */
    public void subscribe(Consumer<Alert> listener) {
        listeners.add(listener);
    }

    /**
     * Raises an alert unless the same one was raised moments ago.
     *
     * @return the alert, or null if it was a repeat
     */
    public Alert publish(double time, Alert.Severity severity, Alert.Category category, String message,
                         String source) {
        String key = source + '|' + message;
        Double last = lastRaised.get(key);
        if (last != null && time - last < REPEAT_INTERVAL) {
            return null;
        }
        lastRaised.put(key, time);
        Alert alert = new Alert(nextId++, time, severity, category, message, source, false);
        history.addFirst(alert);
        while (history.size() > HISTORY) {
            history.removeLast();
        }
        for (Consumer<Alert> listener : listeners) {
            listener.accept(alert);
        }
        return alert;
    }

    /** Marks an alert as seen. */
    public void acknowledge(long id) {
        List<Alert> updated = new ArrayList<>(history.size());
        for (Alert a : history) {
            updated.add(a.id() == id ? a.withAcknowledged() : a);
        }
        history.clear();
        history.addAll(updated);
    }

    /** Most recent alerts, newest first. */
    public List<Alert> recent(int max) {
        List<Alert> result = new ArrayList<>(Math.min(max, history.size()));
        for (Alert a : history) {
            if (result.size() == max) {
                break;
            }
            result.add(a);
        }
        return List.copyOf(result);
    }

    /** Critical alerts nobody has acknowledged yet, newest first. */
    public List<Alert> unacknowledgedCritical() {
        List<Alert> result = new ArrayList<>();
        for (Alert a : history) {
            if (a.severity() == Alert.Severity.CRITICAL && !a.acknowledged()) {
                result.add(a);
            }
        }
        return List.copyOf(result);
    }
}
