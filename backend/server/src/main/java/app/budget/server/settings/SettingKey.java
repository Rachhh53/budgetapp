package app.budget.server.settings;

import app.budget.server.api.Inputs;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every setting the app knows, with its default and what counts as a valid value.
 * Settings are picked from fixed choices in the UI, so anything else is rejected here.
 */
public enum SettingKey {
    THEME("theme", "system", Set.of("light", "dark", "system")::contains,
            "Please choose light, dark, or follow the system setting."),
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
            "Please choose 1, 5, 15, 30 or 60 minutes."),
    /** Whether amounts are blurred each time the app opens or is unlocked, until shown again. */
    START_AMOUNTS_HIDDEN("start_amounts_hidden", "false", Set.of("true", "false")::contains,
            "Please choose whether amounts start out hidden."),
    /** How many days before it is due a bill paid by hand is pointed out as due soon; 0 turns that off. */
    UNPAID_HIGHLIGHT_DAYS("unpaid_highlight_days", "3", Set.of("0", "1", "2", "3", "5", "7", "10", "14")::contains,
            "Please choose 0, 1, 2, 3, 5, 7, 10 or 14 days."),
    /**
     * Where daily backups are saved; empty means the app's own data folder. Never accepted
     * through the settings route: a folder is chosen in the folder browser, which checks that
     * it exists and can be written to, and only from this PC (see BackupRoutes).
     */
    BACKUP_FOLDER("backup_folder", "", value -> false,
            "Please choose the backup folder with Choose folder."),
    /** How many daily backups are kept; older ones are removed. */
    BACKUP_KEEP_COUNT("backup_keep_count", "30", Set.of("7", "14", "30", "60", "90")::contains,
            "Please choose 7, 14, 30, 60 or 90 backups."),
    /**
     * The day the forms for something new open on, in place of today; empty means today. For
     * entering a year from its start: every form would otherwise have to be turned back from
     * today by hand. It only fills in a form. What counts as past due, current or upcoming is
     * always worked out from the real day. The route also refuses a day after today.
     */
    DEFAULT_DATE("default_date", "", SettingKey::emptyOrSupportedDate,
            "Please choose a day from 2000 on, up to today, or go back to using today."),
    /** The day the budget year starts, as month and day ({@code 01-01}): when next year's plan takes effect. */
    BUDGET_YEAR_START("budget_year_start", "01-01", SettingKey::dayOfEveryYear,
            "Please choose the month and day your budget year starts."),
    /** The day of the year from which Budget suggests planning the next one. */
    PLAN_REMINDER("plan_reminder", "10-01", SettingKey::dayOfEveryYear,
            "Please choose the month and day to be reminded to plan next year."),
    /**
     * The network address phone access listens on; empty means phone access is off (the default).
     * Never accepted through the settings route: it is turned on and off under Phone access, which
     * checks the address is this PC's, that a PIN is set, and that the request is from this PC
     * (see PhoneAccessRoutes).
     */
    PHONE_ACCESS_ADDRESS("phone_access_address", "", value -> false,
            "Please turn phone access on or off under Phone access."),
    /**
     * Whether Budget starts, hidden in the tray, when this PC's user signs in to Windows. The desktop
     * shell writes or removes the Windows startup entry to match. Changed from this PC only.
     */
    START_WITH_WINDOWS("start_with_windows", "false", Set.of("true", "false")::contains,
            "Please choose whether Budget starts with Windows.");

    /** Settings about this PC itself, which a phone or another computer may see but not change. */
    private static final Set<SettingKey> THIS_PC_ONLY = Set.of(START_WITH_WINDOWS, PHONE_ACCESS_ADDRESS);

    private static final Pattern MONTH_DAY = Pattern.compile("\\d{2}-\\d{2}");
    private static final int NOT_A_LEAP_YEAR = 2001;

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

    private static boolean emptyOrSupportedDate(String value) {
        if (value.isEmpty()) {
            return true;
        }
        try {
            return Inputs.inSupportedYears(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** A month and day, {@code MM-DD}, that every year has: February 29 is not one. */
    private static boolean dayOfEveryYear(String value) {
        if (!MONTH_DAY.matcher(value).matches()) {
            return false;
        }
        try {
            return MonthDay.parse("--" + value).isValidYear(NOT_A_LEAP_YEAR);
        } catch (DateTimeParseException e) {
            return false;
        }
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

    /** True when only this PC itself may change it. */
    public boolean thisPcOnly() {
        return THIS_PC_ONLY.contains(this);
    }

    /** The keys of the settings only this PC may change, as stored: Undo checks a change against them. */
    public static Set<String> thisPcOnlyKeys() {
        return THIS_PC_ONLY.stream().map(SettingKey::key).collect(Collectors.toUnmodifiableSet());
    }

    public boolean accepts(String value) {
        return value != null && valid.test(value);
    }

    /** What to tell someone whose value was not accepted. */
    public String hint() {
        return hint;
    }
}
