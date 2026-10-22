package app.budget.engine.items;

import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Decides which pay period an occurrence lands in (spec §5.2).
 *
 * <p>For an occurrence due on D, with P the period containing D: it lands in P, or in the period before P when the
 * item is placed one period early. A period's first day is part of it like any other day, so a bill due on payday is
 * paid from that payday's paycheck (Rachel, 2026-10-21: "if something happens on the first day of the pay period, no
 * matter what it is, it shows in the pay period"). It once moved back a period when paid by hand.
 */
public final class PeriodAssigner {

    private final List<PayPeriod> periods;

    /** @param periods consecutive pay periods, earliest first */
    public PeriodAssigner(List<PayPeriod> periods) {
        this.periods = List.copyOf(periods);
    }

    /**
     * The period that pays for an occurrence due on {@code due}; empty when that period is
     * outside the ones this assigner was given.
     */
    public Optional<PayPeriod> assign(LocalDate due, Placement placement) {
        int index = indexContaining(due);
        if (index < 0) {
            return Optional.empty();
        }
        if (placement != Placement.ONE_EARLY) {
            return Optional.of(periods.get(index));
        }
        return index == 0 ? Optional.empty() : Optional.of(periods.get(index - 1));
    }

    /** The period {@code date} falls in. */
    public Optional<PayPeriod> containing(LocalDate date) {
        int index = indexContaining(date);
        return index < 0 ? Optional.empty() : Optional.of(periods.get(index));
    }

    /** The period that opens on {@code start}, for an occurrence the user moved by hand. */
    public Optional<PayPeriod> startingOn(LocalDate start) {
        return containing(start).filter(period -> period.start().equals(start));
    }

    /** Binary search: periods are in order and do not overlap. */
    private int indexContaining(LocalDate date) {
        int low = 0;
        int high = periods.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            PayPeriod period = periods.get(middle);
            if (date.isBefore(period.start())) {
                high = middle - 1;
            } else if (date.isAfter(period.end())) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return -1;
    }
}
