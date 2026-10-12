package app.budget.engine.reports;

import app.budget.engine.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds up an income report (spec §4.11): what came in, group by group.
 *
 * <p>A paycheck is told three ways at once — what was earned, what came out of it, and what
 * reached the account — so each group carries all of them. Money that is not a paycheck (a
 * one-off, interest) has no gross and nothing taken out; it is counted in what was received.
 * An employer's match is the person's money but never reaches an account they spend from, so
 * it is kept beside the total and not in it.
 */
public final class IncomeReport {

    private IncomeReport() {}

    /** What one line of the report is. */
    public enum Kind {
        /** A paycheck: its take-home is {@link Line#amount}. */
        PAYCHECK,
        /** One-off money in. */
        ONE_OFF,
        /** Interest a savings or checking account earned. */
        INTEREST,
        /** What an employer added to a retirement account. */
        MATCH
    }

    /**
     * One line.
     *
     * @param groupKey   what it is grouped under: a month, a source's id, an account's id; null for "none"
     * @param amount     the take-home, the money in, the interest or the match
     * @param gross      a paycheck's gross pay; null when it is not known, and for anything else
     * @param taxes      a paycheck's taxes; null when not known
     * @param deductions what else came out of a paycheck; null when not known
     */
    public record Line(Kind kind, String groupKey, Money amount, Money gross, Money taxes, Money deductions) {}

    /**
     * What a set of lines adds up to.
     *
     * @param gross      gross pay of the paychecks that have one
     * @param taxes      their taxes
     * @param deductions what else came out of them
     * @param takeHome   what the paychecks brought home
     * @param oneOffs    one-off money in
     * @param interest   interest earned
     * @param match      what employers added; not part of {@link #received()}
     */
    public record Sums(Money gross, Money taxes, Money deductions, Money takeHome, Money oneOffs, Money interest, Money match) {

        static final Sums NONE = new Sums(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

        /** Everything that reached an account: take-home, one-offs and interest. */
        public Money received() {
            return takeHome.plus(oneOffs).plus(interest);
        }

        Sums with(Line line) {
            return switch (line.kind()) {
                case PAYCHECK -> new Sums(
                        gross.plus(orZero(line.gross())), taxes.plus(orZero(line.taxes())), deductions.plus(orZero(line.deductions())),
                        takeHome.plus(line.amount()), oneOffs, interest, match);
                case ONE_OFF -> new Sums(gross, taxes, deductions, takeHome, oneOffs.plus(line.amount()), interest, match);
                case INTEREST -> new Sums(gross, taxes, deductions, takeHome, oneOffs, interest.plus(line.amount()), match);
                case MATCH -> new Sums(gross, taxes, deductions, takeHome, oneOffs, interest, match.plus(line.amount()));
            };
        }

        private static Money orZero(Money amount) {
            return amount == null ? Money.ZERO : amount;
        }
    }

    /** @param count how many lines are in the group */
    public record Group(String key, int count, Sums sums) {}

    /**
     * @param paychecksWithoutGross how many paychecks had no gross amount to count: when above
     *                              zero, the gross and taxes are of fewer paychecks than the take-home
     */
    public record Result(List<Group> groups, int count, Sums total, int paychecksWithoutGross) {}

    /**
     * @param inKeyOrder true to list the groups by their key, as months, years and pay periods
     *                   are; false to put the group that brought in most first
     */
    public static Result summarize(List<Line> lines, boolean inKeyOrder) {
        Map<String, List<Line>> byKey = new LinkedHashMap<>();
        for (Line line : lines) {
            byKey.computeIfAbsent(line.groupKey(), key -> new ArrayList<>()).add(line);
        }
        List<Group> groups = new ArrayList<>();
        byKey.forEach((key, members) -> groups.add(new Group(key, members.size(), sum(members))));
        Comparator<Group> order = inKeyOrder
                ? Comparator.comparing(Group::key, Comparator.nullsLast(Comparator.naturalOrder()))
                : Comparator.comparing((Group group) -> group.sums().received().plus(group.sums().match())).reversed()
                        .thenComparing(Group::key, Comparator.nullsLast(Comparator.naturalOrder()));
        groups.sort(order);
        int withoutGross = (int) lines.stream().filter(line -> line.kind() == Kind.PAYCHECK && line.gross() == null).count();
        return new Result(List.copyOf(groups), lines.size(), sum(lines), withoutGross);
    }

    private static Sums sum(List<Line> lines) {
        Sums sums = Sums.NONE;
        for (Line line : lines) {
            sums = sums.with(line);
        }
        return sums;
    }
}
