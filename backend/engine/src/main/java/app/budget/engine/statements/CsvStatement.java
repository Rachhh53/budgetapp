package app.budget.engine.statements;

import app.budget.engine.Money;
import app.budget.engine.statements.StatementText.DateOrder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A statement downloaded as a spreadsheet (CSV). Every bank lays its columns out differently, so which column is the
 * date, the description and the amount is asked once per account and remembered by the header row (decided with Rachel,
 * 2026-10-25); {@link #guess} fills the question in.
 */
public final class CsvStatement {

    private CsvStatement() {}

    /** More lines than a year of a busy card; a file that reads as more is not a statement. */
    public static final int MAX_LINES = 3000;

    /**
     * Which columns hold what, counted from 0.
     *
     * @param hasHeader       the first row names the columns rather than being a line
     * @param postedColumn    the day it was posted, when there is a second date; null otherwise
     * @param amountColumn    one column of signed amounts; null when spending and money back have a column each
     * @param debitColumn     money spent, with {@code creditColumn} money back; both null with {@code amountColumn}
     * @param chargesNegative in the one amount column, money spent is written negative (most bank accounts and some
     *                        cards) rather than positive
     */
    public record Layout(
            boolean hasHeader,
            int dateColumn,
            Integer postedColumn,
            int descriptionColumn,
            Integer amountColumn,
            Integer debitColumn,
            Integer creditColumn,
            DateOrder dateOrder,
            boolean chargesNegative) {

        /** The columns named are within the row, and the amount is one column or a debit and a credit column. */
        public boolean fits(int columns) {
            boolean oneAmount = amountColumn != null && debitColumn == null && creditColumn == null;
            boolean twoAmounts = amountColumn == null && debitColumn != null && creditColumn != null;
            return (oneAmount || twoAmounts)
                    && within(dateColumn, columns) && within(descriptionColumn, columns)
                    && (postedColumn == null || within(postedColumn, columns))
                    && (amountColumn == null || within(amountColumn, columns))
                    && (debitColumn == null || within(debitColumn, columns))
                    && (creditColumn == null || within(creditColumn, columns));
        }

        private static boolean within(int column, int columns) {
            return column >= 0 && column < columns;
        }
    }

    /** A line that could not be read, by its row number counted from 1, so the review page can say which. */
    public record Skipped(int row, String why) {}

    /** What a file says, read with a layout. */
    public record Read(StatementRead statement, List<Skipped> skipped) {}

    /**
     * Splits the text into rows of fields: quoted fields may hold the separator, a doubled quote and line breaks. The
     * separator is a comma, a semicolon or a tab, whichever the first line has most of outside quotes.
     */
    public static List<List<String>> rows(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        char separator = separator(body);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString().trim());
                field.setLength(0);
                addUnlessBlank(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        row.add(field.toString().trim());
        addUnlessBlank(rows, row);
        return rows;
    }

    private static void addUnlessBlank(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(field -> !field.isEmpty())) {
            rows.add(row);
        }
    }

    private static char separator(String body) {
        int end = body.indexOf('\n');
        String first = end < 0 ? body : body.substring(0, end);
        char best = ',';
        int most = -1;
        for (char candidate : new char[] {',', ';', '\t'}) {
            int count = 0;
            boolean quoted = false;
            for (char c : first.toCharArray()) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == candidate && !quoted) {
                    count++;
                }
            }
            if (count > most) {
                most = count;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * What a header row is remembered by: its column names in lower case, so the next download from the same bank
     * finds its layout. Empty for a file with no header row.
     */
    public static String signature(List<String> header) {
        return String.join("|", header.stream().map(name -> name.trim().toLowerCase(Locale.ROOT)).toList());
    }

    private static final Pattern DATE_NAME = Pattern.compile("date|posted|trans", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSTED_NAME = Pattern.compile("post", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION_NAME = Pattern.compile("desc|payee|merchant|name|memo|details|narrative", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_NAME = Pattern.compile("amount|amt|value", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIT_NAME = Pattern.compile("debit|withdraw|charge|spent|money out|paid out", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT_NAME = Pattern.compile("credit|deposit|payment|money in|paid in", Pattern.CASE_INSENSITIVE);

    /**
     * A first guess at the layout, for the person to confirm: columns named like a date, a description and an amount,
     * or else the first column that reads as dates, the longest text and the first that reads as money. Money spent
     * is taken to be written the way most of the amounts are, since most lines of a statement are spending.
     */
    public static Layout guess(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return new Layout(false, 0, null, 1, 2, null, null, DateOrder.MDY, false);
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> first = rows.get(0);
        boolean hasHeader = first.stream().noneMatch(field -> StatementText.amount(field).isPresent() && looksLikeAmountField(field))
                && first.stream().allMatch(field -> date(field, DateOrder.MDY).isEmpty());
        List<List<String>> body = hasHeader ? rows.subList(1, rows.size()) : rows;
        DateOrder order = dateOrder(body);

        Integer date = null;
        Integer posted = null;
        Integer description = null;
        Integer amount = null;
        Integer debit = null;
        Integer credit = null;
        if (hasHeader) {
            for (int i = 0; i < first.size(); i++) {
                String name = first.get(i);
                if (DATE_NAME.matcher(name).find() && mostly(body, i, field -> date(field, order).isPresent())) {
                    if (POSTED_NAME.matcher(name).find()) {
                        posted = posted == null ? i : posted;
                    } else {
                        date = date == null ? i : date;
                    }
                } else if (AMOUNT_NAME.matcher(name).find() && amount == null) {
                    amount = i;
                } else if (DEBIT_NAME.matcher(name).find() && debit == null) {
                    debit = i;
                } else if (CREDIT_NAME.matcher(name).find() && credit == null) {
                    credit = i;
                } else if (DESCRIPTION_NAME.matcher(name).find() && description == null) {
                    description = i;
                }
            }
        }
        // A posted date alone is the only date there is.
        if (date == null && posted != null) {
            date = posted;
            posted = null;
        }
        if (date == null) {
            date = firstColumn(body, columns, field -> date(field, order).isPresent(), -1);
        }
        if (amount == null && (debit == null || credit == null)) {
            debit = null;
            credit = null;
            amount = firstColumn(body, columns, CsvStatement::looksLikeAmountField, date == null ? -1 : date);
        }
        if (description == null) {
            description = longestText(body, columns, date, amount);
        }
        boolean chargesNegative = amount != null && chargesNegative(body, amount, description);
        return new Layout(
                hasHeader,
                date == null ? 0 : date,
                posted,
                description == null ? Math.min(1, Math.max(columns - 1, 0)) : description,
                amount,
                amount == null ? debit : null,
                amount == null ? credit : null,
                order,
                chargesNegative);
    }

    /**
     * The file's lines, read with {@code layout}. Money spent comes out positive. A row whose date or amount cannot be
     * read is skipped and listed, except a blank one or a footer of totals, which say nothing about spending.
     *
     * @param onCard true for a credit card's statement, for guessing what each line is
     */
    public static Read read(List<List<String>> rows, Layout layout, boolean onCard) {
        List<StatementRead.Line> lines = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (int i = layout.hasHeader() ? 1 : 0; i < rows.size() && lines.size() < MAX_LINES; i++) {
            List<String> row = rows.get(i);
            Optional<LocalDate> date = date(field(row, layout.dateColumn()), layout.dateOrder());
            Optional<Money> spent = spent(row, layout);
            String description = field(row, layout.descriptionColumn());
            if (date.isEmpty() || spent.isEmpty()) {
                // A footer such as "Total,,123.45" or a balance line has no date; it is not a line that went missing.
                if (date.isPresent() || spent.isPresent() && !description.isBlank() && !description.toLowerCase(Locale.ROOT).contains("total")) {
                    skipped.add(new Skipped(i + 1, date.isEmpty() ? "date" : "amount"));
                }
                continue;
            }
            LocalDate posted = layout.postedColumn() == null
                    ? null
                    : date(field(row, layout.postedColumn()), layout.dateOrder()).orElse(null);
            String words = description.isBlank() ? "(no description)" : description;
            lines.add(new StatementRead.Line(
                    date.get(), posted, words, spent.get(), LineKinds.guess(words, spent.get(), onCard), true, null));
        }
        return new Read(StatementRead.of(lines, null, null), skipped);
    }

    /** Money spent as positive, from one signed column or from a debit and a credit column. */
    private static Optional<Money> spent(List<String> row, Layout layout) {
        if (layout.amountColumn() != null) {
            return StatementText.amount(field(row, layout.amountColumn()))
                    .map(amount -> layout.chargesNegative() ? amount.negate() : amount);
        }
        String debitText = field(row, layout.debitColumn());
        String creditText = field(row, layout.creditColumn());
        Optional<Money> debit = debitText.isBlank() ? Optional.empty() : StatementText.amount(debitText);
        Optional<Money> credit = creditText.isBlank() ? Optional.empty() : StatementText.amount(creditText);
        if (debit.isEmpty() && credit.isEmpty()) {
            return Optional.empty();
        }
        // Some banks write a debit column's amounts negative already; what is in it is spent either way.
        Money out = debit.map(amount -> amount.isNegative() ? amount.negate() : amount).orElse(Money.ZERO);
        Money in = credit.map(amount -> amount.isNegative() ? amount.negate() : amount).orElse(Money.ZERO);
        return Optional.of(out.minus(in));
    }

    private static String field(List<String> row, Integer column) {
        return column == null || column >= row.size() ? "" : row.get(column);
    }

    private static Optional<LocalDate> date(String field, DateOrder order) {
        // A spreadsheet may add a time after the day.
        String day = field.trim();
        int space = day.indexOf(' ');
        if (space > 0 && day.substring(space).trim().matches("\\d{1,2}:\\d{2}.*")) {
            day = day.substring(0, space);
        }
        return StatementText.date(day, order);
    }

    private static boolean looksLikeAmountField(String field) {
        return StatementText.amount(field).isPresent() && field.matches(".*\\d.*") && !field.matches("\\d{4,}");
    }

    /** The order that reads every date in the file; month first when both do. */
    private static DateOrder dateOrder(List<List<String>> body) {
        for (List<String> row : body) {
            for (String field : row) {
                var parts = field.trim().split("[/.-]");
                if (parts.length == 3 && parts[0].matches("\\d{1,2}") && parts[1].matches("\\d{1,2}")) {
                    if (Integer.parseInt(parts[0]) > 12) {
                        return DateOrder.DMY;
                    }
                    if (Integer.parseInt(parts[1]) > 12) {
                        return DateOrder.MDY;
                    }
                }
            }
        }
        return DateOrder.MDY;
    }

    private interface FieldTest {
        boolean test(String field);
    }

    private static boolean mostly(List<List<String>> body, int column, FieldTest test) {
        long filled = body.stream().filter(row -> !field(row, column).isBlank()).count();
        long passing = body.stream().filter(row -> test.test(field(row, column))).count();
        return filled > 0 && passing * 2 >= filled;
    }

    private static Integer firstColumn(List<List<String>> body, int columns, FieldTest test, int skip) {
        for (int i = 0; i < columns; i++) {
            if (i != skip && mostly(body, i, test)) {
                return i;
            }
        }
        return null;
    }

    private static Integer longestText(List<List<String>> body, int columns, Integer date, Integer amount) {
        Integer best = null;
        double longest = -1;
        for (int i = 0; i < columns; i++) {
            if ((date != null && i == date) || (amount != null && i == amount)) {
                continue;
            }
            final int column = i;
            double average = body.stream().mapToInt(row -> field(row, column).replaceAll("[\\d.,$-]", "").length()).average().orElse(0);
            if (average > longest) {
                longest = average;
                best = i;
            }
        }
        return best;
    }

    /**
     * Whether money spent is written negative. Each line votes: one whose words say money came back ("PAYMENT THANK
     * YOU", "REFUND") votes that spending is written the other way from it, every other line that spending is written
     * as it is. A statement is mostly spending, so even a short one comes out right.
     */
    private static boolean chargesNegative(List<List<String>> body, int column, Integer descriptionColumn) {
        long negative = 0;
        long positive = 0;
        for (List<String> row : body) {
            Optional<Money> amount = StatementText.amount(field(row, column));
            if (amount.isEmpty() || amount.get().isZero()) {
                continue;
            }
            boolean moneyBack = LineKinds.soundsLikeMoneyBack(field(row, descriptionColumn));
            if (amount.get().isNegative() != moneyBack) {
                negative++;
            } else {
                positive++;
            }
        }
        return negative > positive;
    }
}
