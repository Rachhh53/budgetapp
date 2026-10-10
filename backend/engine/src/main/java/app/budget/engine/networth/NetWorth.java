package app.budget.engine.networth;

import app.budget.engine.Money;

/**
 * Net worth (spec §5.7): cash + savings + investments + assets − card balances − loan balances.
 *
 * @param cash        checking and savings together
 * @param investments investment accounts
 * @param property    homes, vehicles and other assets
 * @param cards       what is owed on credit cards, as a positive amount
 * @param loans       what is owed on loans, as a positive amount
 */
public record NetWorth(Money cash, Money investments, Money property, Money cards, Money loans) {

    /** Everything owned. */
    public Money assets() {
        return cash.plus(investments).plus(property);
    }

    /** Everything owed. */
    public Money liabilities() {
        return cards.plus(loans);
    }

    public Money total() {
        return assets().minus(liabilities());
    }
}
