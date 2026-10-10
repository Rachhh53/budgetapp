package app.budget.engine.investments;

import app.budget.engine.Money;

/**
 * What an investment gained or lost between two balances the user entered (spec §5.5):
 * the change that money put in does not explain.
 *
 * <p>Only entered balances are ever passed in. A projection that turned out to be off is
 * not a loss, so projected figures have no place here.
 */
public final class GainLoss {

    private GainLoss() {}

    /**
     * @param starting         the earlier balance entered
     * @param ending           the later balance entered
     * @param netContributions everything put in between the two, less anything taken out
     * @return positive for a gain, negative for a loss
     */
    public static Money between(Money starting, Money ending, Money netContributions) {
        return ending.minus(starting).minus(netContributions);
    }
}
