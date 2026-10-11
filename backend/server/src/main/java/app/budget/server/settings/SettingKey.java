package app.budget.server.settings;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Every setting the app knows, with its default and what counts as a valid value.
 * Settings are picked from fixed choices in the UI, so anything else is rejected here.
 */
public enum SettingKey {
    THEME("theme", "system", Set.of("light", "dark", "system")::contains,
            "Please choose light, dark, or follow Windows."),
    ACCENT_COLOR("accent_color", "#0f766e", Pattern.compile("#[0-9a-fA-F]{6}").asMatchPredicate(),
            "Colors need to be a six-digit hex code, like #0f766e."),
    /** How far ahead pay periods and balances are projected. */
    PROJECTION_HORIZON_MONTHS("projection_horizon_months", "12", Set.of("6", "12", "18", "24", "36")::contains,
            "Please choose 6, 12, 18, 24 or 36 months."),
    /** How many days before a card's promotional rate ends the warnings start. */
    PROMO_WARNING_DAYS("promo_warning_days", "60", Set.of("30", "45", "60", "90", "120")::contains,
            "Please choose 30, 45, 60, 90 or 120 days."),
    /** Minutes without activity before the app locks itself. */
    AUTO_LOCK_MINUTES("auto_lock_minutes", "15", Set.of("1", "5", "15", "30", "60")::contains,
            "Please choose 1, 5, 15, 30 or 60 minutes.");

    private final String key;
    private final String defaultValue;
    private final Predicate<String> valid;
    private final String hint;

    SettingKey(String key, String defaultValue, Predicate<String> valid, String hint) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.valid = valid;
        this.hint = hint;
    }

    public static Optional<SettingKey> find(String key) {
        return Arrays.stream(values()).filter(setting -> setting.key.equals(key)).findFirst();
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public boolean accepts(String value) {
        return value != null && valid.test(value);
    }

    /** What to tell someone whose value was not accepted. */
    public String hint() {
        return hint;
    }
}
