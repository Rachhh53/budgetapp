import { ChevronLeft, ChevronRight } from "lucide-react";
import { EditButton } from "../components/EditButton";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { type ReactNode, useEffect, useId, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type Bucket, useBuckets } from "../api/buckets";
import { type Category, useCategories, useTags } from "../api/categories";
import { type Entry, useEditEntry } from "../api/entries";
import { type PayPeriod, type Paycheck, useEditPaycheck, usePayPeriods } from "../api/income";
import { type Item, useChangeItemAmount, useItems } from "../api/items";
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
import { HideAmountsButton } from "../components/HideAmounts";
import { ItemForm } from "../components/items/ItemForm";
import { ConfirmationCode } from "../components/period/ConfirmationCode";
import { type EditScope, InlineAmount } from "../components/period/InlineAmount";
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
 *
 * The credit cards panel arrives in Phase 5.
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

  const [editingItem, setEditingItem] = useState<Item>();

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
    categories: categories.data,
    items: items.data,
    editItem: setEditingItem,
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

      {primary && primaryAccount ? (
        <Figures balance={primary} account={primaryAccount} />
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
          <IncomePanel paychecks={detail.paychecks} moneyIn={lists.moneyIn} interest={detail.interest} context={context} />
          <AutoPanel occurrences={lists.auto} context={context} />
          <BudgetsPanel occurrences={lists.budgets} context={context} />
        </div>
        <div className="flex flex-col gap-3 lg:order-3 lg:min-h-0 lg:overflow-y-auto">
          <AccountsPanel
            balances={detail.accounts}
            accounts={accounts.data}
            buckets={buckets.data}
            bucketPeriods={detail.buckets}
            primaryAccountId={detail.primaryAccountId}
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
  categories: readonly Category[];
  items: readonly Item[];
  editItem: (item: Item) => void;
}

/** ← and → move between periods, unless the keys are being used to type or to work a dialog. */
function useArrowKeys(previous: string | undefined, next: string | undefined) {
  const navigate = useNavigate();
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
        void navigate(`/periods/${to}`);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [navigate, previous, next]);
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
  if (to === undefined) {
    // Kept in place, dimmed, so the title does not jump when there is nowhere further to go.
    return (
      <span aria-hidden className={`${box} opacity-40`}>
        <Icon className="size-5" />
      </span>
    );
  }
  return (
    <Link to={`/periods/${to.start}`} aria-label={`${label}, ${formatDay(to.start)} to ${formatDay(to.end)}`} className={box}>
      <Icon aria-hidden className="size-5" />
    </Link>
  );
}

/** Ending balances, period by period, so a tight one ahead is seen coming (the strip in the mockup). */
function PeriodStrip({ periods, selected }: { periods: readonly PayPeriod[]; selected: string }) {
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
              to={`/periods/${period.start}`}
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
function Figures({ balance, account }: { balance: AccountPeriod; account: Account }) {
  // Whole numbers without the $, as the mockup writes the sum under the ending balance.
  const plain = (cents: number) => formatCents(cents).replace("$", "");
  return (
    <dl className="grid grid-cols-2 gap-3 lg:grid-cols-5">
      <Figure
        label="Starting balance"
        cents={balance.startingCents}
        note={`${account.name} · ${formatDay(balance.startingOn)}${balance.entered ? " · set by you" : ""}`}
        className="order-2 lg:order-1"
      />
      <Figure
        label="Coming in"
        cents={balance.inCents}
        sign="+"
        note="Paychecks and money in"
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

function Figure({
  label,
  cents,
  sign = "",
  note,
  highlight = false,
  warn = false,
  className = "",
}: {
  label: string;
  cents: number;
  sign?: string;
  note: string;
  /** The number the screen is about: outlined, and largest on a phone. */
  highlight?: boolean;
  /** The note is a warning (it says so in words too): shown in the warning color. */
  warn?: boolean;
  className?: string;
}) {
  return (
    <div className={`rounded-card bg-surface px-4 py-2.5 short:py-1.5 ${highlight ? "border-2 border-accent" : "border border-line"} ${className}`}>
      <dt className="text-sm text-muted">{label}</dt>
      <dd>
        <span data-amount className={`block font-bold tabular-nums ${highlight ? "text-3xl lg:text-2xl" : "text-xl lg:text-2xl"} lg:short:text-xl`}>
          {sign}
          {formatCents(cents)}
        </span>
        <span data-amount title={note} className={`block text-sm tabular-nums lg:truncate ${warn ? "font-semibold text-danger" : "text-muted"}`}>
          {note}
        </span>
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

/** A row: what it is on the left, the amount on the right, in a column that lines up. */
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

/** A recurring item's name, with the button that opens it for a "this and all future" change. */
function ItemName({ occurrence, context }: { occurrence: Occurrence; context: RowContext }) {
  const item = context.items.find((candidate) => candidate.id === occurrence.itemId);
  if (item === undefined) {
    return <>{occurrence.name}</>;
  }
  return (
    <span className="flex items-center gap-1">
      <span className="min-w-0">{occurrence.name}</span>
      <EditButton name={occurrence.name} onClick={() => context.editItem(item)} />
    </span>
  );
}

/** The amount of one occurrence, typed over in place: just this one, or this and all future. */
function OccurrenceAmount({ occurrence, context }: { occurrence: Occurrence; context: RowContext }) {
  const editOne = useEditOccurrence();
  const changeFuture = useChangeItemAmount();
  const item = context.items.find((candidate) => candidate.id === occurrence.itemId);
  // A yearly total is shared out by the app, so one occurrence's amount cannot stand for the item's.
  const canChangeFuture = item !== undefined && item.amountMode === "per_occurrence";

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

/** A one-off's description, with the button that opens it in the + Add item form. */
function EntryName({ entry }: { entry: Entry }) {
  const { editEntry } = useAddItem();
  return (
    <span className="flex items-center gap-1">
      <span className="min-w-0">{entry.description}</span>
      <EditButton name={entry.description} onClick={() => editEntry(entry)} />
    </span>
  );
}

function Dot({ color }: { color: string | undefined }) {
  return <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: color ?? "var(--border)" }} />;
}

function categoryOf(categoryId: number | null, context: RowContext): Category | undefined {
  return context.categories.find((category) => category.id === categoryId);
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
 * "Checking", "Checking → Savings", "Checking → Savings · Emergency fund bucket": the account
 * an occurrence is paid from, and where its money ends up when it stays the user's.
 */
function whereItGoes(occurrence: Occurrence, context: RowContext): string {
  const from = context.accountName(occurrence.payFromAccountId);
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
              detail={`${formatDay(occurrence.payBy)} · ${whereItGoes(occurrence, context)}`}
              amount={<OccurrenceAmount occurrence={occurrence} context={context} />}
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
    <Panel title="Spending budgets" aside="this paycheck">
      {occurrences.length === 0 ? (
        <Empty>No budgets this period. Add one as a recurring item of the Budget type.</Empty>
      ) : (
        <>
          <ul className="divide-y divide-line">
            {occurrences.map((occurrence) => (
              <Row
                key={`${occurrence.itemId}-${occurrence.scheduledDate}`}
                lead={<Dot color={categoryOf(occurrence.categoryId, context)?.color} />}
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
              lead={<Dot color={categoryOf(entry.categoryId, context)?.color} />}
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
  const category = categoryOf(entry.categoryId, context);
  return ["One-off", formatDay(entry.date), where, category?.name].filter(Boolean).join(" · ");
}

/** A bill paid by hand: tick it when it is paid, and keep the confirmation code with it. */
function ManualRow({ occurrence, carriedOver, context }: { occurrence: Occurrence; carriedOver: boolean; context: RowContext }) {
  const edit = useEditOccurrence();
  const checkboxId = useId();

  const due = `Due ${formatDay(occurrence.dueDate)}`;
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
            </span>
          )}
          <ConfirmationCode
            name={occurrence.name}
            code={occurrence.confirmationCode}
            onSave={(confirmationCode) => edit.mutate({ occurrence, edit: { confirmationCode } })}
          />
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
 * Where every account stands at the end of the period, and what its buckets hold: one tab
 * per account with a balance.
 */
function AccountsPanel({
  balances,
  accounts,
  buckets,
  bucketPeriods,
  primaryAccountId,
}: {
  balances: readonly AccountPeriod[];
  accounts: readonly Account[];
  buckets: readonly Bucket[];
  bucketPeriods: readonly BucketPeriod[];
  primaryAccountId: number | null;
}) {
  const id = useId();
  const known = balances.flatMap((balance) => {
    const account = accounts.find((candidate) => candidate.id === balance.accountId);
    return account ? [{ account, balance }] : [];
  });
  const [chosen, setChosen] = useState<number | null>(null);
  const shown = known.find((tab) => tab.account.id === (chosen ?? primaryAccountId)) ?? known[0];

  if (shown === undefined) {
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

  const owed = OWED.includes(shown.account.type);
  const { balance } = shown;
  const held = bucketPeriods.flatMap((period) => {
    const bucket = buckets.find((candidate) => candidate.id === period.bucketId);
    return bucket && period.accountId === shown.account.id ? [{ bucket, period }] : [];
  });
  return (
    <Panel title="Accounts & buckets" aside="at period end">
      <div role="tablist" aria-label="Accounts" className="mb-2 flex gap-1 overflow-x-auto rounded-lg bg-bg p-1">
        {known.map(({ account }) => (
          <button
            key={account.id}
            type="button"
            role="tab"
            id={`${id}-tab-${account.id}`}
            aria-selected={account.id === shown.account.id}
            aria-controls={`${id}-panel`}
            onClick={() => setChosen(account.id)}
            className={`min-h-11 shrink-0 rounded-md px-3 text-sm lg:min-h-9 ${
              account.id === shown.account.id ? "bg-surface font-semibold shadow-sm" : "text-muted"
            }`}
          >
            {account.name}
          </button>
        ))}
      </div>
      <dl id={`${id}-panel`} role="tabpanel" aria-labelledby={`${id}-tab-${shown.account.id}`} className="divide-y divide-line">
        <AccountLine label={owed ? "Owed at period end" : "Ending balance"} cents={balance.endingCents} strong />
        <AccountLine
          label={`${owed ? "Owed" : "Starting"} ${formatDay(balance.startingOn)}${balance.entered ? " · set by you" : ""}`}
          cents={balance.startingCents}
        />
        <AccountLine label={owed ? "Charged" : "Coming in"} cents={balance.inCents} sign="+" />
        <AccountLine label={owed ? "Paid off" : "Going out"} cents={balance.outCents} sign="−" />
        <AccountLine
          label={`${owed ? "Least owed" : "Lowest point"} · ${formatDay(balance.lowestOn)}${balance.lowBalance ? " · low balance" : ""}`}
          cents={balance.lowestCents}
          warn={balance.lowBalance}
        />
        {held.length > 0 && (
          <>
            <AccountLine label="In buckets" cents={balance.bucketsCents} sign="−" />
            <AccountLine label="Available after buckets" cents={balance.availableCents} />
          </>
        )}
      </dl>
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
              <BucketRow key={bucket.id} bucket={bucket} period={period} />
            ))}
          </ul>
        </section>
      )}
    </Panel>
  );
}

/** One bucket at the end of the period: what it holds, what the period moved, and how far along its goal is. */
function BucketRow({ bucket, period }: { bucket: Bucket; period: BucketPeriod }) {
  const moved = period.endingCents - period.startingCents;
  const goal = bucket.goalCents;
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
          <div className="font-medium wrap-break-word">{bucket.name}</div>
          {detail !== "" && (
            <div data-amount className="text-sm leading-tight text-muted tabular-nums">
              {detail}
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
