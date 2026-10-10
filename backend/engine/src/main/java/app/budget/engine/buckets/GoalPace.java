package app.budget.engine.buckets;

import app.budget.engine.Money;

/**
 * Saving towards an amount by a date: how far short a bucket is projected to be, and what
 * closing that gap takes each time money is set aside.
 */
public final class GoalPace {

    private GoalPace() {}

    /** How much of {@code goal} is still missing when {@code projected} is what will be there. Never negative. */
    public static Money shortfall(Money goal, Money projected) {
        Money missing = goal.minus(projected);
        return missing.isNegative() ? Money.ofCents(0) : missing;
    }

    /**
     * What to set aside each time to close {@code shortfall} in {@code times} equal steps.
     * Rounded up to the cent, so the goal is reached and never missed by a cent.
     *
     * @param times how many paydays or months are left; must be at least one
     */
    public static Money each(Money shortfall, int times) {
        if (shortfall.isNegative()) {
            throw new IllegalArgumentException("A shortfall cannot be negative");
        }
        if (times <= 0) {
            throw new IllegalArgumentException("There has to be at least one time left to save on");
        }
        return Money.ofCents((shortfall.cents() + times - 1) / times);
    }
}
