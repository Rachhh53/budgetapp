package app.budget.server.api;

import io.javalin.http.Context;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Checks on what a request sent, shared by every route. Each one either returns a clean
 * value or throws an {@link ApiException} whose message is ready to show on screen.
 */
public final class Inputs {

    /** $100 billion: far beyond any household amount, and far from where {@code long} cents overflow. */
    public static final long MAX_CENTS = 10_000_000_000_000L;

    /** Dates are only ever needed around what is being projected; anything else is a mistake. */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;
    private static final int MAX_RANGE_YEARS = 10;

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String UNREADABLE = "We couldn't read that. Please check the form and try again.";

    private Inputs() {}

    /** The JSON body as {@code type}. The parser's own message is never something to show anyone. */
    public static <T> T body(Context ctx, Class<T> type) {
        T body;
        try {
            body = ctx.bodyAsClass(type);
        } catch (Exception e) {
            throw ApiException.badRequest(UNREADABLE);
        }
        if (body == null) {
            throw ApiException.badRequest(UNREADABLE);
        }
        return body;
    }

    /** A numeric id from the path. Anything that is not a number cannot be a record, so it is "not found". */
    public static long id(Context ctx, String param) {
        try {
            return Long.parseLong(ctx.pathParam(param));
        } catch (NumberFormatException e) {
            throw ApiException.notFound();
        }
    }

    /** Required text, trimmed. {@code label} names the field in the message, e.g. "a name". */
    public static String text(String value, String label, int maxLength) {
        String text = optionalText(value, label, maxLength);
        if (text == null) {
            throw ApiException.badRequest("Please add " + label + ".");
        }
        return text;
    }

    /** Optional text, trimmed; blank becomes {@code null}. */
    public static String optionalText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > maxLength) {
            throw ApiException.badRequest("Please keep " + label + " to " + maxLength + " characters or fewer.");
        }
        return text;
    }

    public static String color(String value) {
        if (value == null || !HEX_COLOR.matcher(value).matches()) {
            throw ApiException.badRequest("Colors need to be a six-digit hex code, like #0f766e.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** A required amount in cents, zero or more. {@code label} starts the message, e.g. "The take-home amount". */
    public static long amount(Long cents, String label) {
        long value = signedAmount(cents, label);
        if (value < 0) {
            throw ApiException.badRequest(label + " can't be less than zero.");
        }
        return value;
    }

    /** A required amount in cents that may be negative (an overdrawn balance, for instance). */
    public static long signedAmount(Long cents, String label) {
        if (cents == null) {
            throw ApiException.badRequest(label + " is missing. Please enter an amount.");
        }
        if (Math.abs(cents) > MAX_CENTS) {
            throw ApiException.badRequest(label + " is larger than Budget can keep track of.");
        }
        return cents;
    }

    /**
     * An optional list of ids chosen from a list the user built (tags, say): no blanks, no
     * repeats, and no more than {@code max}. Whether each one exists is for the caller to check.
     */
    public static List<Long> ids(List<Long> values, int max, String message) {
        if (values == null) {
            return List.of();
        }
        List<Long> distinct = values.stream().distinct().toList();
        if (distinct.size() > max || distinct.stream().anyMatch(Objects::isNull)) {
            throw ApiException.badRequest(message);
        }
        return distinct;
    }

    /** One of an enum's values, sent in lower case (e.g. {@code semi_monthly}). */
    public static <E extends Enum<E>> E choice(Class<E> type, String value, String label) {
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> code(constant).equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Please choose " + label + " from the list."));
    }

    /** How an enum value is written in the API and the database: lower case. */
    public static String code(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** An optional {@code YYYY-MM-DD} query parameter. */
    public static LocalDate dateParam(Context ctx, String name, LocalDate fallback) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return date(value);
    }

    /** True for a date in the years Budget works with (2000–2100). */
    public static boolean inSupportedYears(LocalDate date) {
        return date.getYear() >= MIN_YEAR && date.getYear() <= MAX_YEAR;
    }

    /** Refuses a date range that is backwards, outside the supported years, or over ten years long. */
    public static void requireSensibleRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || !inSupportedYears(from) || !inSupportedYears(to)
                || to.getYear() - from.getYear() > MAX_RANGE_YEARS) {
            throw ApiException.badRequest("Please choose a range of dates up to ten years long, between 2000 and 2100.");
        }
    }

    public static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates need to look like 2026-10-15.");
        }
    }
}
