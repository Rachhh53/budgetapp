import { ChevronLeft, ChevronRight, Clock, Info, PartyPopper, TriangleAlert } from "lucide-react";
import { EditName } from "../components/EditButton";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { type ReactNode, useEffect, useId, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type Bucket, useBuckets } from "../api/buckets";
import { type Category, type Tag, useCategories, useTags } from "../api/categories";
import { type Entry, useEditEntry } from "../api/entries";
import { type PayPeriod, type Paycheck, useEditPaycheck, usePayPeriods } from "../api/income";
import { type Item, useChangeItemAmount, useEndItems, useItems } from "../api/items";
import { useSettings } from "../api/settings";
import {
  type AccountPeriod,
  type BucketPeriod,
  type InterestLine,
  type Occurrence,
  type PeriodDetail,
  useEditOccurrence,
  usePayPeriodsBetween,
  usePeriod,
} from "../api/periods";
import { AddItemButton, useAddItem } from "../components/add/AddItem";
import { Amount, EstimateKey } from "../components/Amount";
import { Card } from "../components/Card";
import { PAYMENT_MODE_LABELS } from "../components/cards/labels";
import { HideAmountsButton } from "../components/HideAmounts";
import { ItemForm } from "../components/items/ItemForm";
import { OccurrenceForm } from "../components/period/OccurrenceForm";
import { CHECK_NUMBER, ConfirmationCode } from "../components/period/ConfirmationCode";
import { type EditScope, InlineAmount } from "../components/period/InlineAmount";
import { INPUT } from "../components/ui";
import { addDays, formatDate, formatDay, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";

/** How far either side of the period on screen the strip of ending balances reaches. */
const STRIP_DAYS_BACK = 45;
const STRIP_DAYS_AHEAD = 150;
const STRIP_LENGTH = 8;

const OWED: readonly Account["type"][] = ["credit_card", "loan"];
const EDITABLE = "input, textarea, select, [contenteditable='true'], [role='dialog'], [role='menu']";

/**
 * The home screen (design/pay-period.png, design/phone-pay-period.png): one pay period with
 * everything coming in and going out of it and where each account ends up. It opens on the
 * current period; the arrows, the strip of periods and the ← / → keys move between them.
 *
 * On a wide screen everything fits without scrolling the page — the three columns scroll
 * inside themselves only if a period holds more than fits. On a phone it is one column,
 * with what needs doing by hand first.
 */
export function PayPeriodPage() {
  const { start: startParam } = useParams();
  const upcoming = usePayPeriods();
  // With no period in the address, open on the current one (or the first, before pay has started).
  const current = upcoming.data?.periods.find((period) => period.current) ?? upcoming.data?.periods[0];
  const start = startParam ?? current?.start;

  const period = usePeriod(start);
  const around = usePayPeriodsBetween(
    start === undefined ? undefined : addDays(start, -STRIP_DAYS_BACK),
    start === undefined ? undefined : addDays(start, STRIP_DAYS_AHEAD),
  );
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const items = useItems();
  const buckets = useBuckets();
  // Only for the cards panel: the period itself is shown whether or not this has arrived.

  const nearby = around.data?.periods ?? [];
  const at = nearby.findIndex((candidate) => candidate.start === start);
  const previous = at > 0 ? nearby[at - 1] : undefined;
  const next = at >= 0 ? nearby[at + 1] : undefined;

  useArrowKeys(previous?.start, next?.start);

  // The + Add item form adds to the period on screen, and previews against it.
  const { setPeriod } = useAddItem();
  const detail = period.data;
  useEffect(() => {
    setPeriod(detail);
    return () => setPeriod(undefined);
  }, [detail, setPeriod]);

  // A bucket's own recurring amount opens its bucket's form; every other row opens that one time it comes round.
  const [editingItem, setEditingItem] = useState<Item>();
  const [editingOccurrence, setEditingOccurrence] = useState<Occurrence>();

  const failure =
    upcoming.error ?? period.error ?? accounts.error ?? categories.error ?? tags.error ?? items.error ?? buckets.error;
  if (failure) {
    return (
      <>
        <h1 className="text-2xl font-bold md:text-3xl">Pay period</h1>
        <p role="alert" className="mt-4 text-danger">
          {failure.message}
        </p>
      </>
    );
  }
  if (upcoming.data && start === undefined) {
    return <NoPeriods hasIncome={upcoming.data.primarySourceId !== null} />;
  }
  if (!detail || !accounts.data || !categories.data || !tags.data || !items.data || !buckets.data) {
    return (
      <>
        <h1 className="text-2xl font-bold md:text-3xl">Pay period</h1>
        <p className="mt-4 text-muted">Loading this pay period…</p>
      </>
    );
  }

  const lists = sortIntoLists(detail);
  const primary = detail.accounts.find((account) => account.accountId === detail.primaryAccountId);
  const primaryAccount = accounts.data.find((account) => account.id === detail.primaryAccountId);
  const anyEstimate =
    detail.paychecks.some((paycheck) => paycheck.estimate) ||
    detail.occurrences.some((occurrence) => occurrence.estimate) ||
    detail.entries.some((entry) => entry.estimate) ||
    // Interest is always an estimate until a balance is entered that includes it.
    detail.interest.length > 0;
  const names = new Map(accounts.data.map((account) => [account.id, account.name]));
  const bucketNames = new Map(buckets.data.map((bucket) => [bucket.id, bucket.name]));
  const context: RowContext = {
    accountName: (id) => names.get(id) ?? "an account",
    bucketName: (id) => (id === null ? undefined : bucketNames.get(id)),
    isChecking: (id) => accounts.data.some((account) => account.id === id && account.type === "checking"),
    isInvestment: (id) => accounts.data.some((account) => account.id === id && account.type === "investment"),
    categories: categories.data,
    tags: tags.data,
    items: items.data,
    editItem: setEditingItem,
    editOccurrence: setEditingOccurrence,
    statementFor: (occurrence) => {
      const paid = (detail.statementsPaid ?? []).find(
        (candidate) => candidate.itemId === occurrence.itemId && candidate.scheduledDate === occurrence.scheduledDate,
      );
      const mode = items.data.find((item) => item.id === occurrence.itemId)?.cardPaymentMode;
      // An amount typed over this one payment is no longer the statement's figure.
      if (paid === undefined || !mode || occurrence.projectedCents !== null) {
        return undefined;
      }
      const what = PAYMENT_MODE_LABELS[mode];
      if (paid.entered) {
        return `${what} on the statement that closed ${formatDay(paid.closeDate)}`;
      }
      return paid.closeDate > todayIso()
        ? `${what}, estimated for the statement closing ${formatDay(paid.closeDate)}`
        : `${what}, estimated for the statement that closed ${formatDay(paid.closeDate)} (not entered yet)`;
    },
  };

  return (
    <div className="flex flex-col gap-3 lg:h-full">
      <header className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-center gap-3">
          <PeriodLink to={previous} label="Previous pay period" icon={ChevronLeft} />
          <div className="min-w-0 flex-1 text-center lg:flex-none lg:text-left">
            <p className="text-xs font-semibold tracking-wide text-muted uppercase">{detail.current ? "Current pay period" : "Pay period"}</p>
            <h1 className="text-xl font-bold md:text-2xl">
              {formatDay(detail.start)} – {formatDate(detail.end)}
            </h1>
          </div>
          <PeriodLink to={next} label="Next pay period" icon={ChevronRight} />
          {detail.current && (
            <span className="hidden rounded-full bg-accent px-3 py-1 text-sm font-semibold text-on-accent lg:inline">Current</span>
          )}
        </div>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center lg:gap-3">
          {anyEstimate && <EstimateKey />}
          <HideAmountsButton />
          <AddItemButton />
        </div>
      </header>

      <PeriodStrip periods={stripAround(nearby, at)} selected={detail.start} />

      <PastDue bills={pastDue([...detail.carriedOver, ...lists.manual])} />

      {primary && primaryAccount ? (
        <Figures balance={primary} account={primaryAccount} detail={detail} />
      ) : (
        <Card>
          <p className="font-medium">No balance to start from yet.</p>
          <p className="mt-1 text-muted">
            {primaryAccount ? (
              <>
                Enter what {primaryAccount.name} holds on the{" "}
                <Link to={`/accounts/${primaryAccount.id}`} className="font-semibold text-accent-ink underline">
                  account's page
                </Link>{" "}
                and this period will show where it ends.
              </>
            ) : (
              "Once your paycheck's account has a balance, this period will show where it ends."
            )}
          </p>
        </Card>
      )}

      <div className="grid grid-cols-1 gap-3 lg:min-h-0 lg:flex-1 lg:grid-cols-3">
        {/* On a phone, what needs doing by hand comes first (design/phone-pay-period.png). */}
        <div className="flex flex-col gap-3 lg:order-2 lg:min-h-0">
          <ManualPanel occurrences={lists.manual} carriedOver={detail.carriedOver} oneOffs={lists.oneOffs} context={context} />
        </div>
        <div className="flex flex-col gap-3 lg:order-1 lg:min-h-0 lg:overflow-y-auto">
          {/* An investment's assumed growth is not income: it shows on that account's own tab instead. */}
          <IncomePanel
            paychecks={detail.paychecks}
            moneyIn={lists.moneyIn}
            interest={detail.interest.filter((line) => !context.isInvestment(line.accountId))}
            context={context}
          />
          <AutoPanel occurrences={lists.auto} context={context} />
          <BudgetsPanel occurrences={lists.budgets} context={context} />
        </div>
        <div className="flex flex-col gap-3 lg:order-3 lg:min-h-0 lg:overflow-y-auto">
          <AccountsPanel
            balances={detail.accounts}
            accounts={accounts.data}
            buckets={buckets.data}
            bucketPeriods={detail.buckets}
            items={items.data}
            periodStart={detail.start}
            periodEnd={detail.end}
            primaryAccountId={detail.primaryAccountId}
            interest={detail.interest}
            promosEnding={detail.promosEnding ?? []}
          />
        </div>
      </div>

      {editingItem && (
        <ItemForm
          item={editingItem}
          accounts={accounts.data}
          categories={categories.data}
          tags={tags.data}
          onClose={() => setEditingItem(undefined)}
          pageLink
        />
      )}
      {editingOccurrence && (
        <OccurrenceForm
          occurrence={editingOccurrence}
          // The same test that puts a row in the "to pay by hand" list.
          byHand={editingOccurrence.kind !== "budget" && !editingOccurrence.autopay}
          fromChecking={context.isChecking(editingOccurrence.payFromAccountId)}
          onClose={() => setEditingOccurrence(undefined)}
        />
      )}
    </div>
  );
}

/** What each row needs to describe itself and to open what it belongs to. */
interface RowContext {
  accountName: (id: number) => string;
  /** `undefined` for no bucket, or one that has since been archived or removed. */
  bucketName: (id: number | null) => string | undefined;
  /** True for a checking account: the only kind a check can be written on. */
  isChecking: (id: number) => boolean;
  /** True for an investment account, whose "interest" is growth the user assumes. */
  isInvestment: (id: number) => boolean;
  categories: readonly Category[];
  tags: readonly Tag[];
  items: readonly Item[];
  editItem: (item: Item) => void;
  /** Opens the short form for one time an item comes round: its amount, and whether and how it was paid. */
  editOccurrence: (occurrence: Occurrence) => void;
  /** For a card payment that follows its card: what the amount is, and which statement it is for. */
  statementFor: (occurrence: Occurrence) => string | undefined;
}

/** The account chosen in Accounts & buckets, kept in the address so it goes along from one pay period to the next. */
const ACCOUNT_PARAM = "account";

/**
 * The way to another pay period that keeps what is chosen on this one (the account showing in
 * Accounts & buckets), so stepping through periods follows one account. Coming back to the
 * screen from elsewhere in the app starts afresh.
 */
function usePeriodPath(): (start: string) => string {
  const { search } = useLocation();
  return (start) => `/periods/${start}${search}`;
}

/** ← and → move between periods, unless the keys are being used to type or to work a dialog. */
function useArrowKeys(previous: string | undefined, next: string | undefined) {
  const navigate = useNavigate();
  const pathTo = usePeriodPath();
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) {
        return;
      }
      if (event.target instanceof Element && event.target.closest(EDITABLE)) {
        return;
      }
      const to = event.key === "ArrowLeft" ? previous : event.key === "ArrowRight" ? next : undefined;
      if (to !== undefined) {
        event.preventDefault();
        void navigate(pathTo(to));
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
    // The path only changes with the address, which is read again each time the keys are bound.
  }, [navigate, previous, next, pathTo]);
}

/** The period before the one on screen, then it and the ones after: eight in all. */
function stripAround(periods: readonly PayPeriod[], at: number): PayPeriod[] {
  const from = Math.max(0, at - 1);
  return periods.slice(from, from + STRIP_LENGTH);
}

interface Lists {
  manual: Occurrence[];
  auto: Occurrence[];
  budgets: Occurrence[];
  moneyIn: Entry[];
  oneOffs: Entry[];
}

/** Sorts a period's contents into the panels of the screen. Skipped occurrences are left out. */
function sortIntoLists(period: PeriodDetail): Lists {
  const active = period.occurrences.filter((occurrence) => !occurrence.skipped);
  const byDay = (a: Occurrence, b: Occurrence) => a.payBy.localeCompare(b.payBy) || a.name.localeCompare(b.name);
  return {
    budgets: active.filter((occurrence) => occurrence.kind === "budget").sort(byDay),
    auto: active.filter((occurrence) => occurrence.kind !== "budget" && occurrence.autopay).sort(byDay),
    manual: active.filter((occurrence) => occurrence.kind !== "budget" && !occurrence.autopay).sort(byDay),
    moneyIn: period.entries.filter((entry) => entry.kind === "money_in"),
    oneOffs: period.entries.filter((entry) => entry.kind !== "money_in"),
  };
}

function NoPeriods({ hasIncome }: { hasIncome: boolean }) {
  return (
    <>
      <h1 className="mb-4 text-2xl font-bold md:text-3xl">Pay periods</h1>
      <Card>
        {hasIncome ? (
          <>
            <p className="font-medium">Your pay periods haven't started yet.</p>
            <p className="mt-1 text-muted">
              The first one begins on your first payday, and each needs the payday after it to know where it ends.{" "}
              <Link to="/income" className="font-semibold text-accent-ink underline">
                Check your pay schedule
              </Link>{" "}
              if that doesn't sound right.
            </p>
          </>
        ) : (
          <>
            <p className="font-medium">No pay periods yet.</p>
            <p className="mt-1 text-muted">
              Pay periods come from your main paycheck.{" "}
              <Link to="/income" className="font-semibold text-accent-ink underline">
                Add your income
              </Link>{" "}
              and they'll appear here, one per payday.
            </p>
          </>
        )}
      </Card>
    </>
  );
}

function PeriodLink({ to, label, icon: Icon }: { to: PayPeriod | undefined; label: string; icon: typeof ChevronLeft }) {
  const box = "flex size-11 shrink-0 items-center justify-center rounded-lg border border-line bg-surface";
  const pathTo = usePeriodPath();
  if (to === undefined) {
    // Kept in place, dimmed, so the title does not jump when there is nowhere further to go.
    return (
      <span aria-hidden className={`${box} opacity-40`}>
        <Icon className="size-5" />
      </span>
    );
  }
  return (
    <Link to={pathTo(to.start)} aria-label={`${label}, ${formatDay(to.start)} to ${formatDay(to.end)}`} className={box}>
      <Icon aria-hidden className="size-5" />
    </Link>
  );
}

/** Ending balances, period by period, so a tight one ahead is seen coming (the strip in the mockup). */
function PeriodStrip({ periods, selected }: { periods: readonly PayPeriod[]; selected: string }) {
  const pathTo = usePeriodPath();
  if (periods.length === 0 || periods.every((period) => period.projectedEndingCents === null)) {
    return null;
  }
  return (
    <nav aria-label="Ending balance by period" className="flex items-center gap-3">
      <p className="hidden shrink-0 text-sm leading-tight text-muted xl:block">
        Ending balance
        <br />
        by period
      </p>
      <ol className="flex min-w-0 flex-1 gap-2 overflow-x-auto pb-1">
        {periods.map((period) => (
          <li key={period.start} className="min-w-28 flex-1">
            <Link
              to={pathTo(period.start)}
              aria-current={period.start === selected ? "page" : undefined}
              className={`block rounded-lg bg-surface px-3 py-1.5 ${
                period.start === selected ? "border-2 border-accent" : "border border-line"
              }`}
            >
              <span className="block text-xs text-muted">
                {formatDay(period.start)}
                {/* In words beside the date, so the flag adds no height and never rests on color alone. */}
                {period.lowBalance && (
                  <span className="font-semibold text-danger">
                    {" "}
                    · Low<span className="sr-only"> balance</span>
                  </span>
                )}
                {(period.promosEnding?.length ?? 0) > 0 && (
                  // Kept to one word on screen so the strip stays one line high; the cards panel below says the rest.
                  <span title="A promotional rate ends in this pay period with a balance left" className="font-semibold text-warn-ink">
                    {" "}
                    · Promo<span className="sr-only"> rate ends</span>
                  </span>
                )}
              </span>
              <span data-amount className="block font-semibold tabular-nums">
                {period.projectedEndingCents === null ? "—" : formatCents(period.projectedEndingCents)}
              </span>
            </Link>
          </li>
        ))}
      </ol>
    </nav>
  );
}

/** The five numbers along the top, for the account the paycheck lands in. */
function Figures({ balance, account, detail }: { balance: AccountPeriod; account: Account; detail: PeriodDetail }) {
  // Whole numbers without the $, as the mockup writes the sum under the ending balance.
  const plain = (cents: number) => formatCents(cents).replace("$", "");
  return (
    <dl className="grid grid-cols-2 gap-3 lg:grid-cols-5">
      <Figure
        label="Starting balance"
        cents={balance.startingCents}
        // A balance dated after this period is the first one entered: nothing earlier is counted, and the note says so.
        note={`${account.name} · ${formatDay(balance.startingOn)}${
          balance.entered ? " · set by you" : balance.startingOn > detail.end ? " · first balance, entered later" : ""
        }`}
        className="order-2 lg:order-1"
      />
      <Figure
        label="Coming in"
        cents={balance.inCents}
        sign="+"
        note="Paychecks and money in"
        breakdown={comingInto(balance, detail)}
        breakdownLabel="What is coming in"
        className="order-3 lg:order-2"
      />
      <Figure
        label="Going out"
        cents={balance.outCents}
        sign="−"
        note="Bills, transfers, budgets, one-offs"
        className="order-4 lg:order-3"
      />
      <Figure
        label="Projected ending"
        cents={balance.endingCents}
        note={`${plain(balance.startingCents)} + ${plain(balance.inCents)} − ${plain(balance.outCents)}`}
        className="order-1 col-span-2 lg:order-4 lg:col-span-1"
        highlight
      />
      <Figure
        label="Available after buckets"
        cents={balance.availableCents}
        note={
          balance.lowBalance
            ? `Low balance: dips to ${formatCents(balance.lowestCents)} on ${formatDay(balance.lowestOn)}`
            : `${formatCents(balance.bucketsCents)} in buckets · lowest ${formatCents(balance.lowestCents)} on ${formatDay(balance.lowestOn)}`
        }
        warn={balance.lowBalance}
        className="order-5"
      />
    </dl>
  );
}

/** One thing that makes up a figure. */
interface Part {
  label: string;
  cents: number;
}

/**
 * What "Coming in" is made of for one account: its paychecks, one-off money in, transfers
 * arriving and interest. The total itself comes from the backend; if these do not add up to
 * it, the difference is listed too, so the list always sums to the figure above it.
 */
function comingInto(balance: AccountPeriod, detail: PeriodDetail, grows = false): Part[] {
  const here = balance.accountId;
  const parts: Part[] = [
    ...detail.paychecks
      .filter((paycheck) => paycheck.depositAccountId === here)
      .map((paycheck) => ({ label: `${paycheck.sourceName} paycheck`, cents: paycheck.netCents })),
    ...detail.entries
      .filter((entry) => (entry.kind === "money_in" ? entry.accountId === here : entry.kind === "transfer" && entry.toAccountId === here))
      .map((entry) => ({ label: entry.kind === "transfer" ? `${entry.description} (transfer)` : entry.description, cents: entry.amountCents })),
    ...[...detail.carriedOver, ...detail.occurrences]
      .filter((occurrence) => !occurrence.skipped && occurrence.payToAccountId === here)
      .map((occurrence) => ({ label: `${occurrence.name} (transfer)`, cents: occurrence.amountCents })),
    // What a paycheck sends straight here instead of to its deposit account: a deduction, or the employer's match.
    ...detail.paychecks.flatMap((paycheck) =>
      paycheck.contributions
        .filter((contribution) => contribution.toAccountId === here)
        .map((contribution) => ({ label: `${contribution.label} · ${paycheck.sourceName}`, cents: contribution.amountCents })),
    ),
    ...detail.interest
      .filter((line) => line.accountId === here)
      .map((line) => ({ label: grows ? "Assumed growth (your assumption)" : "Interest (estimate)", cents: line.amountCents })),
  ];
  const rest = balance.inCents - parts.reduce((sum, part) => sum + part.cents, 0);
  if (rest !== 0) {
    // Less than the list: a balance entered part-way through already includes what arrived before it.
    parts.push({ label: rest < 0 ? "Already in the balance you entered" : "Other", cents: rest });
  }
  return parts;
}

function Figure({
  label,
  cents,
  sign = "",
  note,
  breakdown,
  breakdownLabel,
  highlight = false,
  warn = false,
  className = "",
}: {
  label: string;
  cents: number;
  sign?: string;
  note: string;
  /** What the number is made of, shown when the figure is hovered, focused or tapped. */
  breakdown?: readonly Part[];
  breakdownLabel?: string;
  /** The number the screen is about: outlined, and largest on a phone. */
  highlight?: boolean;
  /** The note is a warning (it says so in words too): shown in the warning color. */
  warn?: boolean;
  className?: string;
}) {
  return (
    <div
      // Focusable when it has more to show, so the keyboard and a tap reach what a hover does.
      tabIndex={breakdown ? 0 : undefined}
      className={`rounded-card bg-surface px-4 py-2.5 short:py-1.5 ${highlight ? "border-2 border-accent" : "border border-line"} ${
        breakdown ? "group relative cursor-help focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-ink" : ""
      } ${className}`}
    >
      <dt className="flex items-center gap-1 text-sm text-muted">
        {label}
        {breakdown && <Info aria-hidden className="size-3.5" />}
      </dt>
      <dd>
        <span data-amount className={`block font-bold tabular-nums ${highlight ? "text-3xl lg:text-2xl" : "text-xl lg:text-2xl"} lg:short:text-xl`}>
          {sign}
          {formatCents(cents)}
        </span>
        <span data-amount title={note} className={`block text-sm tabular-nums lg:truncate ${warn ? "font-semibold text-danger" : "text-muted"}`}>
          {note}
        </span>
        {breakdown && (
          <div
            role="group"
            aria-label={breakdownLabel}
            // Opens towards the middle of the screen: on a phone this figure sits in the right-hand column.
            className="invisible absolute top-full right-0 z-20 mt-1 w-72 max-w-[calc(100vw-2rem)] rounded-lg border border-line bg-surface p-3 text-sm opacity-0 shadow-lg transition-opacity group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100 lg:right-auto lg:left-0"
          >
            {breakdown.length === 0 ? (
              <p className="text-muted">Nothing in this pay period.</p>
            ) : (
              <ul className="space-y-1">
                {breakdown.map((part, index) => (
                  <li key={index} className="flex items-baseline justify-between gap-3">
                    <span className="min-w-0 break-words">{part.label}</span>
                    <span data-amount className="shrink-0 font-semibold tabular-nums">
                      {part.cents < 0 ? "−" : ""}
                      {formatCents(Math.abs(part.cents))}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </dd>
    </div>
  );
}

/** One panel of the screen: a titled region that scrolls inside itself as a last resort. */
function Panel({ title, aside, grow = false, children }: { title: string; aside?: string; grow?: boolean; children: ReactNode }) {
  const headingId = useId();
  return (
    <section
      aria-labelledby={headingId}
      className={`flex flex-col rounded-card border border-line bg-surface px-4 py-3 ${grow ? "lg:min-h-0 lg:flex-1" : ""}`}
    >
      <div className="mb-1 flex items-baseline justify-between gap-3">
        <h2 id={headingId} className="font-semibold">
          {title}
        </h2>
        {aside && <p className="text-sm text-muted">{aside}</p>}
      </div>
      {/* The side padding (taken back by the margin) leaves room for a focus ring, which scrolling would otherwise clip. */}
      <div className={grow ? "lg:-mx-2 lg:min-h-0 lg:flex-1 lg:overflow-y-auto lg:px-2" : ""}>{children}</div>
    </section>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="py-1 text-sm text-muted">{children}</p>;
}

/**
 * A row: what it is on the left, the amount on the right, in a column that lines up. A row that
 * can be opened has no pencil: like every list row in the app it shows the highlight and the hand
 * cursor, and opens when clicked or tapped anywhere (its name is the button that does it).
 */
function Row({
  lead,
  title,
  detail,
  amount,
  below,
  wrap = false,
}: {
  lead?: ReactNode;
  title: ReactNode;
  detail?: ReactNode;
  amount: ReactNode;
  below?: ReactNode;
  /** Let the detail run onto a second line instead of cutting it short: for rows where every word matters. */
  wrap?: boolean;
}) {
  return (
    <li onClick={clickRowToEdit} className={`py-1.5 lg:py-1 ${ROW_OPENS}`}>
      <div className="flex items-center gap-3">
        {lead}
        <div className="min-w-0 flex-1 leading-tight">
          <div className="font-medium wrap-break-word">{title}</div>
          {/* Normally one line on a wide screen, so rows stay short enough to fit; the full text is in the tooltip. */}
          {detail && (
            <div
              title={typeof detail === "string" ? detail : undefined}
              className={`text-sm leading-tight text-muted ${wrap ? "" : "lg:truncate"}`}
            >
              {detail}
            </div>
          )}
        </div>
        <div className="shrink-0">{amount}</div>
      </div>
      {below}
    </li>
  );
}

/** Under a card payment that follows its card: what the amount is and which statement it pays, so an estimate explains itself. */
function StatementNote({ occurrence, context }: { occurrence: Occurrence; context: RowContext }) {
  const note = context.statementFor(occurrence);
  return note === undefined ? null : <span className="block">{note}</span>;
}

/** A recurring item's name, which opens it for a "this and all future" change. */
function ItemName({ occurrence, context }: { occurrence: Occurrence; context: RowContext }) {
  const item = context.items.find((candidate) => candidate.id === occurrence.itemId);
  if (item === undefined) {
    return <>{occurrence.name}</>;
  }
  // Setting money aside belongs to its bucket, whose form is where it is changed. Anything else opens this one time;
  // the item itself is a link away, on its own page.
  const open = () => (item.kind === "bucket_move" ? context.editItem(item) : context.editOccurrence(occurrence));
  return <EditName name={occurrence.name} onClick={open} />;
}

/** The amount of one occurrence, typed over in place: just this one, or this and all future. */
function OccurrenceAmount({ occurrence, context }: { occurrence: Occurrence; context: RowContext }) {
  const editOne = useEditOccurrence();
  const changeFuture = useChangeItemAmount();
  const item = context.items.find((candidate) => candidate.id === occurrence.itemId);
  // A yearly total is shared out by the app, so one occurrence's amount cannot stand for the item's.
  // Nor can a payment that follows its card: it has no amount of its own to change from here on.
  const canChangeFuture = item !== undefined && item.amountMode === "per_occurrence" && !item.followsCard;

  const save = (amountCents: number, scope: EditScope) => {
    if (scope === "future" && item !== undefined) {
      changeFuture.mutate({ item, amountCents, effectiveFrom: occurrence.scheduledDate });
    } else {
      editOne.mutate({ occurrence, edit: { amountCents } });
    }
  };

  return (
    <InlineAmount
      name={occurrence.name}
      cents={occurrence.amountCents}
      estimate={occurrence.estimate}
      projectedCents={occurrence.projectedCents}
      canChangeFuture={canChangeFuture}
      error={editOne.error?.message ?? changeFuture.error?.message}
      onSave={save}
    />
  );
}

function EntryAmount({ entry }: { entry: Entry }) {
  const edit = useEditEntry();
  return (
    <InlineAmount
      name={entry.description}
      cents={entry.amountCents}
      estimate={entry.estimate}
      error={edit.error?.message}
      // A typed amount is the amount: it stops being an estimate (spec §1).
      onSave={(amountCents) => edit.mutate({ ...entry, amountCents, estimate: false })}
    />
  );
}

function PaycheckAmount({ paycheck }: { paycheck: Paycheck }) {
  const edit = useEditPaycheck();
  return (
    <InlineAmount
      name={`${paycheck.sourceName} paycheck`}
      cents={paycheck.netCents}
      estimate={paycheck.estimate}
      projectedCents={paycheck.projectedCents}
      error={edit.error?.message}
      onSave={(netCents) => edit.mutate({ paycheck, netCents })}
    />
  );
}

/** A one-off's description, which opens it in the + Add item form. */
function EntryName({ entry }: { entry: Entry }) {
  const { editEntry } = useAddItem();
  return <EditName name={entry.description} onClick={() => editEntry(entry)} />;
}

function Dot({ color }: { color: string | undefined }) {
  return <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: color ?? "var(--border)" }} />;
}

/**
 * The category's colour beside a row. Its name, and the row's tags, are kept off the line
 * itself, where they read like an account or bucket name: they show on hover and are read
 * out by a screen reader.
 */
function CategoryDot({ categoryId, tagIds, context }: { categoryId: number | null; tagIds: readonly number[]; context: RowContext }) {
  const category = context.categories.find((candidate) => candidate.id === categoryId);
  const tagNames = context.tags.filter((tag) => tagIds.includes(tag.id)).map((tag) => tag.name);
  const about = [category && `Category: ${category.name}`, tagNames.length > 0 && `Tags: ${tagNames.join(", ")}`]
    .filter(Boolean)
    .join(" · ");
  if (about === "") {
    return <Dot color={undefined} />;
  }
  return (
    <span title={about} className="flex shrink-0 items-center">
      <Dot color={category?.color} />
      <span className="sr-only">{about}</span>
    </span>
  );
}

function IncomePanel({
  paychecks,
  moneyIn,
  interest,
  context,
}: {
  paychecks: readonly Paycheck[];
  moneyIn: readonly Entry[];
  interest: readonly InterestLine[];
  context: RowContext;
}) {
  return (
    <Panel title="Income">
      {paychecks.length === 0 && moneyIn.length === 0 && interest.length === 0 ? (
        <Empty>Nothing is coming in this period.</Empty>
      ) : (
        <ul className="divide-y divide-line">
          {paychecks.map((paycheck) => (
            <Row
              key={`${paycheck.incomeSourceId}-${paycheck.scheduledDate}`}
              title={`${paycheck.sourceName} paycheck`}
              detail={`${formatDay(paycheck.payDate)} → ${context.accountName(paycheck.depositAccountId)}`}
              amount={<PaycheckAmount paycheck={paycheck} />}
            />
          ))}
          {moneyIn.map((entry) => (
            <Row
              key={entry.id}
              title={<EntryName entry={entry} />}
              detail={`One-off · ${formatDay(entry.date)} → ${context.accountName(entry.accountId)}`}
              amount={<EntryAmount entry={entry} />}
            />
          ))}
          {/* Worked out from the balance and the rate, so it is not typed over: entering the real balance replaces it. */}
          {interest.map((line) => (
            <Row
              key={`${line.accountId}-${line.date}`}
              title="Interest"
              detail={`${formatDay(line.date)} → ${context.accountName(line.accountId)} · ${formatRate(line.rateBp)} APY`}
              amount={<Amount cents={line.amountCents} estimate className="w-28 font-semibold" />}
            />
          ))}
        </ul>
      )}
    </Panel>
  );
}

/**
 * "Checking", "Checking · Gifts bucket", "Checking → Savings · Emergency fund bucket": the
 * account (and bucket) an occurrence is paid from, and where its money ends up when it stays the user's.
 */
function whereItGoes(occurrence: Occurrence, context: RowContext): string {
  const account = context.accountName(occurrence.payFromAccountId);
  const paidFrom = context.bucketName(occurrence.fromBucketId);
  // Paid out of a bucket: say which, since the bucket goes down with the account.
  const from = paidFrom === undefined ? account : `${account} · ${paidFrom} bucket`;
  const bucket = context.bucketName(occurrence.bucketId);
  if (occurrence.kind === "bucket_move") {
    return `${from} → ${bucket ?? "a bucket"} bucket`;
  }
  const savedIn = context.bucketName(occurrence.saveUpBucketId);
  if (occurrence.payToAccountId === null) {
    return savedIn === undefined ? from : `${from} · saved up in ${savedIn}`;
  }
  const to = context.accountName(occurrence.payToAccountId);
  return bucket === undefined ? `${from} → ${to}` : `${from} → ${to} · ${bucket} bucket`;
}

function AutoPanel({ occurrences, context }: { occurrences: readonly Occurrence[]; context: RowContext }) {
  return (
    <Panel title="Auto-pay & transfers" aside="before next paycheck">
      {occurrences.length === 0 ? (
        <Empty>Nothing is paid automatically this period.</Empty>
      ) : (
        <ul className="divide-y divide-line">
          {occurrences.map((occurrence) => (
            <Row
              key={`${occurrence.itemId}-${occurrence.scheduledDate}`}
              lead={<span className="shrink-0 rounded-md bg-bg px-1.5 py-0.5 text-xs font-semibold text-muted">AUTO</span>}
              title={<ItemName occurrence={occurrence} context={context} />}
              detail={
                <>
                  {formatDay(occurrence.payBy)} · {whereItGoes(occurrence, context)}
                  <StatementNote occurrence={occurrence} context={context} />
                </>
              }
              amount={<OccurrenceAmount occurrence={occurrence} context={context} />}
              // Which statement a card payment is for is the point of that line, so it is never cut short.
              wrap={context.statementFor(occurrence) !== undefined}
            />
          ))}
        </ul>
      )}
    </Panel>
  );
}

function BudgetsPanel({ occurrences, context }: { occurrences: readonly Occurrence[]; context: RowContext }) {
  const total = occurrences.reduce((sum, occurrence) => sum + occurrence.amountCents, 0);
  return (
    <Panel title="Budgets" aside="this paycheck">
      {occurrences.length === 0 ? (
        <Empty>No budgets this period. Add one as a recurring item of the Budget type.</Empty>
      ) : (
        <>
          <ul className="divide-y divide-line">
            {occurrences.map((occurrence) => (
              <Row
                key={`${occurrence.itemId}-${occurrence.scheduledDate}`}
                lead={
                  <CategoryDot
                    categoryId={occurrence.categoryId}
                    tagIds={context.items.find((item) => item.id === occurrence.itemId)?.tagIds ?? []}
                    context={context}
                  />
                }
                title={
                  <>
                    <ItemName occurrence={occurrence} context={context} />
                    {/* Each budget has its own date (spec §5.3), so it is shown beside the name. */}
                    <span className="text-sm font-normal text-muted"> · {formatDay(occurrence.payBy)}</span>
                  </>
                }
                amount={<OccurrenceAmount occurrence={occurrence} context={context} />}
              />
            ))}
          </ul>
          <p className="flex items-center justify-between gap-3 border-t border-line pt-1.5 font-semibold">
            <span>Total</span>
            <span data-amount className="w-28 px-2 text-right tabular-nums">
              {formatCents(total)}
            </span>
          </p>
        </>
      )}
    </Panel>
  );
}

/**
 * The bills paid by hand whose due date has gone by without a tick: listed once each, earliest
 * first. A bill on autopay is taken as paid on its day, so it is never one of these.
 */
function pastDue(bills: readonly Occurrence[]): Occurrence[] {
  const today = todayIso();
  const seen = new Set<string>();
  return bills
    .filter((bill) => !bill.paid && !bill.skipped && !bill.autopay && bill.kind !== "budget" && bill.dueDate < today)
    .filter((bill) => {
      const key = `${bill.itemId}-${bill.scheduledDate}`;
      return !seen.has(key) && seen.add(key);
    })
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.name.localeCompare(b.name));
}

/**
 * A bill paid by hand that is not ticked yet and falls due within `days` from today: "Due
 * today", "Due tomorrow" or "Due in 3 days". Null for everything else, and when the setting
 * (Settings → Warnings → Highlight unpaid bills) is off.
 */
function dueSoon(bill: Occurrence, days: number): string | null {
  if (bill.paid || bill.skipped || bill.autopay || bill.kind === "budget") {
    return null;
  }
  const today = todayIso();
  for (let ahead = 0; ahead <= days; ahead++) {
    if (addDays(today, ahead) === bill.dueDate) {
      return ahead === 0 ? "Due today" : ahead === 1 ? "Due tomorrow" : `Due in ${ahead} days`;
    }
  }
  return null;
}

/**
 * The warning at the top of the screen while anything is past due. Said in words and set apart,
 * with an icon, never by color alone: a missed bill is the first thing to see. Paying late is
 * allowed: ticking the bill records the day it was paid, and the warning goes.
 */
function PastDue({ bills }: { bills: readonly Occurrence[] }) {
  if (bills.length === 0) {
    return null;
  }
  return (
    <section role="alert" aria-label="Past due" className="rounded-card border-2 border-danger bg-surface px-4 py-3">
      <p className="flex items-center gap-2 text-lg font-bold text-danger">
        <TriangleAlert aria-hidden className="size-6 shrink-0" />
        {bills.length === 1 ? "1 bill is past due" : `${bills.length} bills are past due`}
      </p>
      <ul className="mt-1 space-y-0.5">
        {bills.map((bill) => (
          <li key={`${bill.itemId}-${bill.scheduledDate}`} className="font-semibold">
            {bill.name} · <span data-amount>{formatCents(bill.amountCents)}</span> · was due {formatDate(bill.dueDate)}
          </li>
        ))}
      </ul>
      <p className="mt-1 text-sm text-muted">Tick each one under Pay manually when it is paid. The day you tick it is kept as the day it was paid.</p>
    </section>
  );
}

function ManualPanel({
  occurrences,
  carriedOver,
  oneOffs,
  context,
}: {
  occurrences: readonly Occurrence[];
  carriedOver: readonly Occurrence[];
  oneOffs: readonly Entry[];
  context: RowContext;
}) {
  // Something carried over is listed once, even when it also belongs to this period.
  const carried = carriedOver.filter(
    (late) => !occurrences.some((occurrence) => occurrence.itemId === late.itemId && occurrence.scheduledDate === late.scheduledDate),
  );
  const bills = [...carried, ...occurrences];
  const paid = bills.filter((occurrence) => occurrence.paid).length;

  return (
    <Panel title="Pay manually & one-offs" aside={bills.length === 0 ? undefined : `${paid} of ${bills.length} paid`} grow>
      {bills.length === 0 && oneOffs.length === 0 ? (
        <Empty>Nothing to pay by hand this period, and no one-offs. Use + Add item for a one-time purchase.</Empty>
      ) : (
        <ul className="divide-y divide-line">
          {bills.map((occurrence) => (
            <ManualRow
              key={`${occurrence.itemId}-${occurrence.scheduledDate}`}
              occurrence={occurrence}
              carriedOver={carried.includes(occurrence)}
              context={context}
            />
          ))}
          {oneOffs.map((entry) => (
            <Row
              key={entry.id}
              lead={<CategoryDot categoryId={entry.categoryId} tagIds={entry.tagIds} context={context} />}
              title={<EntryName entry={entry} />}
              detail={oneOffDetail(entry, context)}
              amount={<EntryAmount entry={entry} />}
            />
          ))}
        </ul>
      )}
    </Panel>
  );
}

function oneOffDetail(entry: Entry, context: RowContext): string {
  const where =
    entry.toAccountId === null
      ? context.accountName(entry.accountId)
      : `${context.accountName(entry.accountId)} → ${context.accountName(entry.toAccountId)}`;
  // The category is on the coloured dot, not here: after an account it reads like one of its buckets.
  return ["One-off", formatDay(entry.date), where, entry.checkNumber && `Check #${entry.checkNumber}`].filter(Boolean).join(" · ");
}

/** A bill paid by hand: tick it when it is paid, and keep the confirmation code with it. */
function ManualRow({ occurrence, carriedOver, context }: { occurrence: Occurrence; carriedOver: boolean; context: RowContext }) {
  const edit = useEditOccurrence();
  const checkboxId = useId();

  const due = `Due ${formatDay(occurrence.dueDate)}`;
  const isPastDue = pastDue([occurrence]).length > 0;
  const soon = dueSoon(occurrence, Number(useSettings().data?.unpaid_highlight_days ?? 0));
  // Paying late is allowed, and is kept on record: the day it was paid, against the day it was due.
  const paidLate = occurrence.paid && occurrence.paidDate !== null && occurrence.paidDate > occurrence.dueDate;
  const payBy = occurrence.payBy === occurrence.dueDate ? "" : ` · pay by ${formatDay(occurrence.payBy)}`;
  const detail = `${due}${payBy} · ${whereItGoes(occurrence, context)}`;

  return (
    <Row
      lead={
        // The label gives the small box a 44px target (spec: touch targets).
        <label htmlFor={checkboxId} className="-my-1 -ml-2 flex size-11 shrink-0 cursor-pointer items-center justify-center">
          <input
            id={checkboxId}
            type="checkbox"
            checked={occurrence.paid}
            disabled={edit.isPending}
            onChange={(event) =>
              edit.mutate({ occurrence, edit: event.target.checked ? { paid: true, paidDate: todayIso() } : { paid: false } })
            }
            aria-label={`${occurrence.name} paid`}
            className="size-5 accent-(--accent)"
          />
        </label>
      }
      title={<ItemName occurrence={occurrence} context={context} />}
      detail={
        <>
          {detail}
          <StatementNote occurrence={occurrence} context={context} />
          {/* In words with an icon, never color alone. */}
          {isPastDue && (
            <span className="flex items-center gap-1 font-bold text-danger">
              <TriangleAlert aria-hidden className="size-4 shrink-0" />
              Past due
            </span>
          )}
          {soon !== null && (
            <span className="flex items-center gap-1 font-semibold text-warn-ink">
              <Clock aria-hidden className="size-4 shrink-0" />
              {soon}
            </span>
          )}
          {carriedOver && <span className="block font-medium text-ink">Still unpaid from an earlier period</span>}
        </>
      }
      amount={<OccurrenceAmount occurrence={occurrence} context={context} />}
      // "Pay by" is the point of this list, so it is never cut short.
      wrap
      below={
        <div className="flex flex-wrap items-center gap-x-3 pl-9">
          {/* Said in words as well as by the tick, so it never rests on color or shape alone. */}
          {occurrence.paid && (
            <span className="text-sm font-semibold">
              ✓ Paid{occurrence.paidDate ? ` ${formatDay(occurrence.paidDate)}` : ""}
              {paidLate && " · late"}
            </span>
          )}
          <ConfirmationCode
            name={occurrence.name}
            code={occurrence.confirmationCode}
            onSave={(confirmationCode) => edit.mutate({ occurrence, edit: { confirmationCode } })}
          />
          {context.isChecking(occurrence.payFromAccountId) && (
            <ConfirmationCode
              name={occurrence.name}
              code={occurrence.checkNumber}
              wording={CHECK_NUMBER}
              onSave={(checkNumber) => edit.mutate({ occurrence, edit: { checkNumber } })}
            />
          )}
          {edit.error && (
            <span role="alert" className="text-sm text-danger">
              {edit.error.message}
            </span>
          )}
        </div>
      }
    />
  );
}

/**
 * Where every account stands at the end of the period, and what its buckets hold: one account
 * at a time, chosen from a list. A list rather than a row of tabs, so the panel is the same
 * height however many accounts there are and nothing has to be scrolled sideways to reach one.
 */
function AccountsPanel({
  balances,
  accounts,
  buckets,
  bucketPeriods,
  items,
  periodStart,
  periodEnd,
  primaryAccountId,
  interest,
  promosEnding,
}: {
  balances: readonly AccountPeriod[];
  accounts: readonly Account[];
  buckets: readonly Bucket[];
  bucketPeriods: readonly BucketPeriod[];
  items: readonly Item[];
  periodStart: string;
  periodEnd: string;
  primaryAccountId: number | null;
  interest: readonly InterestLine[];
  /** Promotional rates that run out during this period with a balance still on them, on any card. */
  promosEnding: NonNullable<PeriodDetail["promosEnding"]>;
}) {
  const id = useId();
  // Every account is in the list, so one never seems to have gone missing: an account with no balance
  // entered by the end of this period has nothing to show yet, and says so when chosen.
  const known = accounts
    .map((account) => ({ account, balance: balances.find((candidate) => candidate.accountId === account.id) }))
    .filter((tab) => tab.balance !== undefined || !tab.account.archived);
  // Kept in the address rather than in the panel, so the same account is still showing in the next pay period.
  const [search, setSearch] = useSearchParams();
  const asked = Number(search.get(ACCOUNT_PARAM));
  const chosen = Number.isInteger(asked) && asked > 0 ? asked : null;
  const setChosen = (accountId: number) =>
    setSearch(
      (current) => {
        const next = new URLSearchParams(current);
        next.set(ACCOUNT_PARAM, String(accountId));
        return next;
      },
      // Choosing an account is not a step to go Back through.
      { replace: true },
    );
  const shown =
    known.find((tab) => tab.account.id === (chosen ?? primaryAccountId)) ?? known.find((tab) => tab.balance !== undefined) ?? known[0];

  if (shown === undefined || known.every((tab) => tab.balance === undefined)) {
    return (
      <Panel title="Accounts & buckets" aside="at period end">
        <Empty>
          Enter a balance on the{" "}
          <Link to="/accounts" className="font-semibold text-accent-ink underline">
            Accounts
          </Link>{" "}
          screen and each account's ending balance will show here.
        </Empty>
      </Panel>
    );
  }

  const picker = (
    <div className="mb-2">
      <label htmlFor={`${id}-account`} className="sr-only">
        Account
      </label>
      <select
        id={`${id}-account`}
        value={shown.account.id}
        onChange={(event) => setChosen(Number(event.target.value))}
        className={`${INPUT} font-semibold`}
      >
        {known.map(({ account }) => (
          <option key={account.id} value={account.id}>
            {account.name}
          </option>
        ))}
      </select>
    </div>
  );

  const owed = OWED.includes(shown.account.type);
  const { balance } = shown;
  if (balance === undefined) {
    return (
      <Panel title="Accounts & buckets" aside="at period end">
        {picker}
        <div>
          <Empty>
            No balance was entered for {shown.account.name} on or before {formatDay(periodEnd)}, so there is nothing to show for it in
            this pay period.{" "}
            <Link to={`/accounts/${shown.account.id}`} className="font-semibold text-accent-ink underline">
              Enter a balance
            </Link>{" "}
            dated then or earlier and it will appear here.
          </Empty>
        </div>
      </Panel>
    );
  }
  // Only when it differs from the period itself; otherwise the lines mean the whole period, as they read.
  // This period ends before the first balance entered for the account: it stands at that balance, with nothing counted yet.
  const beforeFirst = balance.startingOn > periodEnd;
  const assumed = interest.filter((line) => line.accountId === shown.account.id).reduce((sum, line) => sum + line.amountCents, 0);
  const since = balance.entered && balance.startingOn > periodStart ? ` since ${formatDay(balance.startingOn)}` : "";
  const held = bucketPeriods.flatMap((period) => {
    const bucket = buckets.find((candidate) => candidate.id === period.bucketId);
    return bucket && period.accountId === shown.account.id ? [{ bucket, period }] : [];
  });
  return (
    <Panel title="Accounts & buckets" aside="at period end">
      {picker}
      <dl aria-label={shown.account.name} className="divide-y divide-line">
        <AccountLine label={owed ? "Owed at period end" : "Ending balance"} cents={balance.endingCents} strong />
        {beforeFirst ? (
          <AccountLine
            // An account with no balance entered simply has nothing in it yet: no first balance to name.
            label={balance.builtFromZero ? `${owed ? "Owed" : "Starting"} ${formatDay(periodStart)}` : `First balance, entered ${formatDay(balance.startingOn)}`}
            cents={balance.startingCents}
          />
        ) : (
          <AccountLine
            label={`${owed ? "Owed" : "Starting"} ${formatDay(balance.startingOn)}${balance.entered ? " · set by you" : ""}`}
            cents={balance.startingCents}
          />
        )}
        {!beforeFirst && (
          <>
            {/* A balance entered part-way through already holds what moved before it, so only later days are counted: say so. */}
            {/* An investment's growth is the user's own assumption, not money arriving, so it has a line of its own. */}
            <AccountLine label={`${owed ? "Charged" : "Coming in"}${since}`} cents={balance.inCents - assumed} sign="+" />
            {shown.account.type === "investment" && assumed > 0 && (
              <AccountLine label={`Assumed growth${since} · not a real gain`} cents={assumed} sign="+" />
            )}
            <AccountLine label={`${owed ? "Paid off" : "Going out"}${since}`} cents={balance.outCents} sign="−" />
            <AccountLine
              label={`${owed ? "Least owed" : "Lowest point"} · ${formatDay(balance.lowestOn)}${balance.lowBalance ? " · low balance" : ""}`}
              cents={balance.lowestCents}
              warn={balance.lowBalance}
            />
          </>
        )}
        {held.length > 0 && (
          <>
            <AccountLine label="In buckets" cents={balance.bucketsCents} sign="−" />
            <AccountLine label="Available after buckets" cents={balance.availableCents} />
          </>
        )}
      </dl>
      {/* A card's promotional rate running out this period, shown with the card. In words with an icon, never color alone. */}
      {promosEnding
        .filter((promo) => promo.accountId === shown.account.id)
        .map((promo) => (
          <p key={promo.promoId} className="mt-1 flex items-start gap-1.5 rounded-md border border-warn-line bg-warn px-2 py-1 text-sm font-semibold text-warn-ink">
            <TriangleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span data-amount className="min-w-0">
              {promo.label} ends {formatDay(promo.endDate)} with {formatCents(promo.remainingCents)} left
              {promo.deferredInterest && " · deferred interest"}
            </span>
          </p>
        ))}
      {/* Said in words and set apart, not just coloured: an overdrawn account is the thing to see first. */}
      {!owed && balance.lowestCents < 0 && (
        <p role="alert" className="mt-2 rounded-lg border-2 border-danger p-3 font-semibold text-danger">
          Overdrawn: {shown.account.name} drops to {formatCents(balance.lowestCents)} on {formatDay(balance.lowestOn)}
          {balance.endingCents < 0 ? ` and ends this pay period at ${formatCents(balance.endingCents)}` : ""}.
        </p>
      )}
      {!owed && balance.lowestCents >= 0 && balance.availableCents < 0 && (
        <p role="alert" className="mt-2 rounded-lg border-2 border-danger p-3 font-semibold text-danger">
          The buckets hold {formatCents(-balance.availableCents)} more than {shown.account.name} does. Take that much out of a bucket on the{" "}
          <Link to={`/accounts/${shown.account.id}`} className="underline">
            account's page
          </Link>
          , or update its balance.
        </p>
      )}
      {beforeFirst && !balance.builtFromZero && (
        <p className="mt-1 text-sm text-muted">
          Nothing is counted for {shown.account.name} before the first balance you entered, so this pay period shows that balance as it is.
        </p>
      )}
      {held.length > 0 && (
        <section aria-label={`Buckets in ${shown.account.name}`} className="mt-2 border-t border-line pt-2">
          <div className="flex items-baseline justify-between gap-3">
            <h3 className="text-sm font-semibold">Buckets</h3>
            <Link to={`/accounts/${shown.account.id}`} className="text-sm font-semibold text-accent-ink underline">
              Manage
            </Link>
          </div>
          <ul className="divide-y divide-line">
            {held.map(({ bucket, period }) => (
              <BucketRow
                key={bucket.id}
                bucket={bucket}
                period={period}
                periodEnd={periodEnd}
                // The recurring moves still putting money in it after this period: what "Stop saving" would end.
                saving={items.filter(
                  (item) =>
                    !item.archived &&
                    item.kind === "bucket_move" &&
                    item.bucketId === bucket.id &&
                    (item.schedule.endDate === null || item.schedule.endDate > periodEnd),
                )}
              />
            ))}
          </ul>
        </section>
      )}
    </Panel>
  );
}

/** One bucket at the end of the period: what it holds, what the period moved, and how far along its goal is. */
function BucketRow({
  bucket,
  period,
  periodEnd,
  saving,
}: {
  bucket: Bucket;
  period: BucketPeriod;
  periodEnd: string;
  saving: readonly Item[];
}) {
  const stop = useEndItems();
  const moved = period.endingCents - period.startingCents;
  const goal = bucket.goalCents;
  const goalReached = goal !== null && goal > 0 && period.endingCents >= goal;
  // Against its goal date, as things stand today (the same words as on the account's page).
  const pace = goalReached || bucket.goalDate === null ? null : bucket.goalProgress;
  const projected = pace === null ? "" : `With what is scheduled it will hold ${formatCents(pace.projectedCents)} by ${formatDate(bucket.goalDate ?? "")}.`;
  // Shown as at most full: a bucket can hold more than its goal.
  const reached = goal === null ? null : Math.max(0, Math.min(100, Math.round((period.endingCents * 100) / goal)));
  const detail = [
    moved === 0 ? null : `${moved > 0 ? "+" : "−"}${formatCents(Math.abs(moved))} this period`,
    goal === null ? null : `${reached}% of ${formatCents(goal)}`,
  ]
    .filter(Boolean)
    .join(" · ");
  return (
    <li className="py-1.5 lg:py-1">
      <div className="flex items-center gap-3">
        <Dot color={bucket.color} />
        <div className="min-w-0 flex-1 leading-tight">
          <div className="font-medium wrap-break-word">
            {bucket.name}
            {goalReached && (
              <span className="ml-2 inline-flex items-center gap-1 rounded-full bg-accent px-2 py-0.5 align-middle text-xs font-semibold text-on-accent">
                <PartyPopper aria-hidden className="size-3.5" />
                Goal reached
              </span>
            )}
          </div>
          {detail !== "" && (
            <div data-amount className="text-sm leading-tight text-muted tabular-nums">
              {detail}
            </div>
          )}
          {/* In words, never color alone; the fuller sentence is there on hover. */}
          {pace !== null && (
            <div data-amount title={projected} className="text-sm leading-tight tabular-nums">
              {pace.shortCents === 0 ? (
                <span className="font-semibold">On track</span>
              ) : (
                <span className="font-semibold text-danger">
                  Short{" "}
                  {pace.morePerPaycheckCents !== null
                    ? `${formatCents(pace.morePerPaycheckCents)} a paycheck`
                    : `by ${formatCents(pace.shortCents)}`}
                </span>
              )}
              <span className="text-muted"> for {formatDate(bucket.goalDate ?? "")}</span>
            </div>
          )}
          {/* Only where it would change something: a move still running, in this period or a later one. */}
          {goalReached && saving.length > 0 && periodEnd >= todayIso() && (
            <div className="mt-1">
              <button
                type="button"
                disabled={stop.isPending}
                onClick={() => stop.mutate({ items: saving, endDate: periodEnd, effectiveFrom: todayIso() })}
                aria-label={`Stop saving into ${bucket.name}`}
                title={`Ends ${saving.map((item) => item.name).join(", ")} after this pay period. You can undo it straight afterwards.`}
                className="min-h-11 rounded-lg border border-line px-3 text-sm font-semibold lg:min-h-8"
              >
                {stop.isPending ? "Stopping…" : "Stop saving"}
              </button>
              {stop.error && (
                <p role="alert" className="text-sm text-danger">
                  {stop.error.message}
                </p>
              )}
            </div>
          )}
        </div>
        <span data-amount className="shrink-0 font-semibold tabular-nums">
          {formatCents(period.endingCents)}
        </span>
      </div>
      {reached !== null && (
        // The percentage is in the text above; the bar only draws it.
        <div aria-hidden className="mt-1 h-1.5 overflow-hidden rounded-full bg-bg">
          <div className="h-full rounded-full" style={{ width: `${reached}%`, backgroundColor: bucket.color }} />
        </div>
      )}
    </li>
  );
}

function AccountLine({
  label,
  cents,
  sign = "",
  strong = false,
  warn = false,
}: {
  label: string;
  cents: number;
  sign?: string;
  strong?: boolean;
  /** The label says what is wrong; this only adds the warning color. */
  warn?: boolean;
}) {
  return (
    <div className={`flex items-center justify-between gap-3 py-1.5 ${strong ? "text-lg font-bold" : ""}`}>
      <dt className={warn ? "font-semibold text-danger" : strong ? "" : "text-muted"}>{label}</dt>
      <dd data-amount className="tabular-nums">
        {sign}
        {formatCents(cents)}
      </dd>
    </div>
  );
}
