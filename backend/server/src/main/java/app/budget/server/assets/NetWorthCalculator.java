package app.budget.server.assets;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.assets.AssetValue;
import app.budget.engine.networth.NetWorth;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.Inputs;
import app.budget.server.assets.AssetsRepository.Asset;
import app.budget.server.assets.AssetsRepository.Value;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.Ledger.AccountBalance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What each asset is worth today, and net worth (spec §5.7). The sums are the engine's
 * ({@link AssetValue}, {@link NetWorth}); this class gathers what goes into them. Nothing here
 * is saved: it is worked out on every request from the values and balances entered.
 */
public final class NetWorthCalculator {

    private final AssetsRepository assets;
    private final AccountsRepository accounts;
    private final Ledger ledger;

    public NetWorthCalculator(AssetsRepository assets, AccountsRepository accounts, Ledger ledger) {
        this.assets = assets;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * An asset as the API returns it.
     *
     * @param latestValue the newest value entered; {@code null} until there is one
     * @param valueCents  what it is taken to be worth today; {@code null} until a value is entered
     * @param estimate    true when today's value was grown from the latest entry by the assumed rate
     * @param owedCents   what is still owed on the loan against it; {@code null} without a loan,
     *                    or when nothing is known about that loan's balance
     * @param equityCents the value less what is owed; the whole value when there is no loan
     */
    public record AssetView(
            long id,
            String name,
            AssetsRepository.Kind kind,
            String color,
            Integer appreciationBp,
            Long linkedLoanAccountId,
            boolean archived,
            LocalDate archivedOn,
            Value latestValue,
            Long valueCents,
            boolean estimate,
            Long owedCents,
            Long equityCents) {}

    /** One asset with every value entered for it, newest first. */
    public record AssetDetail(AssetView asset, List<Value> values) {}

    /**
     * Net worth on a day, and what it is made of. Everything is a positive amount; what is owed
     * is taken off to reach {@code netWorthCents}.
     */
    public record Summary(
            LocalDate date,
            long netWorthCents,
            long assetsCents,
            long liabilitiesCents,
            long cashCents,
            long investmentsCents,
            long propertyCents,
            long cardsCents,
            long loansCents) {}

    /** Every asset with today's value and equity, in the order the user added them. */
    public List<AssetView> all(LocalDate today) {
        Map<Long, Long> owed = owedByAccount(today);
        Map<Long, List<Value>> values = assets.allValues();
        List<AssetView> views = new ArrayList<>();
        for (Asset asset : assets.all()) {
            views.add(view(asset, values.getOrDefault(asset.id(), List.of()), owed, today));
        }
        return views;
    }

    public Optional<AssetDetail> detail(long id, LocalDate today) {
        return assets.find(id).map(asset -> {
            List<Value> values = assets.values(id);
            return new AssetDetail(view(asset, values, owedByAccount(today), today), values);
        });
    }

    /**
     * Net worth at the end of {@code today}. Archived accounts and assets are left out, as on the Accounts screen: each
     * is archived as of today or earlier. On days gone by, each counts until the day it was archived as of.
     */
    public Summary summary(LocalDate today) {
        return summary(today, ledger.balancesOn(today), accountsById(), assets.all(), assets.allValues());
    }

    /**
     * Net worth at the end of each of {@code days}, which must be in date order: what it was on
     * the days gone by, as worked out from the balances and values entered, and what it is
     * projected to be on the days ahead.
     *
     * <p>It starts with the first balance or value the user entered: before that an account is
     * shown standing at its first balance, which says nothing about what it really held. When
     * nothing was ever entered, it starts once money first moves.
     */
    public List<Summary> series(List<LocalDate> days) {
        Map<Long, Account> byId = accountsById();
        List<Asset> allAssets = assets.all();
        Map<Long, List<Value>> values = assets.allValues();
        Optional<LocalDate> firstEntered = firstEntered(byId, allAssets, values);
        List<Summary> series = new ArrayList<>();
        for (Ledger.DayBalances day : ledger.balancesOn(days)) {
            if (firstEntered.isPresent() && day.date().isBefore(firstEntered.get())) {
                continue;
            }
            Summary summary = summary(day.date(), day.balances(), byId, allAssets, values);
            boolean anythingKnown = summary.propertyCents() != 0
                    || day.balances().stream()
                            .anyMatch(balance -> byId.containsKey(balance.accountId()) && !byId.get(balance.accountId()).awayOn(day.date()));
            if (anythingKnown) {
                series.add(summary);
            }
        }
        return series;
    }

    /** The earliest day the user entered a balance for an account, or a value for an asset, before it was archived. */
    private Optional<LocalDate> firstEntered(Map<Long, Account> byId, List<Asset> allAssets, Map<Long, List<Value>> values) {
        List<LocalDate> entered = new ArrayList<>();
        accounts.allBalances().forEach((accountId, balances) -> {
            Account account = byId.get(accountId);
            if (account != null) {
                balances.stream().filter(balance -> !account.awayOn(balance.date())).forEach(balance -> entered.add(balance.date()));
            }
        });
        for (Asset asset : allAssets) {
            values.getOrDefault(asset.id(), List.of()).stream()
                    .filter(value -> !asset.awayOn(value.date()))
                    .forEach(value -> entered.add(value.date()));
        }
        return entered.stream().min(LocalDate::compareTo);
    }

    private Map<Long, Account> accountsById() {
        Map<Long, Account> byId = new HashMap<>();
        for (Account account : accounts.all()) {
            byId.put(account.id(), account);
        }
        return byId;
    }

    private static Summary summary(
            LocalDate day, List<AccountBalance> balances, Map<Long, Account> byId, List<Asset> allAssets, Map<Long, List<Value>> values) {
        Money cash = Money.ZERO;
        Money investments = Money.ZERO;
        Money cards = Money.ZERO;
        Money loans = Money.ZERO;
        for (AccountBalance balance : balances) {
            Account account = byId.get(balance.accountId());
            if (account == null || account.awayOn(day)) {
                continue;
            }
            Money held = Money.ofCents(balance.balanceCents());
            switch (Inputs.choice(AccountType.class, account.type(), "the kind of account")) {
                case CHECKING, SAVINGS -> cash = cash.plus(held);
                case INVESTMENT -> investments = investments.plus(held);
                case CREDIT_CARD -> cards = cards.plus(held);
                case LOAN -> loans = loans.plus(held);
            }
        }
        Money property = Money.ZERO;
        for (Asset asset : allAssets) {
            if (!asset.awayOn(day)) {
                property = property.plus(worth(asset, values.getOrDefault(asset.id(), List.of()), day).orElse(Money.ZERO));
            }
        }
        NetWorth worth = new NetWorth(cash, investments, property, cards, loans);
        return new Summary(
                day, worth.total().cents(), worth.assets().cents(), worth.liabilities().cents(),
                cash.cents(), investments.cents(), property.cents(), cards.cents(), loans.cents());
    }

    private static AssetView view(Asset asset, List<Value> newestFirst, Map<Long, Long> owed, LocalDate today) {
        Value latest = newestFirst.stream().filter(value -> !value.date().isAfter(today)).findFirst().orElse(null);
        Optional<Money> worth = worth(asset, newestFirst, today);
        Long valueCents = worth.map(Money::cents).orElse(null);
        Long owedCents = asset.linkedLoanAccountId() == null ? null : owed.get(asset.linkedLoanAccountId());
        Long equityCents = valueCents == null
                ? null
                : (Long) AssetValue.equity(Money.ofCents(valueCents), Money.ofCents(owedCents == null ? 0 : owedCents)).cents();
        return new AssetView(
                asset.id(), asset.name(), asset.kind(), asset.color(), asset.appreciationBp(), asset.linkedLoanAccountId(),
                asset.archived(), asset.archivedOn(), latest, valueCents,
                latest != null && valueCents != null && valueCents != latest.valueCents(),
                owedCents, equityCents);
    }

    /** What an asset is taken to be worth on {@code day}; empty until a value on or before it has been entered. */
    private static Optional<Money> worth(Asset asset, List<Value> newestFirst, LocalDate day) {
        return AssetValue.on(
                day,
                newestFirst.stream().map(value -> new AssetValue.Entered(value.date(), Money.ofCents(value.valueCents()))).toList(),
                asset.appreciationBp() == null ? null : BasisPoints.of(asset.appreciationBp()));
    }

    /** What every account holds (or owes) at the end of {@code today}, by account id. */
    private Map<Long, Long> owedByAccount(LocalDate today) {
        Map<Long, Long> owed = new HashMap<>();
        for (AccountBalance balance : ledger.balancesOn(today)) {
            owed.put(balance.accountId(), balance.balanceCents());
        }
        return owed;
    }
}
