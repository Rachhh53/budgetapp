package app.budget.server.income;

import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.MatchMode;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRoutes.DeductionInput;
import app.budget.server.income.IncomeRoutes.MatchInput;
import java.util.List;

/**
 * Reads the amounts of a paycheck as the user sent them: take-home, gross, deductions and the
 * employer's match. Shared by an income source and by a paycheck planned for next year, so both
 * are checked, and worked out, the same way.
 */
public final class PayInputs {

    /** 1,000%: beyond any real match, and a typo still fits so it can be shown and fixed. */
    private static final int MAX_MATCH_BP = 100_000;

    /** Nobody can be matched on more than all of their pay. */
    private static final int MAX_CAP_BP = 10_000;

    private final AccountsRepository accounts;
    private final DeductionInputs deductions;

    public PayInputs(AccountsRepository accounts) {
        this.accounts = accounts;
        this.deductions = new DeductionInputs(accounts);
    }

    /** The paycheck, each part checked; a deduction given as a share of pay is worked out from the gross amount. */
    public Pay read(Long grossCents, Long netCents, Boolean netIsEstimate, List<DeductionInput> deductionInputs, MatchInput match) {
        Long gross = grossCents == null ? null : Inputs.amount(grossCents, "The gross amount");
        long net = Inputs.amount(netCents, "The take-home amount");
        return new Pay(gross, net, Boolean.TRUE.equals(netIsEstimate), deductions.read(deductionInputs, gross), match(match, gross));
    }

    private Match match(MatchInput input, Long grossCents) {
        if (input == null) {
            return null;
        }
        Account account = input.toAccountId() == null ? null : accounts.find(input.toAccountId()).orElse(null);
        // A match is retirement money: it goes into an investment account, never into spending money.
        if (account == null || !Inputs.code(AccountType.INVESTMENT).equals(account.type())) {
            throw ApiException.badRequest("Please choose the investment account the employer match is paid into.");
        }
        MatchMode mode = Inputs.choice(MatchMode.class, input.mode(), "how the match is worked out");
        if (mode == MatchMode.FIXED) {
            return new Match(account.id(), mode, Inputs.amount(input.fixedCents(), "The match amount"), null, null, false);
        }
        if (input.matchBp() == null || input.matchBp() < 0 || input.matchBp() > MAX_MATCH_BP) {
            throw ApiException.badRequest("Please enter how much of your contribution is matched, between 0% and 1,000%.");
        }
        if (input.capBpOfGross() == null || input.capBpOfGross() < 0 || input.capBpOfGross() > MAX_CAP_BP) {
            throw ApiException.badRequest("Please enter how much of your pay is matched up to, between 0% and 100%.");
        }
        // Without gross pay there is no checking a contribution against the cap, so the user's word is asked for.
        boolean confirmed = grossCents == null && Boolean.TRUE.equals(input.contributionWithinCap());
        if (grossCents == null && !confirmed) {
            throw ApiException.badRequest(
                    "Without the gross amount of a paycheck, there is no telling whether what you put in is within the share of"
                            + " your pay that is matched. Please fill in the gross amount, or confirm that it is.");
        }
        return new Match(account.id(), mode, null, input.matchBp(), input.capBpOfGross(), confirmed);
    }
}
