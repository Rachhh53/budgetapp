package app.budget.server.income;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRoutes.DeductionInput;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what is taken out of a paycheck as the user sent it, for an income source's paychecks and
 * for a one-off paycheck alike, so both are checked, and worked out, the same way.
 */
final class DeductionInputs {

    static final int NAME_MAX = 80;
    static final int MAX_DEDUCTIONS = 30;

    /** Nobody can have more than all of their pay taken out. */
    private static final int MAX_SHARE_BP = 10_000;

    private final AccountsRepository accounts;

    DeductionInputs(AccountsRepository accounts) {
        this.accounts = accounts;
    }

    /** The deductions, each checked; a share of pay is worked out from {@code grossCents} and kept in cents too. */
    List<Deduction> read(List<DeductionInput> inputs, Long grossCents) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_DEDUCTIONS || inputs.contains(null)) {
            throw ApiException.badRequest("Please keep to " + MAX_DEDUCTIONS + " paycheck deductions or fewer.");
        }
        List<Deduction> deductions = new ArrayList<>();
        for (DeductionInput input : inputs) {
            if (input.toAccountId() != null && !accounts.exists(input.toAccountId())) {
                throw ApiException.badRequest("Please choose the account each deduction goes to from the list.");
            }
            String name = Inputs.text(input.name(), "a name for each deduction", NAME_MAX);
            if (input.percentBp() == null) {
                deductions.add(new Deduction(name, Inputs.amount(input.amountCents(), "A deduction amount"), input.toAccountId(), null));
                continue;
            }
            if (input.percentBp() < 0 || input.percentBp() > MAX_SHARE_BP) {
                throw ApiException.badRequest("Please enter each deduction's share of your pay between 0% and 100%.");
            }
            if (grossCents == null) {
                throw ApiException.badRequest(
                        "A deduction given as a percent of your pay needs the gross amount of a paycheck. Please fill that in as well.");
            }
            // Kept in cents as well, so a paycheck is worked out the same way whichever way it was typed.
            long amount = Money.ofCents(grossCents).times(BasisPoints.of(input.percentBp()).toFraction()).cents();
            deductions.add(new Deduction(name, amount, input.toAccountId(), input.percentBp()));
        }
        return deductions;
    }
}
