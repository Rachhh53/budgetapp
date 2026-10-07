package app.budget.engine.projection;

import app.budget.engine.Money;
import java.time.LocalDate;

/**
 * A balance the user entered: "the real number on this day is X", taken as the balance the
 * day starts with. Whatever is dated that day or later is still to come; everything dated
 * before it is already in that number, except what is still outstanding.
 */
public record BalanceAnchor(LocalDate date, Money balance) {}
