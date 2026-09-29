package com.selfdriving.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** System settings as key/value pairs (typed and validated by the settings service). */
public final class SettingsRepository {

    private final Jdbc jdbc;

    public SettingsRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    public Optional<String> get(String key) {
        return jdbc.one("SELECT setting_value FROM settings WHERE setting_key = ?", r -> r.getString(1), key);
    }

    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String[] kv : jdbc.query("SELECT setting_key, setting_value FROM settings ORDER BY setting_key",
                r -> new String[] {r.getString(1), r.getString(2)})) {
            result.put(kv[0], kv[1]);
        }
        return result;
    }

    public void put(String key, String value, Long userId) {
        int changed = jdbc.update("UPDATE settings SET setting_value = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE setting_key = ?", value, userId, key);
        if (changed == 0) {
            jdbc.update("INSERT INTO settings (setting_key, setting_value, updated_by) VALUES (?, ?, ?)", key, value,
                    userId);
        }
    }
}
