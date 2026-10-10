package app.budget.engine.buckets;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.buckets.BucketProjector.Bucket;
import app.budget.engine.buckets.BucketProjector.BucketBalance;
import app.budget.engine.buckets.BucketProjector.InterestDestination;
import app.budget.engine.buckets.BucketProjector.InterestRule;
import app.budget.engine.buckets.BucketProjector.Move;
import app.budget.engine.buckets.BucketProjector.Result;
import app.budget.engine.projection.InterestCredit;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Buckets through the pay periods Oct 1–14, Oct 15–31 and Nov 1–14. */
class BucketProjectorTest {

    private static final long GIFTS = 1;
    private static final long EMERGENCY = 2;

    private static final List<Bucket> BUCKETS =
            List.of(new Bucket(GIFTS, date("2026-09-01")), new Bucket(EMERGENCY, date("2026-09-01")));

    private static final List<PayPeriod> PERIODS = List.of(
            period("2026-10-01", "2026-10-14"),
            period("2026-10-15", "2026-10-31"),
            period("2026-11-01", "2026-11-14"));

    @Test
    void aBucketHoldsEverythingMovedIntoItLessWhatCameOut() {
        List<Move> moves = List.of(
                move(GIFTS, "2026-09-20", 15_000),
                move(GIFTS, "2026-10-01", 5_000),
                move(GIFTS, "2026-10-15", 5_000),
                move(GIFTS, "2026-10-20", -8_000),
                move(EMERGENCY, "2026-11-01", 100_000));

        Result result = BucketProjector.project(BUCKETS, moves, List.of(), InterestRule.UNASSIGNED, PERIODS);

        assertThat(bucket(result, 0, GIFTS)).isEqualTo(new BucketBalance(GIFTS, cents(15_000), cents(20_000)));
        assertThat(bucket(result, 1, GIFTS)).isEqualTo(new BucketBalance(GIFTS, cents(20_000), cents(17_000)));
        assertThat(bucket(result, 2, GIFTS)).isEqualTo(new BucketBalance(GIFTS, cents(17_000), cents(17_000)));
        assertThat(bucket(result, 2, EMERGENCY)).isEqualTo(new BucketBalance(EMERGENCY, Money.ZERO, cents(100_000)));
        assertThat(result.periods().get(2).total()).isEqualTo(cents(117_000));
    }

    @Test
    void everyBucketIsListedEvenWhenNothingMovedIt() {
        Result result = BucketProjector.project(BUCKETS, List.of(), List.of(), InterestRule.UNASSIGNED, PERIODS);

        assertThat(result.periods()).hasSize(3);
        assertThat(result.periods().getFirst().buckets()).extracting(BucketBalance::bucketId).containsExactly(GIFTS, EMERGENCY);
        assertThat(result.periods().getFirst().total()).isEqualTo(Money.ZERO);
    }

    @Test
    void unassignedInterestStaysOutOfEveryBucket() {
        Result result = BucketProjector.project(
                BUCKETS, List.of(move(EMERGENCY, "2026-09-20", 500_000)), List.of(credit("2026-10-31", 3354, 1_000_000)),
                InterestRule.UNASSIGNED, PERIODS);

        assertThat(result.interestShares()).isEmpty();
        assertThat(bucket(result, 2, EMERGENCY).ending()).isEqualTo(cents(500_000));
    }

    @Test
    void interestForOneBucketAllGoesThere() {
        Result result = BucketProjector.project(
                BUCKETS, List.of(), List.of(credit("2026-10-31", 3354, 1_000_000)),
                new InterestRule(InterestDestination.BUCKET, EMERGENCY), PERIODS);

        assertThat(result.interestShares()).containsExactly(move(EMERGENCY, "2026-10-31", 3354));
        assertThat(bucket(result, 1, EMERGENCY).ending()).isEqualTo(cents(3354));
        assertThat(bucket(result, 1, GIFTS).ending()).isEqualTo(Money.ZERO);
    }

    @Test
    void proportionalInterestFollowsWhatEachBucketHeldAtTheStartOfTheDay() {
        List<Move> moves = List.of(
                move(EMERGENCY, "2026-09-20", 500_000),
                move(GIFTS, "2026-09-20", 250_000),
                // Arrives on the credit day itself, so it earns nothing this month.
                move(GIFTS, "2026-10-31", 250_000));

        Result result = BucketProjector.project(
                BUCKETS, moves, List.of(credit("2026-10-31", 3354, 1_000_000)),
                new InterestRule(InterestDestination.PROPORTIONAL, null), PERIODS);

        // Half the account is in Emergency and a quarter in Gifts; the last quarter's interest stays unassigned.
        assertThat(result.interestShares()).containsExactlyInAnyOrder(move(EMERGENCY, "2026-10-31", 1677), move(GIFTS, "2026-10-31", 838));
        assertThat(bucket(result, 1, GIFTS).ending()).isEqualTo(cents(500_838));
    }

    @Test
    void bucketsHoldingMoreThanTheAccountCannotEarnMoreThanItDid() {
        List<Move> moves = List.of(move(EMERGENCY, "2026-09-20", 900_000), move(GIFTS, "2026-09-20", 300_000));

        Result result = BucketProjector.project(
                BUCKETS, moves, List.of(credit("2026-10-31", 3354, 1_000_000)),
                new InterestRule(InterestDestination.PROPORTIONAL, null), PERIODS);

        long shared = result.interestShares().stream().mapToLong(share -> share.amount().cents()).sum();
        assertThat(shared).isLessThanOrEqualTo(3354);
        assertThat(result.interestShares()).contains(move(EMERGENCY, "2026-10-31", 2516));
    }

    @Test
    void interestOnlyReachesABucketOnceItIsOpen() {
        List<Bucket> openedInNovember = List.of(new Bucket(EMERGENCY, date("2026-11-01")));

        Result result = BucketProjector.project(
                openedInNovember, List.of(),
                List.of(credit("2026-10-31", 3354, 1_000_000), credit("2026-11-10", 3365, 1_003_354)),
                new InterestRule(InterestDestination.BUCKET, EMERGENCY), PERIODS);

        assertThat(result.interestShares()).containsExactly(move(EMERGENCY, "2026-11-10", 3365));
    }

    private static BucketBalance bucket(Result result, int period, long bucketId) {
        return result.periods().get(period).buckets().stream()
                .filter(balance -> balance.bucketId() == bucketId)
                .findFirst()
                .orElseThrow();
    }

    private static Move move(long bucketId, String day, long cents) {
        return new Move(bucketId, date(day), cents(cents));
    }

    private static InterestCredit credit(String day, long cents, long balanceCents) {
        return new InterestCredit(date(day), cents(cents), cents(balanceCents), BasisPoints.of(410), false);
    }

    private static PayPeriod period(String start, String end) {
        return new PayPeriod(date(start), date(end), date(start));
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }
}
