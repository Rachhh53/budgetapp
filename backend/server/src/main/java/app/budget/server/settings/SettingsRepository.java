package app.budget.server.settings;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.generic.GenericType;

/** Reads and writes the {@code settings} table. Unset keys fall back to their defaults. */
public final class SettingsRepository {

    private static final GenericType<Map<String, String>> STRING_MAP = new GenericType<>() {};

    private final Jdbi jdbi;
    private final Clock clock;

    public SettingsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** Every known setting with its effective value, in a stable order. */
    public Map<String, String> all() {
        Map<String, String> stored = jdbi.withHandle(handle -> handle
                .createQuery("SELECT key, value FROM settings")
                .setMapKeyColumn("key")
                .setMapValueColumn("value")
                .collectInto(STRING_MAP));
        Map<String, String> effective = new LinkedHashMap<>();
        for (SettingKey setting : SettingKey.values()) {
            effective.put(setting.key(), stored.getOrDefault(setting.key(), setting.defaultValue()));
        }
        return effective;
    }

    /** One setting's effective value. */
    public String get(SettingKey setting) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT value FROM settings WHERE key = :key")
                .bind("key", setting.key())
                .mapTo(String.class)
                .findOne()
                .orElse(setting.defaultValue()));
    }

    public void put(SettingKey setting, String value) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO settings (key, value, updated_at) VALUES (:key, :value, :now)
                        ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                        """)
                .bind("key", setting.key())
                .bind("value", value)
                .bind("now", clock.instant().toString())
                .execute());
    }
}
