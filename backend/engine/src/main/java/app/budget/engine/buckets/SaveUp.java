package app.budget.engine.buckets;

import app.budget.engine.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * Saving up for a bill (spec §4.6, §5.8): the amount is shared out over the paydays before
 * it is due, so the money is there on the day.
 */
public final class SaveUp {

    private SaveUp() {}

    /**
     * What to set aside on each payday, earliest first. The shares always add up to
     * {@code target} exactly: when it does not divide evenly, the earliest paydays carry the
     * odd cents, so the saving is never a cent short at the end.
     *
     * @param paydays how many paydays there are to save on; zero gives no shares
     */
    public static List<Money> shares(Money target, int paydays) {
        if (target.isNegative()) {
            throw new IllegalArgumentException("A save-up target cannot be negative");
        }
        if (paydays <= 0) {
            return List.of();
        }
        long each = target.cents() / paydays;
        long oddCents = target.cents() % paydays;
        List<Money> shares = new ArrayList<>(paydays);
        for (int i = 0; i < paydays; i++) {
            shares.add(Money.ofCents(i < oddCents ? each + 1 : each));
        }
        return shares;
    }
}
