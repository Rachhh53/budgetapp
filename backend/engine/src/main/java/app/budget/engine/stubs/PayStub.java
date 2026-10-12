package app.budget.engine.stubs;

import app.budget.engine.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * A pay stub as the app understands it (spec §4.3, Phase 8): its lines, grouped the way a
 * stub groups them, and what each one is tracked as.
 *
 * <p>Nothing here reads a file. The words of a stub and where they sit on the page come in
 * as {@link Row}s, from whatever read the PDF, and {@link StubReader} turns them into this.
 */
public final class PayStub {

    private PayStub() {}

    /** Where a line sits on the stub. */
    public enum Section {
        /** Pay before anything is taken out: regular hours, overtime, a bonus. */
        EARNINGS,
        /** Taken out before tax: a 401(k), an HSA, medical premiums. */
        PRE_TAX,
        TAX,
        /** Taken out after tax: a Roth contribution, a garnishment. */
        AFTER_TAX,
        /** What is paid to the person. */
        NET,
        /** What the employer adds on top; it is no part of gross or net pay. */
        EMPLOYER
    }

    /** What a line is tracked as in the budget. */
    public enum Tracking {
        GROSS,
        /** Money sent to an account of the person's own: a 401(k), an HSA. */
        CONTRIBUTION,
        /** A premium or a fee: gone, not saved. */
        BENEFIT,
        TAX,
        NET,
        /** The employer's match, into an account of the person's own. */
        MATCH,
        /** On the stub, but nothing the budget follows (an employer-paid benefit). */
        IGNORE
    }

    /**
     * One line of a stub.
     *
     * @param amount this paycheck's amount; what is taken out is held as a positive amount
     * @param ytd    the year to date, when the stub gives one
     * @param sure   false when the line was hard to place and wants a second look
     */
    public record Line(Section section, String label, Money amount, Money ytd, Tracking tracking, boolean sure) {}

    /**
     * What was read from a stub.
     *
     * @param payDate     the day it was paid, when the stub says so
     * @param statedGross the stub's own total of its earnings, when it prints one
     * @param statedNet   the stub's own net pay, when it prints one
     */
    public record Read(LocalDate payDate, List<Line> lines, Money statedGross, Money statedNet) {}

    /** How a line was filed on an earlier stub from the same employer, so the next one is filed the same way. */
    public record Remembered(Section section, Tracking tracking) {}

    /** One word on the page: where it starts and ends across the page, in points. */
    public record Word(double x, double endX, String text) {}

    /** The words that share a line on the page, left to right. Rows come top to bottom, page after page. */
    public record Row(int page, List<Word> words) {}

    /**
     * What a label is remembered under: its letters and digits only, in lower case, so
     * "401(k) " and "401 (K)" are the same line.
     */
    public static String key(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Whether a stub adds up (spec Phase 8: gross − deductions = net). The employer's lines
     * are no part of it.
     *
     * @param difference gross less everything taken out, less net: zero when the stub adds up
     */
    public record Totals(Money gross, Money preTax, Money taxes, Money afterTax, Money net, Money employer, Money difference) {

        public static Totals of(List<Line> lines) {
            Money gross = sum(lines, Section.EARNINGS);
            Money preTax = sum(lines, Section.PRE_TAX);
            Money taxes = sum(lines, Section.TAX);
            Money afterTax = sum(lines, Section.AFTER_TAX);
            Money net = sum(lines, Section.NET);
            return new Totals(gross, preTax, taxes, afterTax, net, sum(lines, Section.EMPLOYER),
                    gross.minus(preTax).minus(taxes).minus(afterTax).minus(net));
        }

        /** Everything taken out between gross and net. */
        public Money deductions() {
            return preTax.plus(taxes).plus(afterTax);
        }

        public boolean balanced() {
            return difference.isZero();
        }

        private static Money sum(List<Line> lines, Section section) {
            Money total = Money.ZERO;
            for (Line line : lines) {
                if (line.section() == section) {
                    total = total.plus(line.amount());
                }
            }
            return total;
        }
    }
}
