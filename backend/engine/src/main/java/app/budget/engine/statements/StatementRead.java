package app.budget.engine.statements;

import app.budget.engine.Money;
import java.time.LocalDate;
import java.util.List;

/**
 * What was read from a card or bank statement (Rachel, 2026-10-25: "upload a statement from a cc or account and
 * aggregate charges by category and tag"). Each reader (CSV, OFX, PDF) answers with one of these; nothing in it is
 * money yet, only lines to look over.
 *
 * @param from the first day the statement covers, when it says; otherwise its first line's date. Null with no lines
 * @param to   the last day it covers, when it says; otherwise its last line's date. Null with no lines
 */
public record StatementRead(List<Line> lines, LocalDate from, LocalDate to) {

    /** What a line is, as far as a statement can tell. Payments and transfers are already planned in Budget. */
    public enum Kind {
        /** Money spent. */
        CHARGE,
        /** Money given back for something bought: it comes off what was spent. */
        REFUND,
        /** A payment toward a card. */
        PAYMENT,
        /** Money moved between accounts, or a deposit such as a paycheck. */
        TRANSFER
    }

    /**
     * One line of a statement.
     *
     * @param txnDate    the day the money was spent; the day it was posted when the statement gives only that
     * @param postedDate the day the bank recorded it; null when the statement gives only one date
     * @param amount     spent as a positive amount; money back (a refund, a payment, a deposit) as a negative one
     * @param sure       false for a line the reader had to guess at, pointed out until someone has looked at it
     * @param externalId the bank's own id for the line (OFX's FITID), used to notice it on a second statement; null when
     *                   the file has none
     */
    public record Line(
            LocalDate txnDate, LocalDate postedDate, String description, Money amount, Kind kind, boolean sure, String externalId) {}

    /** A statement's dates, from its lines when the file does not say them. */
    static StatementRead of(List<Line> lines, LocalDate from, LocalDate to) {
        LocalDate first = lines.stream().map(Line::txnDate).min(LocalDate::compareTo).orElse(null);
        LocalDate last = lines.stream().map(Line::txnDate).max(LocalDate::compareTo).orElse(null);
        LocalDate start = from == null ? first : first != null && first.isBefore(from) ? first : from;
        LocalDate end = to == null ? last : last != null && last.isAfter(to) ? last : to;
        return new StatementRead(List.copyOf(lines), start, end);
    }
}
