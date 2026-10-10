package app.budget.engine.assets;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.SavingsInterest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a home, vehicle or other asset is worth on a day (spec §5.7): the latest value the
 * user entered, grown month by month since then when they gave a yearly rate.
 *
 * <p>The rate is the user's own assumption and only ever fills the gap after the newest
 * value: entering a new value replaces the estimate from that day on.
 */
public final class AssetValue {

    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private AssetValue() {}

    /** A value the user entered for the asset. */
    public record Entered(LocalDate date, Money value) {}

    /**
     * @param entered      every value entered, in any order
     * @param appreciation the yearly rate it is assumed to grow by; {@code null} to hold its value
     * @return empty when no value was entered on or before {@code date}
     */
    public static Optional<Money> on(LocalDate date, List<Entered> entered, BasisPoints appreciation) {
        Optional<Entered> latest = entered.stream()
                .filter(value -> !value.date().isAfter(date))
                .max(Comparator.comparing(Entered::date));
        if (latest.isEmpty() || appreciation == null || appreciation.value() == 0) {
            return latest.map(Entered::value);
        }
        // Whole months only, so a value does not creep up day by day between the months it is looked at.
        int months = Math.toIntExact(ChronoUnit.MONTHS.between(latest.get().date(), date));
        BigDecimal growth = BigDecimal.ONE.add(SavingsInterest.monthlyRate(appreciation)).pow(months, PRECISION);
        return Optional.of(latest.get().value().times(growth));
    }

    /** What is left of the asset once the loan against it is paid: its value less what is still owed. */
    public static Money equity(Money value, Money owed) {
        return value.minus(owed);
    }
}
