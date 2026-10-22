package app.budget.engine.buckets;

import app.budget.engine.Money;
import app.budget.engine.projection.InterestCredit;
import app.budget.engine.schedule.PayPeriod;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows the buckets of one account through the pay periods (spec §4.2).
 *
 * <p>A bucket has no entered balance to start from: what it holds is simply everything ever
 * moved into it, less everything moved out. The caller says what moved and when (recurring
 * moves, save-ups, splits, what the user did by hand); this class adds the buckets' share of
 * the account's interest and does the sums.
 */
public final class BucketProjector {

    private BucketProjector() {}

    /**
     * A bucket to follow.
     *
     * @param openedOn the day it was added; interest only reaches it from this day on
     * @param closedOn the day it was archived as of, or {@code null}: it lets go of whatever it holds that day, takes
     *                 nothing from then on, and is not listed in a period that starts on or after it
     */
    public record Bucket(long id, LocalDate openedOn, LocalDate closedOn) {

        public Bucket(long id, LocalDate openedOn) {
            this(id, openedOn, null);
        }

        /** True when the bucket is closed on {@code day}. */
        boolean closedBy(LocalDate day) {
            return closedOn != null && !day.isBefore(closedOn);
        }
    }

    /** @param amount signed: positive goes into the bucket, negative comes out of it */
    public record Move(long bucketId, LocalDate date, Money amount) {}

    /** Where an account's interest goes. */
    public enum InterestDestination {
        /** It stays outside every bucket: it raises what is available. */
        UNASSIGNED,
        /** All of it goes into one bucket. */
        BUCKET,
        /** Each bucket earns on what it holds; the rest stays unassigned. */
        PROPORTIONAL
    }

    /**
     * @param destination where interest goes
     * @param bucketId    the bucket that receives it when {@code destination} is {@code BUCKET}
     */
    public record InterestRule(InterestDestination destination, Long bucketId) {

        public static final InterestRule UNASSIGNED = new InterestRule(InterestDestination.UNASSIGNED, null);
    }

    /**
     * One bucket across one pay period; {@code ending − starting} is what the period moved, and in the period it closes
     * in, what it let go of too.
     */
    public record BucketBalance(long bucketId, Money starting, Money ending) {}

    public record PeriodBuckets(PayPeriod period, List<BucketBalance> buckets) {

        /** Everything set aside at the end of the period: what "available" leaves out. */
        public Money total() {
            Money total = Money.ZERO;
            for (BucketBalance bucket : buckets) {
                total = total.plus(bucket.ending());
            }
            return total;
        }
    }

    /**
     * @param periods        one entry per period, each listing every bucket in the order given that is not closed by
     *                       the time the period starts
     * @param interestShares the interest that went into buckets, earliest first: moves this
     *                       class added to the ones it was given
     */
    public record Result(List<PeriodBuckets> periods, List<Move> interestShares) {}

    /**
     * @param moves    everything that moves these buckets, in any order
     * @param interest the account's interest credits, in any order
     */
    public static Result project(
            List<Bucket> buckets, List<Move> moves, List<InterestCredit> interest, InterestRule rule, List<PayPeriod> periods) {
        List<Move> ordered = new ArrayList<>(moves);
        ordered.sort(Comparator.comparing(Move::date));
        List<Move> shares = interestShares(buckets, ordered, interest, rule);
        List<Move> all = new ArrayList<>(ordered);
        all.addAll(shares);

        List<PeriodBuckets> result = new ArrayList<>();
        for (PayPeriod period : periods) {
            List<BucketBalance> balances = new ArrayList<>();
            for (Bucket bucket : buckets) {
                if (bucket.closedBy(period.start())) {
                    continue;
                }
                Money starting = Money.ZERO;
                Money ending = Money.ZERO;
                for (Move move : all) {
                    if (move.bucketId() != bucket.id() || move.date().isAfter(period.end()) || bucket.closedBy(move.date())) {
                        continue;
                    }
                    ending = ending.plus(move.amount());
                    if (move.date().isBefore(period.start())) {
                        starting = starting.plus(move.amount());
                    }
                }
                // Closed during the period: whatever it still held goes back to what is available that day.
                balances.add(new BucketBalance(bucket.id(), starting, bucket.closedBy(period.end()) ? Money.ZERO : ending));
            }
            result.add(new PeriodBuckets(period, balances));
        }
        return new Result(result, shares);
    }

    /**
     * Shares each interest credit out among the buckets. Like the interest itself, a share is
     * judged on what the bucket held at the start of the credit day, and earlier shares count
     * towards later ones.
     */
    private static List<Move> interestShares(List<Bucket> buckets, List<Move> ordered, List<InterestCredit> interest, InterestRule rule) {
        if (rule.destination() == InterestDestination.UNASSIGNED || interest.isEmpty()) {
            return List.of();
        }
        List<InterestCredit> credits = interest.stream().sorted(Comparator.comparing(InterestCredit::date)).toList();
        Map<Long, Money> held = new HashMap<>();
        List<Move> shares = new ArrayList<>();
        int next = 0;
        for (InterestCredit credit : credits) {
            while (next < ordered.size() && ordered.get(next).date().isBefore(credit.date())) {
                Move move = ordered.get(next++);
                held.merge(move.bucketId(), move.amount(), Money::plus);
            }
            List<Move> ofThisCredit = new ArrayList<>();
            for (Bucket bucket : buckets) {
                if (bucket.openedOn().isAfter(credit.date()) || bucket.closedBy(credit.date())) {
                    continue;
                }
                Money share = share(credit, bucket, held, rule);
                if (!share.isZero()) {
                    ofThisCredit.add(new Move(bucket.id(), credit.date(), share));
                }
            }
            // Counted only once every bucket's share is known, so one bucket's share never feeds another's.
            for (Move share : ofThisCredit) {
                held.merge(share.bucketId(), share.amount(), Money::plus);
            }
            shares.addAll(ofThisCredit);
        }
        return shares;
    }

    private static Money share(InterestCredit credit, Bucket bucket, Map<Long, Money> held, InterestRule rule) {
        if (rule.destination() == InterestDestination.BUCKET) {
            return rule.bucketId() != null && rule.bucketId() == bucket.id() ? credit.amount() : Money.ZERO;
        }
        Money inBucket = held.getOrDefault(bucket.id(), Money.ZERO);
        if (inBucket.isNegative() || inBucket.isZero()) {
            return Money.ZERO;
        }
        // Buckets that together hold more than the account does cannot earn more than it earned.
        Money allBuckets = Money.ZERO;
        for (Money amount : held.values()) {
            if (!amount.isNegative()) {
                allBuckets = allBuckets.plus(amount);
            }
        }
        Money whole = allBuckets.compareTo(credit.balance()) > 0 ? allBuckets : credit.balance();
        return Money.fromDecimal(credit.amount().toDecimal()
                .multiply(BigDecimal.valueOf(inBucket.cents()))
                .divide(BigDecimal.valueOf(whole.cents()), MathContext.DECIMAL128));
    }
}
