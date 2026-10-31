package app.budget.engine.statements;

import app.budget.engine.Money;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amounts and dates as banks write them, shared by the three readers. Every amount becomes whole cents through
 * {@link BigDecimal}, never a double.
 */
public final class StatementText {

    private StatementText() {}

    /** Which way round a date with slashes is written: 10/05/2026 in the US, 05/10/2026 elsewhere. */
    public enum DateOrder { MDY, DMY, YMD }

    /**
     * An amount: "$1,234.56", "-12.00", "(12.00)", "12.00 CR", "12.00-", with a typographic minus too. A sign of any of
     * those kinds makes it negative. Nothing else may be in the text but spaces and a currency sign.
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "^(?<lead>[-−+(]?)\\s*\\$?\\s*(?<lead2>[-−]?)(?<digits>\\d{1,3}(?:,\\d{3})+|\\d+)?(?:\\.(?<cents>\\d{1,2}))?\\s*"
                    + "(?<trail>\\)|-|−|cr|dr)?$",
            Pattern.CASE_INSENSITIVE);

    /** As written, so a minus, brackets or "CR" come back negative. Empty for text that is not an amount. */
    public static Optional<Money> amount(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim().replace("USD", "").trim();
        Matcher matcher = AMOUNT.matcher(trimmed);
        if (trimmed.isEmpty() || !matcher.matches() || (matcher.group("digits") == null && matcher.group("cents") == null)) {
            return Optional.empty();
        }
        String lead = matcher.group("lead");
        String trail = matcher.group("trail");
        // A bracket opened must be closed, and the other way round.
        if ("(".equals(lead) != ")".equals(trail)) {
            return Optional.empty();
        }
        String whole = matcher.group("digits") == null ? "0" : matcher.group("digits").replace(",", "");
        String cents = matcher.group("cents") == null ? "0" : matcher.group("cents");
        BigDecimal value = new BigDecimal(whole + "." + cents);
        boolean negative = lead.equals("-") || lead.equals("−") || lead.equals("(") || !matcher.group("lead2").isEmpty()
                || (trail != null && !trail.equals(")") && !trail.equalsIgnoreCase("dr"));
        Money money = Money.fromDecimal(value);
        return Optional.of(negative ? money.negate() : money);
    }

    /** True for text that has the cents a statement's amount always shows: "12.00", not a store number "1200". */
    public static boolean looksLikeMoney(String text) {
        return text != null && text.matches(".*\\d\\.\\d{2}\\s*(\\)|-|−|cr|CR|Cr|dr|DR)?\\s*$") && amount(text).isPresent();
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final Pattern NUMERIC = Pattern.compile("^(\\d{1,4})[/.-](\\d{1,2})[/.-](\\d{1,4})$");
    /** OFX's day, which may run on into a time to any precision and a time zone in brackets. */
    private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})(?=\\d{2,}|[\\[.]|$)");
    private static final Pattern MONTH_FIRST = Pattern.compile("^([a-z]{3,9})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_FIRST = Pattern.compile("^(\\d{1,2})[\\s-]+([a-z]{3,9})\\.?[\\s-]+(\\d{2,4})$", Pattern.CASE_INSENSITIVE);

    /**
     * A whole date: 2026-10-05, 10/05/2026, 10/05/26, 20261005 (OFX, which may carry a time after it), Oct 5, 2026 or
     * 05 Oct 2026. {@code order} settles which number is the month when slashes leave it open. Empty for anything else.
     */
    public static Optional<LocalDate> date(String text, DateOrder order) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        try {
            Matcher numeric = NUMERIC.matcher(trimmed);
            if (numeric.matches()) {
                String a = numeric.group(1);
                String b = numeric.group(2);
                String c = numeric.group(3);
                if (a.length() == 4) {
                    return Optional.of(LocalDate.of(Integer.parseInt(a), Integer.parseInt(b), Integer.parseInt(c)));
                }
                int year = year(c);
                int first = Integer.parseInt(a);
                int second = Integer.parseInt(b);
                // A first number over 12 can only be the day, whatever the order says.
                boolean dayFirst = order == DateOrder.DMY ? second <= 12 || first > 12 : first > 12;
                return Optional.of(dayFirst ? LocalDate.of(year, second, first) : LocalDate.of(year, first, second));
            }
            Matcher compact = COMPACT.matcher(trimmed);
            if (compact.find()) {
                return Optional.of(LocalDate.of(
                        Integer.parseInt(compact.group(1)), Integer.parseInt(compact.group(2)), Integer.parseInt(compact.group(3))));
            }
            Matcher monthFirst = MONTH_FIRST.matcher(trimmed);
            if (monthFirst.matches() && month(monthFirst.group(1)) > 0) {
                return Optional.of(LocalDate.of(
                        Integer.parseInt(monthFirst.group(3)), month(monthFirst.group(1)), Integer.parseInt(monthFirst.group(2))));
            }
            Matcher dayFirst = DAY_FIRST.matcher(trimmed);
            if (dayFirst.matches() && month(dayFirst.group(2)) > 0) {
                return Optional.of(LocalDate.of(year(dayFirst.group(3)), month(dayFirst.group(2)), Integer.parseInt(dayFirst.group(1))));
            }
        } catch (DateTimeException | NumberFormatException e) {
            // 02/30/2026 and the like: not a date.
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static final Pattern SHORT_NUMERIC = Pattern.compile("^(\\d{1,2})[/-](\\d{1,2})$");
    private static final Pattern SHORT_MONTH = Pattern.compile("^([a-z]{3,9})\\.?\\s*(\\d{1,2})$", Pattern.CASE_INSENSITIVE);

    /** A date written without its year, as card statements list their lines: "10/05" or "Oct 5". */
    public static Optional<MonthDay> monthDay(String text, DateOrder order) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        try {
            Matcher numeric = SHORT_NUMERIC.matcher(trimmed);
            if (numeric.matches()) {
                int first = Integer.parseInt(numeric.group(1));
                int second = Integer.parseInt(numeric.group(2));
                return Optional.of(order == DateOrder.DMY ? MonthDay.of(second, first) : MonthDay.of(first, second));
            }
            Matcher named = SHORT_MONTH.matcher(trimmed);
            if (named.matches() && month(named.group(1)) > 0) {
                return Optional.of(MonthDay.of(month(named.group(1)), Integer.parseInt(named.group(2))));
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** A month by its name or the first three letters of it; 0 for a word that is not a month. */
    static int month(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        Integer exact = MONTHS.get(lower.length() > 4 ? lower.substring(0, 3) : lower);
        return exact == null ? 0 : exact;
    }

    /** "26" is 2026: statements are of the last few years, never the last century. */
    private static int year(String text) {
        int year = Integer.parseInt(text);
        return text.length() <= 2 ? 2000 + year : year;
    }
}
