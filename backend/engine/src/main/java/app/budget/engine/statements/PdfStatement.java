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

    /** The balance a statement starts from, and the one it ends with, as its summary names them. */
    private static final Pattern PREVIOUS_BALANCE = Pattern.compile("(previous|opening|beginning|starting) balance", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEW_BALANCE = Pattern.compile("(new|closing|ending) balance", Pattern.CASE_INSENSITIVE);

    /**
     * A card's account summary: payments and credits, and each kind of thing that adds to what is owed, as Discover
     * prints them ("Payments and Credits -$500.00", "Purchases +$92.20", "Fees Charged +$0.00") and as Synchrony prints
     * them for the Amazon Store Card, a sign before each and payments apart from other credits ("(-) Payments $500.00",
     * "(-) Other Credits $12.10", "(+) Purchases/Debits $92.20"; Rachel, 2026-10-30). The first row naming each with an
     * amount after it is the summary, which comes before the lines.
     */
    private static final String SUMMARY_START = "^(?:\\(?[-+=−]\\)?\\s*)?(?:total\\s+)?";
    private static final Pattern CREDITS_TOTAL = Pattern.compile(SUMMARY_START + "payments?,? (and|&) (other )?credits", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> CREDIT_PARTS = List.of(
            Pattern.compile(SUMMARY_START + "payments?\\b(?! (and|&|due))", Pattern.CASE_INSENSITIVE),
            Pattern.compile(SUMMARY_START + "(other )?credits\\b", Pattern.CASE_INSENSITIVE));
    private static final List<Pattern> CHARGE_TOTALS = List.of(
            Pattern.compile(SUMMARY_START + "purchases?\\b", Pattern.CASE_INSENSITIVE),
            // What some summaries call the purchases (Rachel, 2026-10-30: "total charges says 0. it's transactions on the
            // statement probably? plus the other things you named").
            Pattern.compile(SUMMARY_START + "(new )?transactions\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(SUMMARY_START + "balance transfers?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(SUMMARY_START + "cash advances?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(SUMMARY_START + "fees charged\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(SUMMARY_START + "interest charged\\b", Pattern.CASE_INSENSITIVE));

    /**
     * The order code on the row under an Amazon Store Card charge ("BrrRTxVYnMRq", "BCaaypvfqjgg"): known by where it is,
     * one word of eight or more letters and digits on a row of its own, not by how it is scrambled (Rachel, 2026-10-30:
     * "why not just do it on line breaks or whatever").
     */
    private static final Pattern ORDER_CODE = Pattern.compile("[A-Za-z0-9]{8,}");

    /** A charge on Synchrony's Amazon Store Card, whose next rows are its order code and what was bought. */
    private static final Pattern AMAZON = Pattern.compile("\\bAMAZON\\b", Pattern.CASE_INSENSITIVE);

    /** Words a statement's headings and summary lines use, so they are never read as a charge's code or description. */
    private static boolean heading(String text) {
        return MONEY_BACK_HEADING.matcher(text).find() || SPENDING_HEADING.matcher(text).find() || SUMMARY.matcher(text).find();
    }

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
        // How far into a charge written over three rows: 0 on the row right after a line, 1 after its order code.
        int afterLine = -1;
        for (Row row : rows) {
            if (lines.size() >= CsvStatement.MAX_LINES) {
                break;
            }
            List<String> words = row.words().stream().map(Word::text).toList();
            String text = String.join(" ", words);
            Optional<Parsed> parsed = parse(words, end, today);
            if (parsed.isEmpty()) {
                // Synchrony's Amazon Store Card writes each charge on three rows: the date, a reference, "AMAZON RETAIL"
                // and the amount; then a scrambled order code; then what was bought, which is what says what the charge
                // was (Rachel, 2026-10-30). Taken by the rows' places under an Amazon charge, never a heading's words.
                if (afterLine == 0 && AMAZON.matcher(lines.getLast().description()).find() && words.size() == 1
                        && ORDER_CODE.matcher(text).matches() && !heading(text)) {
                    afterLine = 1;
                    continue;
                }
                if (afterLine == 1 && !text.isBlank() && words.stream().noneMatch(StatementText::looksLikeMoney) && !heading(text)) {
                    StatementRead.Line first = lines.removeLast();
                    lines.add(new StatementRead.Line(
                            first.txnDate(), first.postedDate(), text, first.amount(), first.kind(), first.sure(), first.externalId()));
                    afterLine = -1;
                    continue;
                }
                afterLine = -1;
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
            afterLine = -1;
            if (SUMMARY.matcher(line.description()).find() || line.description().isBlank()) {
                continue;
            }
            // Written with a minus, in brackets or with "CR", it is money back wherever it sits.
            Money spent = line.writtenNegative() ? line.amount() : moneyBackSection ? line.amount().negate() : line.amount();
            boolean sure = line.yearKnown() && (!line.twoAmounts() || balanceColumn);
            lines.add(new StatementRead.Line(
                    line.txnDate(), line.posted(), line.description(), spent, LineKinds.guess(line.description(), spent, onCard), sure, null));
            afterLine = 0;
        }
        StatementRead read = StatementRead.of(lines, period[0], period[1]).withBalances(balance(rows, PREVIOUS_BALANCE), balance(rows, NEW_BALANCE));
        if (!onCard) {
            return read;
        }
        // One line for payments and credits together when the summary has it, otherwise its parts added up.
        Money credits = positive(balance(rows, CREDITS_TOTAL));
        return read.withTotals(summed(rows, CHARGE_TOTALS), credits != null ? credits : summed(rows, CREDIT_PARTS));
    }

    /**
     * The summary lines named, added up; null when it names none of them. Each is read as a positive amount, however
     * its sign is printed.
     */
    private static Money summed(List<Row> rows, List<Pattern> lines) {
        Money total = null;
        for (Pattern named : lines) {
            Money each = positive(balance(rows, named));
            if (each != null) {
                total = total == null ? each : total.plus(each);
            }
        }
        return total;
    }

    private static Money positive(Money amount) {
        return amount == null || !amount.isNegative() ? amount : amount.negate();
    }

    /**
     * The first amount after the first place the statement names this balance ("New Balance $1,234.56"), as written: on a
     * card what is owed, on a bank account what it holds. Null when it never says.
     */
    private static Money balance(List<Row> rows, Pattern named) {
        for (Row row : rows) {
            List<String> words = joinedMarks(row.words().stream().map(Word::text).toList());
            Matcher matcher = named.matcher(String.join(" ", words));
            if (!matcher.find()) {
                continue;
            }
            // Count the words up to the end of the name, then take the first amount after it.
            int seen = 0;
            for (String word : words) {
                seen += word.length() + 1;
                if (seen > matcher.end() && StatementText.looksLikeMoney(word)) {
                    Optional<Money> amount = StatementText.amount(word);
                    if (amount.isPresent()) {
                        return amount.get();
                    }
                }
            }
        }
        return null;
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
