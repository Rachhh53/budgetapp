package app.budget.engine.items;

import app.budget.engine.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Something paid for ahead and used up a session at a time (spec §4.6; Rachel, 2026-10-19: "if I
 * pay 100 today and I attend a course that costs 25/week and I want to draw down on that each
 * week"). What was paid ahead covers the sessions in date order, whenever it was paid: $100 covers
 * the first four $25 sessions. A session only part covered is not covered.
 */
public final class PaidAhead {

    private PaidAhead() {}

    /** One session, at what it costs. Skipped sessions are left out by the caller: they use nothing. */
    public record Session(LocalDate date, Money cost) {}

    /**
     * Where the money paid ahead stands.
     *
     * @param paid           everything paid ahead
     * @param used           what the sessions up to {@code asOf} came to
     * @param left           paid less used: what is still there for later sessions; negative when
     *                       sessions went past what was paid
     * @param coversThrough  the last session the money covers in full; {@code null} when it covers none
     * @param firstUncovered the first session it does not cover; {@code null} when it covers every session given
     */
    public record Result(Money paid, Money used, Money left, LocalDate coversThrough, LocalDate firstUncovered) {}

    /**
     * @param paid     everything paid ahead for the item
     * @param sessions its sessions, in any order, as far ahead as the caller wants to know about
     * @param asOf     the day "used" and "left" are worked out for, today as a rule
     */
    public static Result drawDown(Money paid, List<Session> sessions, LocalDate asOf) {
        List<Session> inOrder = sessions.stream().sorted(Comparator.comparing(Session::date)).toList();
        Money used = Money.ZERO;
        Money cumulative = Money.ZERO;
        LocalDate coversThrough = null;
        LocalDate firstUncovered = null;
        for (Session session : inOrder) {
            if (!session.date().isAfter(asOf)) {
                used = used.plus(session.cost());
            }
            cumulative = cumulative.plus(session.cost());
            if (firstUncovered == null) {
                if (cumulative.compareTo(paid) <= 0) {
                    coversThrough = session.date();
                } else {
                    firstUncovered = session.date();
                }
            }
        }
        return new Result(paid, used, paid.minus(used), coversThrough, firstUncovered);
    }

    /**
     * A pack of sessions bought together (Rachel, 2026-10-19: "I pay 1275 for a 20 session pack. I go
     * twice per week"): each session costs the price shared by the sessions, $63.75 here.
     */
    public record Pack(Money price, int sessions) {

        public Pack {
            if (price.compareTo(Money.ZERO) <= 0 || sessions < 1) {
                throw new IllegalArgumentException("a pack has a price and at least one session");
            }
        }
    }

    /**
     * {@link #drawDown(Money, List, LocalDate)} for a pack. Counting is done in whole sessions against
     * the price, never in rounded cents, so a $1,275 pack covers exactly its 20 sessions: session
     * <i>k</i> is covered when what was paid is at least <i>k</i> twentieths of the price.
     *
     * @param sessions the days of its sessions, skipped ones left out
     */
    public static Result drawDown(Money paid, List<LocalDate> sessions, LocalDate asOf, Pack pack) {
        List<LocalDate> inOrder = sessions.stream().sorted().toList();
        int covered = covered(paid, pack, inOrder.size());
        long toDate = inOrder.stream().filter(day -> !day.isAfter(asOf)).count();
        Money used = share(pack, toDate);
        return new Result(
                paid,
                used,
                paid.minus(used),
                covered == 0 ? null : inOrder.get(covered - 1),
                covered == inOrder.size() ? null : inOrder.get(covered));
    }

    /**
     * The days the next packs are needed, given what was paid: each is due on the first session the
     * money so far does not cover, and covers the next {@code pack.sessions()} from there. As far
     * ahead as the sessions given go.
     */
    public static List<LocalDate> nextPacks(Money paid, List<LocalDate> sessions, Pack pack) {
        List<LocalDate> inOrder = sessions.stream().sorted().toList();
        List<LocalDate> due = new ArrayList<>();
        Money total = paid;
        for (int covered = covered(total, pack, inOrder.size()); covered < inOrder.size(); covered = covered(total, pack, inOrder.size())) {
            due.add(inOrder.get(covered));
            total = total.plus(pack.price());
        }
        return due;
    }

    /** How many sessions {@code paid} covers in full, at most {@code most}. */
    private static int covered(Money paid, Pack pack, int most) {
        if (paid.compareTo(Money.ZERO) <= 0) {
            return 0;
        }
        // paid × sessions ÷ price, rounded down: a session part paid for is not covered.
        long whole = BigInteger.valueOf(paid.cents())
                .multiply(BigInteger.valueOf(pack.sessions()))
                .divide(BigInteger.valueOf(pack.price().cents()))
                .min(BigInteger.valueOf(most))
                .longValueExact();
        return Math.toIntExact(whole);
    }

    /** What {@code count} sessions of a pack come to, rounded half-even to the cent. */
    private static Money share(Pack pack, long count) {
        BigDecimal cents = BigDecimal.valueOf(pack.price().cents())
                .multiply(BigDecimal.valueOf(count))
                .divide(BigDecimal.valueOf(pack.sessions()), 0, Money.ROUNDING);
        return Money.ofCents(cents.longValueExact());
    }
}
