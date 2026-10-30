package app.budget.engine.statements;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Lines of a new statement that an earlier statement of the same account already has. Statements of one account add
 * together within a pay period (Rachel, 2026-10-25), so a download that overlaps the last one would count the days they
 * share twice; those lines are left out, saying which statement has them.
 *
 * <p>Two lines are the same when the bank gave both the same id, or, without ids, when the day, the amount and the
 * description all match. Each earlier line answers for one new line only: two identical coffees on one day on the new
 * statement and one on the old leave one of them counted.
 */
public final class Duplicates {

    private Duplicates() {}

    /** A line already kept from an earlier statement. */
    public record Known(long lineId, LocalDate txnDate, long cents, String description, String externalId) {}

    /** For each new line, the earlier line it repeats, or null. */
    public static List<Long> of(List<StatementRead.Line> incoming, List<Known> existing) {
        List<Known> unused = new ArrayList<>(existing);
        List<Long> found = new ArrayList<>();
        for (StatementRead.Line line : incoming) {
            Known match = null;
            for (Known known : unused) {
                if (same(line, known)) {
                    match = known;
                    break;
                }
            }
            if (match != null) {
                unused.remove(match);
            }
            found.add(match == null ? null : match.lineId());
        }
        return found;
    }

    private static boolean same(StatementRead.Line line, Known known) {
        if (line.externalId() != null && known.externalId() != null) {
            return line.externalId().equals(known.externalId());
        }
        return line.txnDate().equals(known.txnDate())
                && line.amount().cents() == known.cents()
                && Objects.equals(plain(line.description()), plain(known.description()));
    }

    private static String plain(String description) {
        return description == null ? "" : description.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
