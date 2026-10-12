import { CalendarDays, CircleCheck, Clock, type LucideIcon, Minus, Send } from "lucide-react";
import { type ReactNode, useId, useState } from "react";
import { Link, useParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { useCategories, useTags } from "../api/categories";
import {
  type Entry,
  type EntryDetail,
  REIMBURSEMENT_LABELS,
  type Reimbursement,
  type ReimbursementStatus,
  useEntry,
  useNames,
  useSetReimbursement,
} from "../api/entries";
import { usePayPeriodsBetween } from "../api/periods";
import { useAddItem } from "../components/add/AddItem";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { categoryLabel } from "../components/categories/LabelFields";
import { EditButton, EditPageButton } from "../components/EditButton";
import { TagChips } from "../components/expenses/TagChips";
import { Receipts } from "../components/files/Receipts";
import { TextPicker } from "../components/Picker";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { addDays, formatDate, formatDay, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";

const KIND_NAMES: Record<Entry["kind"], string> = { money_out: "Expense", money_in: "Money in", transfer: "Transfer" };

const STATUS_ICONS: Record<ReimbursementStatus, LucideIcon> = {
  none: Minus,
  to_submit: Send,
  submitted: Clock,
  reimbursed: CircleCheck,
};

/**
 * One expense, laid out like an account's page (design/expenses.png, as a page of its own):
 * the amount first, then a card per topic — where its reimbursement stands, what it is, and its
 * receipts and documents. The Expenses screen is the overview; this is where the detail lives.
 */
export function ExpenseDetailPage() {
  const id = Number(useParams().id);
  const detail = useEntry(id);
  const accounts = useAccounts();
  const { editEntry } = useAddItem();

  if (detail.isPending && Number.isInteger(id)) {
    return <p className="text-muted">Loading…</p>;
  }
  if (!detail.data) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Expenses &amp; receipts</h1>
        <Card>
          <p role="alert" className="font-medium">
            {detail.error && detail.error.message !== "We couldn't find that."
              ? detail.error.message
              : "We couldn't find that expense. It may have been removed."}
          </p>
          <Link to="/expenses" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all expenses
          </Link>
        </Card>
      </>
    );
  }

  const { entry } = detail.data;
  const account = accounts.data?.find((candidate) => candidate.id === entry.accountId);
  const kindName = KIND_NAMES[entry.kind];

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/expenses" className="text-accent-ink underline">
              Expenses &amp; receipts
            </Link>{" "}
            / {kindName}
          </p>
          <h1 className={`${PAGE_TITLE} truncate`}>{entry.payee ?? entry.description}</h1>
          <p className="text-muted">
            {formatDate(entry.date)}
            {entry.payee !== null && ` · ${entry.description}`}
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <PeriodLink date={entry.date} />
          <EditPageButton label={`Edit ${kindName.toLowerCase()}`} onClick={() => editEntry(entry)} />
        </div>
      </div>

      <div className="space-y-4">
        <Figures entry={entry} account={account} />

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div className="space-y-4">
            {entry.reimbursement && <ReimbursementCard detail={detail.data} reimbursement={entry.reimbursement} />}
            {detail.data.reimburses && <PaysBackCard expense={detail.data.reimburses} />}
            <AboutCard entry={entry} onEdit={() => editEntry(entry, "tags")} />
          </div>
          <Card title="Receipts & documents">
            <Receipts owner={{ type: "entry", entryId: entry.id }} />
          </Card>
        </div>
      </div>
    </>
  );
}

/** The figures an account page opens with, for an expense: what it cost, and what has come back. */
function Figures({ entry, account }: { entry: Entry; account: Account | undefined }) {
  const owes = account?.type === "credit_card" || account?.type === "loan";
  const accountName = account?.name ?? "an account that was removed";
  const repaid = entry.reimbursement;
  const tracked = repaid !== null && (repaid.status !== "none" || repaid.paidBackCents > 0);

  return (
    <Card>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className={tracked ? "" : "sm:col-span-2"}>
          <p className="text-muted">Amount</p>
          <Amount cents={entry.amountCents} estimate={entry.estimate} className="-ml-2 text-3xl font-bold" />
          <p className="text-muted">
            {entry.kind === "money_in" ? `into ${accountName}` : `paid from ${accountName}`}
            {entry.kind === "money_out" && owes && ", so it adds to what is owed there"}
            {entry.estimate && " · an estimate until you type the real amount"}
          </p>
        </div>
        {tracked && repaid && (
          <>
            <Figure label="Paid back so far" cents={repaid.reimbursedCents}>
              {repaid.status === "reimbursed" && repaid.paidBackCents === 0 ? "marked as reimbursed" : "has come back"}
            </Figure>
            <Figure label="Still owed to you" cents={repaid.owedCents}>
              {repaid.status === "to_submit" ? "not sent in yet" : repaid.owedCents > 0 ? "sent in, still to come" : "nothing more to come"}
            </Figure>
          </>
        )}
      </div>
    </Card>
  );
}

function Figure({ label, cents, children }: { label: string; cents: number; children: string }) {
  return (
    <div>
      <p className="text-muted">{label}</p>
      <p data-amount className="text-2xl font-bold tabular-nums">
        {formatCents(cents)}
      </p>
      <p className="text-muted">{children}</p>
    </div>
  );
}

/**
 * Where the expense stands on being paid back. Each control saves as it is changed, like a
 * paid checkbox, and Undo takes any of it back: there is nothing here to forget to save.
 */
function ReimbursementCard({ detail, reimbursement }: { detail: EntryDetail; reimbursement: Reimbursement }) {
  const { entry, paybacks } = detail;
  const save = useSetReimbursement(entry.id);
  const names = useNames();
  const { add, editEntry } = useAddItem();
  const dateId = useId();
  const { status, submittedOn, to } = reimbursement;
  const sent = status === "submitted" || status === "reimbursed";

  const set = (change: Partial<{ status: ReimbursementStatus; submittedOn: string | null; to: string | null }>) =>
    save.mutate({ status, submittedOn, to, ...change });
  // The ways back, and out, are a click further away than the way forward.
  const [changing, setChanging] = useState(false);
  const step = (change: Parameters<typeof set>[0]) => {
    setChanging(false);
    set(change);
  };

  return (
    <Card title="Reimbursement">
      <div className="space-y-4">
        {status === "none" ? (
          <>
            <p className="text-muted">{summary(reimbursement)}</p>
            <button type="button" onClick={() => set({ status: "to_submit" })} disabled={save.isPending} className={BUTTON_SECONDARY}>
              I'll be paid back for this
            </button>
          </>
        ) : (
          <>
            {/* How far along it is, to look at; the one button under it is the next thing to do. */}
            <Steps reimbursement={reimbursement} />
            <p className="text-muted">{summary(reimbursement)}</p>
            <div className="flex flex-wrap items-center gap-2">
              {status === "to_submit" && (
                // Marking it sent in means today; the day can be changed below.
                <button
                  type="button"
                  onClick={() => set({ status: "submitted", submittedOn: submittedOn ?? todayIso() })}
                  disabled={save.isPending}
                  className={BUTTON_PRIMARY}
                >
                  Mark as submitted
                </button>
              )}
              {status === "submitted" && (
                <button type="button" onClick={() => set({ status: "reimbursed" })} disabled={save.isPending} className={BUTTON_PRIMARY}>
                  Mark as reimbursed
                </button>
              )}
              <button type="button" onClick={() => setChanging(!changing)} aria-expanded={changing} className={BUTTON_LINK}>
                Change
              </button>
            </div>
            {changing && (
              <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                {status === "submitted" && (
                  <button
                    type="button"
                    onClick={() => step({ status: "to_submit", submittedOn: null })}
                    disabled={save.isPending}
                    className={BUTTON_SECONDARY}
                  >
                    Back to To submit
                  </button>
                )}
                {status === "reimbursed" && (
                  <button type="button" onClick={() => step({ status: "submitted" })} disabled={save.isPending} className={BUTTON_SECONDARY}>
                    Back to Submitted
                  </button>
                )}
                <button type="button" onClick={() => step({ status: "none" })} disabled={save.isPending} className={BUTTON_SECONDARY}>
                  I won't be paid back for this
                </button>
              </div>
            )}
          </>
        )}

        {status !== "none" && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <TextPicker
              label="Who pays it back (optional)"
              noun="name"
              placeholder="e.g. HSA, work, insurance"
              suggestions={names.data?.reimbursers ?? []}
              value={to ?? ""}
              onChange={(text) => set({ to: text === "" ? null : text })}
            />
            {sent && (
              <div>
                <label htmlFor={dateId} className={LABEL}>
                  Sent in on (optional)
                </label>
                <input
                  id={dateId}
                  type="date"
                  value={submittedOn ?? ""}
                  // A date half typed is not a date yet: only a whole one, or none, is saved.
                  onChange={(event) => set({ submittedOn: event.target.value === "" ? null : event.target.value })}
                  className={INPUT}
                />
              </div>
            )}
          </div>
        )}
        {save.error && (
          <p role="alert" className="text-danger">
            {save.error.message}
          </p>
        )}

        {(status !== "none" || paybacks.length > 0) && (
          <div className="border-t border-line pt-3">
            <h3 className="font-semibold">Money that came back</h3>
            {paybacks.length === 0 ? (
              <p className={HINT}>Nothing yet. When it arrives, add it here and it is counted against this expense.</p>
            ) : (
              <ul className="divide-y divide-line">
                {paybacks.map((payback) => (
                  <li key={payback.id} onClick={clickRowToEdit} className={`-mx-2 flex min-h-11 items-center gap-3 rounded-lg px-2 py-1 ${ROW_OPENS}`}>
                    <span className="shrink-0 text-muted">{formatDate(payback.date)}</span>
                    <button
                      type="button"
                      data-edit
                      onClick={() => editEntry(payback)}
                      aria-label={`Edit ${payback.description}`}
                      title={`Edit ${payback.description}`}
                      className="min-w-0 flex-1 cursor-pointer truncate rounded-sm text-left font-medium"
                    >
                      {payback.description}
                    </button>
                    <Amount cents={payback.amountCents} estimate={payback.estimate} className="shrink-0 font-semibold" />
                  </li>
                ))}
              </ul>
            )}
            <button type="button" onClick={() => add("money_in", undefined, entry)} className={`${BUTTON_SECONDARY} mt-3`}>
              + Add money that came back
            </button>
          </div>
        )}
      </div>
    </Card>
  );
}

/** Longer than any pay period, so looking this far back from a day always reaches the period that holds it. */
const LONGEST_PERIOD_DAYS = 45;

/**
 * The way to the pay period this falls in (Rachel, 2026-10-12), to see it among everything else
 * that came in and went out then. Not there when no pay period holds its date: before the first
 * paycheck, say, or while no income has been set up.
 */
function PeriodLink({ date }: { date: string }) {
  const periods = usePayPeriodsBetween(addDays(date, -LONGEST_PERIOD_DAYS), date);
  const period = periods.data?.periods.find((candidate) => candidate.start <= date && date <= candidate.end);
  if (period === undefined) {
    return null;
  }
  return (
    <Link
      to={`/periods/${period.start}`}
      title={`Open the ${formatDay(period.start)} – ${formatDay(period.end)} pay period`}
      className={`${BUTTON_SECONDARY} inline-flex items-center justify-center gap-2`}
    >
      <CalendarDays aria-hidden className="size-4" />
      See its pay period
    </Link>
  );
}

/** The three stops on the way to being paid back, in order. */
const STEPS = ["to_submit", "submitted", "reimbursed"] as const satisfies readonly ReimbursementStatus[];

/**
 * How far along being paid back it is, as a line of three stops (Rachel, 2026-10-12: a row of
 * choices to click "look like tabs", and did not say which one it was on). Only shown, never
 * clicked. Each stop has its icon and says in words whether it is done, so nothing rests on color.
 */
function Steps({ reimbursement }: { reimbursement: Reimbursement }) {
  const at = STEPS.findIndex((step) => step === reimbursement.status);
  const under = (step: (typeof STEPS)[number], position: number): string => {
    if (position > at) {
      return "Not yet";
    }
    switch (step) {
      case "to_submit":
        return position === at ? "Not sent in yet" : "Done";
      case "submitted":
        return reimbursement.submittedOn === null ? "Sent in" : formatDate(reimbursement.submittedOn);
      case "reimbursed":
        return "Paid back";
    }
  };
  return (
    <ol aria-label="How far along it is" className="grid grid-cols-3">
      {STEPS.map((step, position) => {
        const Icon = STATUS_ICONS[step];
        const reached = position <= at;
        return (
          <li key={step} aria-current={position === at ? "step" : undefined} className="relative flex min-w-0 flex-col items-center text-center">
            {/* The line from the stop before, drawn behind the two circles it joins. */}
            {position > 0 && <span aria-hidden className={`absolute top-5 right-1/2 h-0.5 w-full ${reached ? "bg-accent" : "bg-line"}`} />}
            <span
              className={`relative flex size-10 items-center justify-center rounded-full border-2 ${
                reached ? "border-accent bg-accent text-on-accent" : "border-line bg-surface text-muted"
              } ${position === at ? "ring-2 ring-accent ring-offset-2 ring-offset-surface" : ""}`}
            >
              <Icon aria-hidden className="size-5" />
            </span>
            <span className={`mt-2 text-sm ${position === at ? "font-bold" : "font-medium"} ${reached ? "" : "text-muted"}`}>
              {REIMBURSEMENT_LABELS[step]}
            </span>
            <span className="text-sm text-muted">{under(step, position)}</span>
          </li>
        );
      })}
    </ol>
  );
}

/** Where it stands, in a sentence: "Submitted to HSA on Sep 29, 2026 · $25.00 still to come." */
function summary({ status, submittedOn, to, owedCents, paidBackCents }: Reimbursement): string {
  const whom = to === null ? "" : ` to ${to}`;
  const when = submittedOn === null ? "" : ` on ${formatDate(submittedOn)}`;
  switch (status) {
    case "none":
      return paidBackCents > 0
        ? `Nobody is expected to pay this back, though ${formatCents(paidBackCents)} has come in for it.`
        : "Nobody pays this back.";
    case "to_submit":
      return `Still to send in${whom} · ${formatCents(owedCents)} to ask for.`;
    case "submitted":
      return `Submitted${whom}${when} · ${owedCents > 0 ? `${formatCents(owedCents)} still to come` : "all of it has come back"}.`;
    case "reimbursed":
      return `Reimbursed${to === null ? "" : ` by ${to}`}${submittedOn === null ? "" : ` · sent in on ${formatDate(submittedOn)}`}.`;
  }
}

/** For money coming in: the expense it pays back, with the way to its page. */
function PaysBackCard({ expense }: { expense: Entry }) {
  return (
    <Card title="Pays back">
      <Link to={`/expenses/${expense.id}`} className="-mx-2 flex min-h-11 cursor-pointer items-center gap-3 rounded-lg px-2 py-2 hover:bg-bg/60">
        <span className="min-w-0 flex-1">
          <span className="block truncate font-semibold">{expense.payee ?? expense.description}</span>
          <span className="block text-sm text-muted">{formatDate(expense.date)}</span>
        </span>
        <Amount cents={expense.amountCents} estimate={expense.estimate} className="shrink-0 font-semibold" />
      </Link>
    </Card>
  );
}

/** What the entry is: its tags, category, note and check number. Changed from the Edit form. */
function AboutCard({ entry, onEdit }: { entry: Entry; onEdit: () => void }) {
  const categories = useCategories();
  const tags = useTags();
  const category = categories.data?.find((candidate) => candidate.id === entry.categoryId);

  return (
    <Card
      title="Details"
      action={<EditButton name="details" onClick={onEdit} />}
    >
      <dl className="grid gap-4 sm:grid-cols-2">
        <Fact term="Tags">
          {entry.tagIds.length > 0 ? <TagChips tagIds={entry.tagIds} tags={tags.data ?? []} /> : <span className="text-muted">None</span>}
        </Fact>
        <Fact term="Category">
          {category && categories.data ? categoryLabel(category, categories.data) : <span className="text-muted">None</span>}
        </Fact>
        <Fact term="Note">{entry.note ?? <span className="text-muted">None</span>}</Fact>
        {entry.checkNumber !== null && <Fact term="Check number">{entry.checkNumber}</Fact>}
      </dl>
    </Card>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted">{term}</dt>
      <dd className="mt-0.5 font-medium wrap-break-word">{children}</dd>
    </div>
  );
}
