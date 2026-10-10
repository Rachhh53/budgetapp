package app.budget.engine.projection;

import java.util.List;

/**
 * One account projected through the pay periods.
 *
 * @param balances one entry per period from the first one a balance is known for
 * @param interest every estimated interest credit since the first balance entered, earliest
 *                 first; already counted in {@code balances}
 */
public record Projection(List<PeriodBalance> balances, List<InterestCredit> interest) {

    public static final Projection NONE = new Projection(List.of(), List.of());
}
