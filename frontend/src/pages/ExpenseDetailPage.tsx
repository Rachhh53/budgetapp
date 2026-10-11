import { CircleCheck, Clock, type LucideIcon, Minus, Send } from "lucide-react";
import { type ReactNode, useId } from "react";
import { Link, useParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { useCategories, useTags } from "../api/categories";
import {
  type Entry,
  type EntryDetail,
  REIMBURSEMENT_LABELS,
  REIMBURSEMENT_STATUSES,
  type Reimbursement,
  type ReimbursementStatus,
  useEntry,
  useNames,
  useSetReimbursement,
} from "../api/entries";
import { useAddItem } from "../components/add/AddItem";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { categoryLabel } from "../components/categories/LabelFields";
import { EditPageButton } from "../components/EditButton";
import { TagChips } from "../components/expenses/TagChips";
import { Receipts } from "../components/files/Receipts";
import { TextPicker } from "../components/Picker";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { Segmented } from "../components/Segmented";
import { BUTTON_SECONDARY, DETAIL_HEADER, HINT, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { formatDate, todayIso } from "../lib/dates";
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
          <EditPageButton label={`Edit ${kindName.toLowerCase()}`} onClick={() => editEntry(entry)} />
        </div>
      </div>

      <div className="space-y-4">
        <Figures entry={entry} account={account} />

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div className="space-y-4">
            {entry.reimbursement && <ReimbursementCard detail={detail.data} reimbursement={entry.reimbursement} />}
            {detail.data.reimburses && <PaysBackCard expense={detail.data.reimburses} />}
            <AboutCard entry={entry} onEdit={() => editEntry(entry)} />
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

  return (
    <Card title="Reimbursement">
      <div className="space-y-4">
        <Segmented
          legend="Where it stands"
          options={REIMBURSEMENT_STATUSES.map((value) => ({ value, label: REIMBURSEMENT_LABELS[value], icon: STATUS_ICONS[value] }))}
          value={status}
          // Marking it sent in with no day given means today; the day can be changed below.
          onChange={(next) => set({ status: next, submittedOn: next === "submitted" && submittedOn === null ? todayIso() : submittedOn })}
        />
        <p className="text-muted">{summary(reimbursement)}</p>

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

/** Where it stands, in a sentence: "Submitted to HSA on Sep 29, 2026 · $25.00 still to come." */
function summary({ status, submittedOn, to, owedCents, paidBackCents }: Reimbursement): string {
  const whom = to === null ? "" : ` to ${to}`;
  const when = submittedOn === null ? "" : ` on ${formatDate(submittedOn)}`;
  switch (status) {
    case "none":
      return paidBackCents > 0
        ? `Nobody is expected to pay this back, though ${formatCents(paidBackCents)} has come in for it.`
        : "Nobody pays this back. Choose To submit if someone will.";
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
      action={
        <button type="button" onClick={onEdit} aria-label="Edit details" className="min-h-11 rounded-lg px-2 font-semibold text-accent-ink">
          Edit
        </button>
      }
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
