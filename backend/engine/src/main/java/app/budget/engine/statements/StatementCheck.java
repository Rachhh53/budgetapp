package app.budget.engine.statements;

import java.util.List;

/**
 * Whether the rows read from a statement add up to its balances (Rachel, 2026-10-30: "make sure the rows you show me for
 * the statement add up to the statement balance"). Every row counts, payments and repeats of an earlier statement among
 * them: a row the reader missed or misread shows as a difference.
 *
 * <p>On a card the balances are what is owed, so spending adds to it; on a bank account they are what it holds, so
 * spending takes from it. Amounts are whole cents, spent positive and money back negative, as statement lines keep them.
 */
public final class StatementCheck {

    private StatementCheck() {}

    /**
     * @param spentCents        every row's spending, added up
     * @param backCents         every row's money back (refunds, payments, deposits), added up, as a positive amount
     * @param expectedNewCents  the previous balance moved by the rows; null without a previous balance
     * @param differenceCents   the new balance less the expected one; null without both balances. Zero when it adds up
     */
    public record Result(long spentCents, long backCents, Long expectedNewCents, Long differenceCents) {}

    public static Result of(boolean onCard, Long previousCents, Long newCents, List<Long> amountsCents) {
        long spent = amountsCents.stream().filter(cents -> cents > 0).mapToLong(Long::longValue).sum();
        long back = -amountsCents.stream().filter(cents -> cents < 0).mapToLong(Long::longValue).sum();
        long net = spent - back;
        Long expected = previousCents == null ? null : onCard ? previousCents + net : previousCents - net;
        Long difference = expected == null || newCents == null ? null : newCents - expected;
        return new Result(spent, back, expected, difference);
    }
}
