import type { ReactNode } from "react";
import { Link, useParams } from "react-router";
import { useCameFrom } from "../lib/cameFrom";
import { type Account, useAccounts } from "../api/accounts";
import { useCategories, useTags } from "../api/categories";
import { type Entry, type EntryDetail, type Reimbursement, useEntry, useSetReimbursement } from "../api/entries";
import { type FlaggedPayment, paymentPath } from "../api/reimbursements";
import { useAddItem } from "../components/add/AddItem";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { categoryLabel } from "../components/categories/LabelFields";
import { EditButton, EditPageButton } from "../components/EditButton";
import { TagChips } from "../components/expenses/TagChips";
import { Receipts } from "../components/files/Receipts";
import { ComingBackCard } from "../components/expenses/ComingBackCard";
import { ReimbursementCard } from "../components/expenses/ReimbursementCard";
import { PeriodLink } from "../components/period/PeriodLink";
import { DETAIL_HEADER, PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";
import { HelpLink } from "../components/help/HelpLink";

const KIND_NAMES: Record<Entry["kind"], string> = { money_out: "Expense", money_in: "Money in", transfer: "Transfer" };

/**
 * One expense, laid out like an account's page (design/expenses.png, as a page of its own):
 * the amount first, then a card per topic — where its reimbursement stands, what it is, and its
 * receipts and documents. The Expenses screen is the overview; this is where the detail lives.
 */
export function ExpenseDetailPage() {
  const id = Number(useParams().id);
  const back = useCameFrom({ to: "/expenses", label: "Expenses & receipts" });
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
            <Link to={back.to} className="text-accent-ink underline">
              {back.label}
            </Link>{" "}
            / {kindName}
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} min-w-0 truncate`}>{entry.payee ?? entry.description}</h1>
            <HelpLink />
          </div>
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
            {entry.reimbursement && <ExpenseReimbursement detail={detail.data} reimbursement={entry.reimbursement} />}
            {/* Still to come back: how much, where and when it is planned (Rachel, 2026-10-25). */}
            {entry.reimbursement && (entry.reimbursement.status === "to_submit" || entry.reimbursement.status === "submitted") && (
              <ComingBackCard entry={entry} reimbursement={entry.reimbursement} />
            )}
            {detail.data.reimburses && <PaysBackCard expense={detail.data.reimburses} />}
            {detail.data.reimbursesPayment && <PaysBackPaymentCard payment={detail.data.reimbursesPayment} />}
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

/** An expense's Reimbursement card: its changes are saved on the expense. */
function ExpenseReimbursement({ detail, reimbursement }: { detail: EntryDetail; reimbursement: Reimbursement }) {
  const { entry, paybacks, movesOut } = detail;
  const save = useSetReimbursement(entry.id);
  const { status, submittedOn, to } = reimbursement;
  return (
    <ReimbursementCard
      reimbursement={reimbursement}
      target={entry}
      paybacks={paybacks}
      movesOut={movesOut}
      onSet={(change) => save.mutate({ status, submittedOn, to, ...change })}
      onNotPaidBack={() => save.mutate({ status: "none", submittedOn, to })}
      saving={save.isPending}
      error={save.error?.message}
    />
  );
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

/** For money coming in: the flagged recurring payment it pays back, with the way to its page. */
function PaysBackPaymentCard({ payment }: { payment: FlaggedPayment }) {
  return (
    <Card title="Pays back">
      <Link
        to={paymentPath(payment.itemId, payment.scheduledDate)}
        className="-mx-2 flex min-h-11 cursor-pointer items-center gap-3 rounded-lg px-2 py-2 hover:bg-bg/60"
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate font-semibold">{payment.name}</span>
          <span className="block text-sm text-muted">{formatDate(payment.date)} · a recurring payment</span>
        </span>
        <Amount cents={payment.amountCents} className="shrink-0 font-semibold" />
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
