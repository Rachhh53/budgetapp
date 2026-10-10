package app.budget.engine.projection;

import app.budget.engine.Money;
import java.time.LocalDate;

/**
 * Money arriving in or leaving one account on a day.
 *
 * @param amount      signed: positive raises the account's balance, negative lowers it
 * @param outstanding true for something the user pays by hand and has not marked paid. It has
 *                    not happened yet, so a balance entered after its date does not include it.
 */
public record Movement(LocalDate date, Money amount, boolean outstanding) {

    /**
     * The day it takes effect. Something still outstanding when the newest balance was entered
     * has yet to happen, so it is carried forward to that day.
     */
    public LocalDate countsOn(LocalDate newestEntered) {
        return outstanding && date.isBefore(newestEntered) ? newestEntered : date;
    }
}
