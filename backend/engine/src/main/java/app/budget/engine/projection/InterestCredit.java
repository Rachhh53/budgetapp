package app.budget.engine.projection;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.time.LocalDate;

/**
 * One month's estimated interest on an account.
 *
 * @param balance what the account held at the start of that day: what the interest was worked out on
 */
public record InterestCredit(LocalDate date, Money amount, Money balance, BasisPoints apy) {}
