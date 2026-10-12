package app.budget.engine.stubs;

import app.budget.engine.Money;
import app.budget.engine.stubs.PayStub.Line;
import app.budget.engine.stubs.PayStub.Read;
import app.budget.engine.stubs.PayStub.Remembered;
import app.budget.engine.stubs.PayStub.Row;
import app.budget.engine.stubs.PayStub.Section;
import app.budget.engine.stubs.PayStub.Tracking;
import app.budget.engine.stubs.PayStub.Word;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the words of a pay stub into its lines (spec Phase 8), by where they sit on the page.
 *
 * <p>Payroll companies lay stubs out differently, but they share a shape: titled groups
 * (earnings, deductions, taxes), a label at the left of each line, and amounts in columns
 * headed "Current" and "YTD". Two groups often sit side by side. So a row is cut into
 * pieces, each a label followed by its numbers; each piece is filed under the title above
 * it; and each number is taken as this paycheck's or the year's by the column it ends under.
 *
 * <p>It is a best effort, and says so: a line it had to guess at is marked not {@code sure},
 * and nothing is used until the person has looked the result over. A line filed by hand on
 * an earlier stub from the same employer ({@link Remembered}) is filed that way again.
 */
public final class StubReader {

    private StubReader() {}

    /** How far, in points, a number may end from a column heading and still belong under it. */
    private static final double COLUMN_REACH = 60;
    /** How far left of a group's title a label may start and still belong to that group. */
    private static final double TITLE_REACH = 30;
    /**
     * A row with more words than this is a sentence, not a row of titles. Two groups side by side,
     * each with its column headings, come to sixteen ("Earnings rate hours this period year to
     * date Other Benefits and Information this period total to date").
     */
    private static final int MOST_WORDS_IN_TITLES = 20;
    /** A label with more words than this is a sentence that happens to hold an amount. */
    private static final int MOST_WORDS_IN_A_LABEL = 8;
    /** How far right of the net pay a group must start to be a separate column, untouched by it. */
    private static final double COLUMN_APART = 100;
    /** How far below "Pay date" its date may be, in rows, when the stub prints it underneath. */
    private static final int ROWS_BELOW_A_DATE_LABEL = 2;

    /**
     * 1,234.56 with an optional $, a minus sign in front, behind, or as brackets, a plus sign in
     * front, and a footnote's asterisk behind ("-150.00*": taken out before tax).
     */
    private static final Pattern MONEY = Pattern.compile("\\(?[-+]?\\$?(\\d{1,3}(?:,\\d{3})+|\\d+)\\.(\\d{2})\\)?-?\\*?");
    /**
     * Any other number with a decimal point or a percent sign: hours, a rate with four decimals. A
     * whole number is left as a word, because labels carry them ("Misc 12", "Box 14") and columns rarely do.
     */
    private static final Pattern NUMBER = Pattern.compile("-?\\$?[\\d,]+(?:\\.\\d+%?|%)");

    private enum Column { CURRENT, YTD, OTHER }

    private record ColumnHead(Column column, double endX) {}

    /**
     * A group title. {@code section} is null for plain "Deductions", which says nothing about
     * tax, and {@code skip} is for groups with no pay in them (where the net pay was deposited).
     */
    private record Title(double x, Section section, boolean skip) {}

    private record Phrase<T>(String[] words, T means) {}

    /**
     * @param ytd     null when the stub gives no year to date
     * @param guessed the two were told apart by their order alone, among other numbers (hours, a rate)
     */
    private record Figures(Money current, Money ytd, boolean guessed) {}

    /** A label and the numbers after it. */
    private record Piece(double x, String label, List<Word> amounts, int numbersSeen) {}

    private static final List<Phrase<Column>> COLUMN_HEADS = List.of(
            columnHead(Column.YTD, "year", "to", "date"),
            columnHead(Column.YTD, "year-to-date"),
            columnHead(Column.YTD, "ytd"),
            columnHead(Column.YTD, "y-t-d"),
            columnHead(Column.CURRENT, "this", "period"),
            columnHead(Column.CURRENT, "current"),
            columnHead(Column.CURRENT, "amount"),
            columnHead(Column.OTHER, "hours"),
            columnHead(Column.OTHER, "hrs"),
            columnHead(Column.OTHER, "units"),
            columnHead(Column.OTHER, "rate"));

    // Longer titles first: "pre-tax deductions" must not be read as plain "deductions".
    private static final List<Phrase<Title>> TITLES = List.of(
            title(Section.EMPLOYER, "employer", "paid", "benefits"),
            title(Section.EMPLOYER, "employer", "contributions"),
            title(Section.EMPLOYER, "company", "contributions"),
            title(Section.EMPLOYER, "employer", "benefits"),
            // Beside the pay on some statements: leave balances, taxable wages, imputed income. None of it is pay.
            skipped("other", "benefits", "and", "information"),
            skipped("other", "benefits"),
            skipped("important", "notes"),
            skipped("additional", "information"),
            skipped("net", "pay", "distribution"),
            skipped("direct", "deposit"),
            skipped("payment", "information"),
            title(Section.PRE_TAX, "pre-tax", "deductions"),
            title(Section.PRE_TAX, "pre", "tax", "deductions"),
            title(Section.PRE_TAX, "pretax", "deductions"),
            title(Section.PRE_TAX, "before-tax", "deductions"),
            title(Section.PRE_TAX, "before", "tax", "deductions"),
            title(Section.PRE_TAX, "before", "tax"),
            title(Section.PRE_TAX, "pre-tax"),
            title(Section.PRE_TAX, "pretax"),
            title(Section.AFTER_TAX, "after-tax", "deductions"),
            title(Section.AFTER_TAX, "after", "tax", "deductions"),
            title(Section.AFTER_TAX, "post-tax", "deductions"),
            title(Section.AFTER_TAX, "post", "tax", "deductions"),
            title(Section.AFTER_TAX, "after", "tax"),
            title(Section.AFTER_TAX, "after-tax"),
            title(Section.AFTER_TAX, "post-tax"),
            title(Section.AFTER_TAX, "other", "deductions"),
            title(Section.TAX, "tax", "deductions"),
            title(Section.TAX, "statutory", "deductions"),
            title(Section.TAX, "taxes", "withheld"),
            title(Section.TAX, "employee", "taxes"),
            title(Section.TAX, "withholdings"),
            title(Section.TAX, "statutory"),
            title(Section.TAX, "taxes"),
            title(Section.EARNINGS, "hours", "and", "earnings"),
            title(Section.EARNINGS, "earnings"),
            title(null, "deductions"));

    private static final Pattern GROSS_TOTAL = Pattern.compile(
            "(total )?gross( pay| earnings| wages)?|total earnings|total pay");
    private static final Pattern NET_TOTAL = Pattern.compile(
            "(total )?net( pay| check| amount)?( this period)?|take home( pay)?");
    private static final Pattern OTHER_TOTAL = Pattern.compile("(total|totals|sub ?total)\\b.*");
    /** A figure the stub reports about the pay, which is no line of it: "Your federal taxable wages this period are". */
    private static final Pattern REPORTED = word("taxable wages|taxable gross|taxable earnings|excluded from|total to date");
    /**
     * Where the net pay went, which some statements list among what is taken out, above a "Net
     * Pay" of nothing: "Checking 1", "Savings", "Direct Deposit". It is the pay, not a deduction.
     */
    private static final Pattern DEPOSIT = word("checking|chequing|savings|direct deposit|dir dep|deposit|deposited|net check");
    /** A title that is a single word alone on its row, too common a word to be looked for anywhere else. */
    private static final Pattern LONE_TITLE = Pattern.compile("other|voluntary");

    private static final Pattern EMPLOYER_WORDS = word("employer|company|match|er");
    private static final Pattern CONTRIBUTION_WORDS = word(
            "401 ?\\(?k\\)?|401|403 ?\\(?b\\)?|403|457|roth|hsa|ira|retirement|tsp|pension|espp|savings plan|deferred comp");
    private static final Pattern BENEFIT_WORDS = word(
            "medical|dental|vision|health|insurance|ins|life|ltd|std|disability|fsa|flex|commuter|transit|parking|union|dues"
                    + "|legal|garnishment|garnish|child support|charity|donation|ad&d|add");
    private static final Pattern TAX_WORDS = word(
            "tax|taxes|fica|oasdi|medicare|social security|soc sec|withholding|sdi|sui|fit|sit|federal|fed|state|local|city|county");
    private static final Pattern EARNINGS_WORDS = word(
            "regular|salary|overtime|bonus|holiday|vacation|pto|sick|commission|hourly|wages|earnings|tips|shift|retro");
    private static final Pattern AFTER_TAX_WORDS = word("roth|after tax|after-tax|post tax|post-tax|garnishment|garnish|child support");

    private static final Pattern DATE_LABEL = Pattern.compile(
            "pay date|check date|payment date|advice date|pay day|paid on|date paid", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_DATE = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4}|\\d{2})");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern WORDED_DATE = Pattern.compile(
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? (\\d{1,2}),? (\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /**
     * Reads a stub.
     *
     * @param rows       the stub's words, row by row from the top of the first page
     * @param remembered how lines were filed on earlier stubs from this employer, by {@link PayStub#key}
     */
    public static Read read(List<Row> rows, Map<String, Remembered> remembered) {
        List<Line> lines = new ArrayList<>();
        List<ColumnHead> columns = List.of();
        List<Title> titles = new ArrayList<>();
        Money statedGross = null;
        Money statedNet = null;
        Money statedGrossYtd = null;
        Money statedNetYtd = null;
        Money deposited = Money.ZERO;

        for (Row row : rows) {
            List<Word> words = row.words().stream().filter(word -> !word.text().equals("$")).toList();
            if (words.stream().noneMatch(word -> MONEY.matcher(word.text()).matches())) {
                if (words.size() <= MOST_WORDS_IN_TITLES) {
                    List<ColumnHead> heads = columnHeads(words);
                    if (heads.stream().anyMatch(head -> head.column() != Column.OTHER)) {
                        columns = heads;
                    }
                    place(titles, titles(words));
                }
                continue;
            }
            for (Piece piece : pieces(words)) {
                Figures figures = figures(piece, columns);
                String plain = plain(piece.label());
                Remembered known = remembered.get(PayStub.key(piece.label()));
                if (known == null && GROSS_TOTAL.matcher(plain).matches()) {
                    if (statedGross == null) {
                        statedGross = figures.current();
                        statedGrossYtd = figures.ytd();
                    }
                    continue;
                }
                if (known == null && NET_TOTAL.matcher(plain).matches()) {
                    if (statedNet == null) {
                        statedNet = figures.current();
                        statedNetYtd = figures.ytd();
                        // The net pay ends the pay. What is printed after it in the same column is where
                        // it went and what it is made of (the account it was paid into, "Net Check",
                        // taxable wages, the tear-off advice), until another title says otherwise.
                        double netX = piece.x();
                        titles.removeIf(title -> title.x() <= netX + COLUMN_APART);
                        titles.add(new Title(Double.NEGATIVE_INFINITY, null, true));
                    }
                    continue;
                }
                if (known == null && DEPOSIT.matcher(plain).find() && !CONTRIBUTION_WORDS.matcher(plain).find()) {
                    deposited = deposited.plus(abs(figures.current()));
                    continue;
                }
                if (known == null && (REPORTED.matcher(plain).find() || plain.split(" ").length > MOST_WORDS_IN_A_LABEL)) {
                    continue;
                }
                if (known == null && OTHER_TOTAL.matcher(plain).matches()) {
                    continue;
                }
                Title title = titleFor(titles, piece.x());
                if (title != null && title.skip() && known == null) {
                    continue;
                }
                Line line = known != null
                        ? new Line(known.section(), piece.label(), figures.current(), figures.ytd(), known.tracking(), true)
                        : filed(piece, figures, title);
                if (line == null) {
                    continue;
                }
                // What is taken out is sometimes printed with a minus sign; it is held as a plain amount either way.
                boolean takenOut = line.section() != Section.EARNINGS;
                lines.add(new Line(line.section(), line.label(),
                        takenOut ? abs(line.amount()) : line.amount(),
                        takenOut && line.ytd() != null ? abs(line.ytd()) : line.ytd(),
                        line.tracking(),
                        // Hours and a rate beside the pay, with no headings to tell them apart, are a guess.
                        line.sure() && !(figures.guessed() && line.section() == Section.EARNINGS)));
            }
        }

        // "Net Pay 0.00" under the accounts the pay went into: what was deposited is the net pay.
        if ((statedNet == null || statedNet.isZero()) && !deposited.isZero()) {
            statedNet = deposited;
            statedNetYtd = null;
        }
        // A stub that prints only its gross pay, with no earnings lines, still has a gross.
        if (statedGross != null && lines.stream().noneMatch(line -> line.section() == Section.EARNINGS)) {
            lines.add(0, new Line(Section.EARNINGS, "Gross pay", statedGross, statedGrossYtd, Tracking.GROSS, true));
        }
        if (statedNet != null && lines.stream().noneMatch(line -> line.section() == Section.NET)) {
            lines.add(new Line(Section.NET, "Net pay", abs(statedNet), statedNetYtd, Tracking.NET, true));
        }
        dropTheNetPayReadAsTakenOut(lines);
        lines.sort((a, b) -> a.section().compareTo(b.section()));
        return new Read(payDate(rows), List.copyOf(lines), statedGross, statedNet);
    }

    /**
     * Whatever a statement calls the line that shows the net pay leaving for the bank, it gives
     * itself away by its amount: it is exactly the net pay, and without it the stub adds up.
     * Such a line is the pay itself counted a second time, so it is taken out — however it was
     * filed, including by hand on an earlier stub.
     */
    private static void dropTheNetPayReadAsTakenOut(List<Line> lines) {
        Money net = Money.ZERO;
        Money gross = Money.ZERO;
        Money takenOut = Money.ZERO;
        for (Line line : lines) {
            switch (line.section()) {
                case NET -> net = net.plus(line.amount());
                case EARNINGS -> gross = gross.plus(line.amount());
                case PRE_TAX, TAX, AFTER_TAX -> takenOut = takenOut.plus(line.amount());
                case EMPLOYER -> { }
            }
        }
        if (net.isZero() || gross.isZero()) {
            return;
        }
        // Off by exactly the net pay: gross less everything taken out comes to nothing.
        if (!gross.minus(takenOut).isZero()) {
            return;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line line = lines.get(i);
            boolean takes = line.section() == Section.PRE_TAX || line.section() == Section.TAX || line.section() == Section.AFTER_TAX;
            if (takes && line.amount().equals(net)) {
                lines.remove(i);
                return;
            }
        }
    }

    /** Files a line nobody has filed before, by the title it sits under and the words in its label; null to leave it out. */
    private static Line filed(Piece piece, Figures figures, Title title) {
        String plain = plain(piece.label());
        boolean contribution = CONTRIBUTION_WORDS.matcher(plain).find();
        boolean benefit = BENEFIT_WORDS.matcher(plain).find();
        boolean tax = TAX_WORDS.matcher(plain).find() && !contribution;
        boolean employer = EMPLOYER_WORDS.matcher(plain).find();
        Section under = title == null ? null : title.section();

        if (under == Section.EMPLOYER || employer) {
            // An employer's line that names a savings plan is its match; anything else it pays is not the person's money.
            Tracking tracking = contribution || plain.contains("match") ? Tracking.MATCH : Tracking.IGNORE;
            return line(piece, figures, Section.EMPLOYER, tracking, employer || contribution || benefit);
        }
        if (under == Section.EARNINGS) {
            return line(piece, figures, Section.EARNINGS, Tracking.GROSS, true);
        }
        if (under == Section.TAX) {
            return line(piece, figures, Section.TAX, Tracking.TAX, true);
        }
        if (under == Section.PRE_TAX || under == Section.AFTER_TAX) {
            Tracking tracking = contribution ? Tracking.CONTRIBUTION : Tracking.BENEFIT;
            return line(piece, figures, under, tracking, contribution || benefit);
        }
        // Under plain "Deductions", or under no title at all, the label has to say what the line is.
        // An asterisk on the amount is the stub's own mark for "excluded from taxable wages": before tax.
        boolean marked = piece.amounts().stream().anyMatch(amount -> amount.text().endsWith("*"));
        boolean afterTax = !marked && AFTER_TAX_WORDS.matcher(plain).find();
        if (contribution) {
            return line(piece, figures, afterTax ? Section.AFTER_TAX : Section.PRE_TAX, Tracking.CONTRIBUTION, true);
        }
        if (benefit) {
            return line(piece, figures, afterTax ? Section.AFTER_TAX : Section.PRE_TAX, Tracking.BENEFIT, true);
        }
        if (tax) {
            return line(piece, figures, Section.TAX, Tracking.TAX, true);
        }
        if (title != null) {
            // Something taken out that no word explains: kept, and pointed out.
            return line(piece, figures, Section.PRE_TAX, Tracking.BENEFIT, false);
        }
        if (EARNINGS_WORDS.matcher(plain).find()) {
            return line(piece, figures, Section.EARNINGS, Tracking.GROSS, false);
        }
        // A number beside words that are no part of the pay: a check number, an address, a balance of leave.
        return null;
    }

    private static Line line(Piece piece, Figures figures, Section section, Tracking tracking, boolean sure) {
        return new Line(section, piece.label(), figures.current(), figures.ytd(), tracking, sure);
    }

    /** This paycheck's amount and the year to date, from a piece's numbers, by the column each ends under. */
    private static Figures figures(Piece piece, List<ColumnHead> columns) {
        Money current = null;
        Money ytd = null;
        boolean placed = false;
        for (Word amount : piece.amounts()) {
            ColumnHead nearest = null;
            for (ColumnHead head : columns) {
                if (nearest == null || Math.abs(head.endX() - amount.endX()) < Math.abs(nearest.endX() - amount.endX())) {
                    nearest = head;
                }
            }
            if (nearest == null || Math.abs(nearest.endX() - amount.endX()) > COLUMN_REACH) {
                continue;
            }
            placed = true;
            if (nearest.column() == Column.CURRENT) {
                current = money(amount.text());
            } else if (nearest.column() == Column.YTD) {
                ytd = money(amount.text());
            }
        }
        if (placed && (current != null || ytd != null)) {
            // Nothing this paycheck, with a year to date, is a line the stub left blank this time.
            return new Figures(current == null ? Money.ZERO : current, ytd, false);
        }
        // No headings to go by: the last number is the year to date when there are two or more.
        List<Word> amounts = piece.amounts();
        int count = amounts.size();
        if (count == 1) {
            return new Figures(money(amounts.get(0).text()), null, false);
        }
        boolean guessed = count > 2 || piece.numbersSeen() > count;
        return new Figures(money(amounts.get(count - 2).text()), money(amounts.get(count - 1).text()), guessed);
    }

    /** Cuts a row into its pieces: words, then numbers, and a new piece when words follow numbers. */
    private static List<Piece> pieces(List<Word> words) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder label = new StringBuilder();
        List<Word> amounts = new ArrayList<>();
        double x = 0;
        int numbers = 0;
        for (Word word : words) {
            boolean money = MONEY.matcher(word.text()).matches();
            boolean number = money || NUMBER.matcher(word.text()).matches();
            if (!number && numbers > 0) {
                if (!label.isEmpty() && !amounts.isEmpty()) {
                    pieces.add(new Piece(x, label.toString(), List.copyOf(amounts), numbers));
                }
                label.setLength(0);
                amounts.clear();
                numbers = 0;
            }
            if (number && !label.isEmpty()) {
                numbers++;
                if (money) {
                    amounts.add(word);
                }
            } else if (!number || label.isEmpty()) {
                // A number before any word is part of the label ("401 K"), not an amount.
                if (label.isEmpty()) {
                    x = word.x();
                } else {
                    label.append(' ');
                }
                label.append(word.text());
            }
        }
        if (!label.isEmpty() && !amounts.isEmpty()) {
            pieces.add(new Piece(x, label.toString(), List.copyOf(amounts), numbers));
        }
        return pieces;
    }

    private static List<ColumnHead> columnHeads(List<Word> words) {
        List<ColumnHead> heads = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            for (Phrase<Column> phrase : COLUMN_HEADS) {
                if (startsAt(words, i, phrase.words())) {
                    heads.add(new ColumnHead(phrase.means(), words.get(i + phrase.words().length - 1).endX()));
                    i += phrase.words().length - 1;
                    break;
                }
            }
        }
        return heads;
    }

    private static List<Title> titles(List<Word> words) {
        List<Title> found = new ArrayList<>();
        if (words.size() == 1 && LONE_TITLE.matcher(plain(words.get(0).text())).matches()) {
            // "Other", under "Statutory": what is taken out that is not tax. The label says what each line is.
            found.add(new Title(words.get(0).x(), null, false));
            return found;
        }
        for (int i = 0; i < words.size(); i++) {
            for (Phrase<Title> phrase : TITLES) {
                if (startsAt(words, i, phrase.words())) {
                    found.add(new Title(words.get(i).x(), phrase.means().section(), phrase.means().skip()));
                    i += phrase.words().length - 1;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Takes new titles into the ones in force. A single one replaces whatever was at or to the
     * right of it. A row of several (groups side by side start together) replaces what lies
     * across them, and leaves a column further right alone: "Deductions" and "Statutory" at the
     * left do not end an information column at the right of the page.
     */
    private static void place(List<Title> inForce, List<Title> found) {
        if (found.isEmpty()) {
            return;
        }
        if (found.size() > 1) {
            double from = found.stream().mapToDouble(Title::x).min().orElseThrow() - TITLE_REACH;
            double to = found.stream().mapToDouble(Title::x).max().orElseThrow() + TITLE_REACH;
            inForce.removeIf(title -> title.x() >= from && title.x() <= to);
        } else {
            double x = found.get(0).x();
            inForce.removeIf(title -> title.x() >= x - TITLE_REACH);
        }
        inForce.addAll(found);
    }

    /** The title a label at {@code x} sits under: the nearest one starting at or left of it. */
    private static Title titleFor(List<Title> inForce, double x) {
        Title best = null;
        for (Title title : inForce) {
            if (title.x() <= x + TITLE_REACH && (best == null || title.x() > best.x())) {
                best = title;
            }
        }
        if (best == null) {
            for (Title title : inForce) {
                if (best == null || title.x() < best.x()) {
                    best = title;
                }
            }
        }
        return best;
    }

    private static boolean startsAt(List<Word> words, int at, String[] phrase) {
        if (at + phrase.length > words.size()) {
            return false;
        }
        for (int i = 0; i < phrase.length; i++) {
            if (!plain(words.get(at + i).text()).equals(phrase[i])) {
                return false;
            }
        }
        return true;
    }

    /** The day the stub was paid: the date beside "Pay date" or the like, or just under it. */
    private static LocalDate payDate(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            String text = text(rows.get(i));
            Matcher label = DATE_LABEL.matcher(text);
            if (!label.find()) {
                continue;
            }
            LocalDate beside = firstDate(text.substring(label.end()));
            if (beside != null) {
                return beside;
            }
            // A stub laid out as a table prints the date under its heading: the date nearest below it.
            double labelX = xOf(rows.get(i), label.start());
            for (int below = i + 1; below <= i + ROWS_BELOW_A_DATE_LABEL && below < rows.size(); below++) {
                Word nearest = null;
                for (Word word : rows.get(below).words()) {
                    if (firstDate(word.text()) != null
                            && (nearest == null || Math.abs(word.x() - labelX) < Math.abs(nearest.x() - labelX))) {
                        nearest = word;
                    }
                }
                if (nearest != null) {
                    return firstDate(nearest.text());
                }
            }
        }
        return null;
    }

    private static LocalDate firstDate(String text) {
        try {
            Matcher iso = ISO_DATE.matcher(text);
            Matcher numeric = NUMERIC_DATE.matcher(text);
            Matcher worded = WORDED_DATE.matcher(text);
            // Whichever comes first in the text is the one beside the label.
            int isoAt = iso.find() ? iso.start() : Integer.MAX_VALUE;
            int numericAt = numeric.find() ? numeric.start() : Integer.MAX_VALUE;
            int wordedAt = worded.find() ? worded.start() : Integer.MAX_VALUE;
            if (isoAt == Integer.MAX_VALUE && numericAt == Integer.MAX_VALUE && wordedAt == Integer.MAX_VALUE) {
                return null;
            }
            if (isoAt <= numericAt && isoAt <= wordedAt) {
                return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            if (numericAt <= wordedAt) {
                // US stubs: month first. A two-digit year is this century.
                int year = Integer.parseInt(numeric.group(3));
                return LocalDate.of(year < 100 ? 2000 + year : year, Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2)));
            }
            int month = MONTHS.indexOf(worded.group(1).toLowerCase(Locale.ROOT)) + 1;
            return LocalDate.of(Integer.parseInt(worded.group(3)), month, Integer.parseInt(worded.group(2)));
        } catch (DateTimeException e) {
            // 13/45/2026: digits that look like a date and are not one.
            return null;
        }
    }

    private static String text(Row row) {
        StringBuilder text = new StringBuilder();
        for (Word word : row.words()) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(word.text());
        }
        return text.toString();
    }

    /** Where across the page the word holding character {@code at} of the row's text starts. */
    private static double xOf(Row row, int at) {
        int seen = 0;
        for (Word word : row.words()) {
            seen += word.text().length() + 1;
            if (at < seen) {
                return word.x();
            }
        }
        return 0;
    }

    private static Money money(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not an amount of money");
        }
        boolean negative = text.contains("-") || text.startsWith("(");
        // A plus sign and a footnote's asterisk say nothing about the amount itself.
        Money amount = Money.fromDecimal(new BigDecimal(matcher.group(1).replace(",", "") + "." + matcher.group(2)));
        return negative ? amount.negate() : amount;
    }

    private static Money abs(Money amount) {
        return amount.isNegative() ? amount.negate() : amount;
    }

    /** A label for matching: lower case, with only the punctuation that carries meaning ("pre-tax", "ad&d"). */
    private static String plain(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}&()\\- ]+", "").replaceAll("\\s+", " ").strip();
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])(" + alternatives + ")(?![\\p{L}\\p{N}])");
    }

    private static Phrase<Column> columnHead(Column column, String... words) {
        return new Phrase<>(words, column);
    }

    private static Phrase<Title> title(Section section, String... words) {
        return new Phrase<>(words, new Title(0, section, false));
    }

    private static Phrase<Title> skipped(String... words) {
        return new Phrase<>(words, new Title(0, null, true));
    }
}
