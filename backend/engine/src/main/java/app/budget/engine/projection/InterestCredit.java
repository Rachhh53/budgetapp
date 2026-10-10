package app.budget.engine.projection;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.time.LocalDate;

/**
 * One month's estimated interest on an account.
 *
 * @param balance           what the account held at the start of that day: what the interest was worked out on
 * @param inEnteredBalance  true when the user entered a balance for that very day. The number they
 *                          typed is taken to hold the interest already, so it is not added to the
 *                          account again; it is still on record so a bucket gets its share
 */
public record InterestCredit(LocalDate date, Money amount, Money balance, BasisPoints apy, boolean inEnteredBalance) {}
