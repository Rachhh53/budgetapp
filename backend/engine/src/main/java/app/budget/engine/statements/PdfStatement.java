package app.budget.engine.statements;

import app.budget.engine.Money;
import app.budget.engine.statements.StatementText.DateOrder;
import app.budget.engine.stubs.PayStub.Row;
import app.budget.engine.stubs.PayStub.Word;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A statement saved as a PDF, read from the words on its pages (Rachel chose PDF as well as CSV and OFX, 2026-10-25).
 * Every bank draws its statement differently, so this looks only for what they share: a line of spending starts with
 * a date and ends with an amount. Lines it had to guess at are pointed out for a second look, as a pay stub's are.
 *
 * <p>Card statements give a line's month and day only; the year comes from the statement's own dates.
 */
public final class PdfStatement {

    private PdfStatement() {}

    /** Words that say a section lists money back, so its amounts are taken as money back unless written otherwise. */
    private static final Pattern MONEY_BACK_HEADING = Pattern.compile(
            "(?<![\\p{L}])(deposits?|credits?|payments?( and| &) (other )?credits|additions|money in)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);

    /** Words that say a section lists spending. */
    private static final Pattern SPENDING_HEADING = Pattern.compile(
            "(?<![\\p{L}])(purchases?|withdrawals?|debits?|charges?|fees|subtractions|checks paid|money out|transactions)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE);

    /** Lines that carry a date and an amount and are not spending: the statement's own figures. */
    private static final Pattern SUMMARY = Pattern.compile(
            "(previous|new|opening|closing|beginning|ending|statement|daily|average|current) balance|balance (forward|brought)"
                    + "|\\btotal\\b|minimum payment|payment due|credit limit|available credit|credit line|cash advance limit",
            Pattern.CASE_INSENSITIVE);

    /** A statement's period, written as two whole dates with a dash, "to" or "through" between them. */
    private static final Pattern PERIOD = Pattern.compile(
            "(\\d{1,2}/\\d{1,2}/\\d{2,4}|[A-Za-z]{3,9}\\.? \\d{1,2},? \\d{4})\\s*(?:-|–|to|through|thru)\\s*"
                    + "(\\d{1,2}/\\d{1,2}/\\d{2,4}|[A-Za-z]{3,9}\\.? \\d{1,2},? \\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHOLE_DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}|[A-Za-z]{3,9}\\.? \\d{1,2},? \\d{4}");

    /**
     * @param onCard true for a credit card's statement, for guessing what each line is
     * @param today  the year to use when the statement has no whole date anywhere in it
     */
    public static StatementRead read(List<Row> rows, boolean onCard, LocalDate today) {
        LocalDate[] period = period(rows);
        LocalDate end = period[1] != null ? period[1] : latestWholeDate(rows).orElse(null);
        List<StatementRead.Line> lines = new ArrayList<>();
        boolean moneyBackSection = false;
        boolean balanceColumn = false;
        for (Row row : rows) {
            if (lines.size() >= CsvStatement.MAX_LINES) {
                break;
            }
            List<String> words = row.words().stream().map(Word::text).toList();
            String text = String.join(" ", words);
            Optional<Parsed> parsed = parse(words, end, today);
            if (parsed.isEmpty()) {
                // A heading: what follows it is money back or spending until the next one says otherwise.
                if (text.length() < 80) {
                    if (MONEY_BACK_HEADING.matcher(text).find() && !SPENDING_HEADING.matcher(text).find()) {
                        moneyBackSection = true;
                    } else if (SPENDING_HEADING.matcher(text).find()) {
                        moneyBackSection = false;
                    }
                }
                balanceColumn = balanceColumn || text.toLowerCase().contains("balance") && text.toLowerCase().contains("amount");
                continue;
            }
            Parsed line = parsed.get();
            if (SUMMARY.matcher(line.description()).find() || line.description().isBlank()) {
                continue;
            }
            // Written with a minus, in brackets or with "CR", it is money back wherever it sits.
            Money spent = line.writtenNegative() ? line.amount() : moneyBackSection ? line.amount().negate() : line.amount();
            boolean sure = line.yearKnown() && (!line.twoAmounts() || balanceColumn);
            lines.add(new StatementRead.Line(
                    line.txnDate(), line.posted(), line.description(), spent, LineKinds.guess(line.description(), spent, onCard), sure, null));
        }
        return StatementRead.of(lines, period[0], period[1]);
    }

    /**
     * One row read as a line.
     *
     * @param amount         as written, money back negative only when written so
     * @param writtenNegative the amount itself says it is money back
     * @param twoAmounts     the row ended in two amounts, taken as the line's and a running balance
     */
    private record Parsed(
            LocalDate txnDate, LocalDate posted, String description, Money amount, boolean writtenNegative, boolean yearKnown,
            boolean twoAmounts) {}

    private static Optional<Parsed> parse(List<String> drawn, LocalDate end, LocalDate today) {
        List<String> words = joinedMarks(drawn);
        if (words.size() < 3) {
            return Optional.empty();
        }
        int index = 0;
        DatedWord first = dateAt(words, index, end, today);
        if (first == null) {
            return Optional.empty();
        }
        index += first.words();
        DatedWord second = dateAt(words, index, end, today);
        if (second != null) {
            index += second.words();
        }
        int last = words.size() - 1;
        if (!StatementText.looksLikeMoney(words.get(last))) {
            return Optional.empty();
        }
        boolean twoAmounts = last - 1 >= index && StatementText.looksLikeMoney(words.get(last - 1));
        int amountAt = twoAmounts ? last - 1 : last;
        Money amount = StatementText.amount(words.get(amountAt)).orElseThrow();
        if (amountAt <= index) {
            return Optional.empty();
        }
        String description = String.join(" ", words.subList(index, amountAt)).trim();
        if (description.endsWith("$")) {
            description = description.substring(0, description.length() - 1).trim();
        }
        if (description.endsWith(" -") || description.equals("-")) {
            description = description.substring(0, description.length() - 1).trim();
            amount = amount.negate();
        }
        // The posted date is the bank's; the first date on the row is the day it was spent.
        return Optional.of(new Parsed(first.date(), second == null ? null : second.date(), description, amount,
                amount.isNegative(), first.yearKnown(), twoAmounts));
    }

    /** "12.00 CR" and "$ 12.00" are often drawn as two words; they are one amount. */
    private static List<String> joinedMarks(List<String> drawn) {
        List<String> words = new ArrayList<>();
        for (String word : drawn) {
            int last = words.size() - 1;
            if (last >= 0 && word.matches("(?i)cr|dr") && StatementText.looksLikeMoney(words.get(last))) {
                words.set(last, words.get(last) + " " + word);
            } else if (last >= 0 && words.get(last).equals("$") && StatementText.amount(word).isPresent()) {
                words.set(last, "$" + word);
            } else {
                words.add(word);
            }
        }
        return words;
    }

    /** A date at a word, and how many words it took ("10/05" is one, "Oct 5" two). */
    private record DatedWord(LocalDate date, int words, boolean yearKnown) {}

    private static DatedWord dateAt(List<String> words, int index, LocalDate end, LocalDate today) {
        if (index >= words.size()) {
            return null;
        }
        String word = words.get(index);
        Optional<LocalDate> whole = StatementText.date(word, DateOrder.MDY);
        if (whole.isPresent()) {
            return new DatedWord(whole.get(), 1, true);
        }
        Optional<MonthDay> day = StatementText.monthDay(word, DateOrder.MDY);
        int used = 1;
        if (day.isEmpty() && index + 1 < words.size()) {
            day = StatementText.monthDay(word + " " + words.get(index + 1), DateOrder.MDY);
            used = 2;
        }
        if (day.isEmpty()) {
            return null;
        }
        if (end == null) {
            return new DatedWord(day.get().atYear(today.getYear()), used, false);
        }
        // A line after the statement's last day belongs to the year before: December's lines on January's statement.
        LocalDate dated = day.get().atYear(end.getYear());
        return new DatedWord(dated.isAfter(end) ? day.get().atYear(end.getYear() - 1) : dated, used, true);
    }

    private static LocalDate[] period(List<Row> rows) {
        for (Row row : rows) {
            String text = String.join(" ", row.words().stream().map(Word::text).toList());
            Matcher matcher = PERIOD.matcher(text);
            if (matcher.find()) {
                Optional<LocalDate> from = wholeDate(matcher.group(1));
                Optional<LocalDate> to = wholeDate(matcher.group(2));
                if (from.isPresent() && to.isPresent() && !to.get().isBefore(from.get())
                        && from.get().plusDays(400).isAfter(to.get())) {
                    return new LocalDate[] {from.get(), to.get()};
                }
            }
        }
        return new LocalDate[] {null, null};
    }

    private static Optional<LocalDate> latestWholeDate(List<Row> rows) {
        LocalDate latest = null;
        for (Row row : rows) {
            Matcher matcher = WHOLE_DATE.matcher(String.join(" ", row.words().stream().map(Word::text).toList()));
            while (matcher.find()) {
                Optional<LocalDate> date = wholeDate(matcher.group());
                if (date.isPresent() && (latest == null || date.get().isAfter(latest))) {
                    latest = date.get();
                }
            }
        }
        return Optional.ofNullable(latest);
    }

    private static Optional<LocalDate> wholeDate(String text) {
        return StatementText.date(text.replace(".", ""), DateOrder.MDY);
    }
}
