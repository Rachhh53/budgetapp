import { Paperclip } from "lucide-react";
import { useId, useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type Category, type Tag, useCategories, useTags } from "../api/categories";
import { type Entry, REIMBURSEMENT_LABELS, REIMBURSEMENT_STATUSES, type ReimbursementStatus, useEntries, useExpenseYears } from "../api/entries";
import { useAddItem } from "../components/add/AddItem";
import { Amount, EstimateKey } from "../components/Amount";
import { Card } from "../components/Card";
import { FilterPills } from "../components/FilterPills";
import { ReimbursementBadge } from "../components/expenses/ReimbursementBadge";
import { TagChips } from "../components/expenses/TagChips";
import { BUTTON_PRIMARY, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { formatDay, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";
import { HelpLink } from "../components/help/HelpLink";

/** The columns of the list from `md` up; below that each expense is a small stack. */
const COLUMNS = "md:grid md:grid-cols-[4.5rem_minmax(0,1.3fr)_minmax(0,1.2fr)_minmax(0,1fr)_7.5rem_3rem_7.5rem] md:items-center md:gap-3";

/**
 * A row that opens its expense: the hand cursor and the highlight say so, as on every list (see `ROW_LINK`).
 * Its own classes because this row is a stack on a phone and a grid from `md`, never a flex row.
 */
const ROW = "-mx-2 block min-h-11 cursor-pointer rounded-lg px-2 py-3 hover:bg-bg/60";

type Standing = ReimbursementStatus | "all";

/**
 * Expenses & receipts (design/expenses.png), as an overview like the Accounts screen: a year
 * of one-off spending, what it comes to and what is still owed back, narrowed by tag or by
 * where the reimbursement stands. A row opens the expense's own page, where its receipts and
 * its reimbursement live.
 */
export function ExpensesPage() {
  const thisYear = Number(todayIso().slice(0, 4));
  const years = useExpenseYears();
  const [pickedYear, setPickedYear] = useState<number>();
  // This year until told otherwise, even before anything is in it: that is where a new expense lands.
  const year = pickedYear ?? thisYear;
  const yearChoices = [...new Set([thisYear, ...(years.data ?? [])])].sort((a, b) => b - a);

  const entries = useEntries(`${year}-01-01`, `${year}-12-31`);
  const accounts = useAccounts();
  const tags = useTags();
  const { add } = useAddItem();
  const categories = useCategories();
  // A category, then tags, narrow the list (Rachel, 2026-10-20: "should have categories as a filter and then tags").
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [tagId, setTagId] = useState<number | null>(null);
  const categoryChoiceId = useId();
  const [standing, setStanding] = useState<Standing>("all");
  const yearId = useId();
  const standingId = useId();
  const headingId = useId();

  // Newest first: what was just added is at the top.
  const expenses = (entries.data ?? []).filter((entry) => entry.kind === "money_out").reverse();
  // A category takes in its sub-categories, as everywhere else.
  const allCategories = categories.data ?? [];
  const inCategory = (expense: Entry, id: number) =>
    expense.categoryId === id || allCategories.some((category) => category.id === expense.categoryId && category.parentId === id);
  const usedCategories = allCategories.filter((category) => expenses.some((expense) => inCategory(expense, category.id)));
  // A category chosen in one year may not be used in another: then it filters nothing.
  const categoryFilter = usedCategories.some((category) => category.id === categoryId) ? categoryId : null;
  const ofCategory = categoryFilter === null ? expenses : expenses.filter((expense) => inCategory(expense, categoryFilter));
  // The tags offered are those used in what the category leaves, so a tag never shows an empty list.
  const usedTags = (tags.data ?? []).filter((tag) => ofCategory.some((expense) => expense.tagIds.includes(tag.id)));
  const tagFilter = usedTags.some((tag) => tag.id === tagId) ? tagId : null;
  const shown = ofCategory.filter(
    (expense) =>
      (tagFilter === null || expense.tagIds.includes(tagFilter)) &&
      (standing === "all" || expense.reimbursement?.status === standing),
  );
  const totalCents = shown.reduce((sum, expense) => sum + expense.amountCents, 0);
  const owedCents = shown.reduce((sum, expense) => sum + (expense.reimbursement?.owedCents ?? 0), 0);
  const reimbursedCents = shown.reduce((sum, expense) => sum + (expense.reimbursement?.reimbursedCents ?? 0), 0);
  const filtered = categoryFilter !== null || tagFilter !== null || standing !== "all";
  const needsAccount = accounts.data !== undefined && accounts.data.every((account) => account.archived);

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">One-off spending</p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={PAGE_TITLE}>Expenses &amp; receipts</h1>
            <HelpLink />
          </div>
        </div>
        <button type="button" disabled={needsAccount} onClick={() => add("money_out")} className={BUTTON_PRIMARY}>
          + Add expense
        </button>
      </div>

      <div className="space-y-4">
        {needsAccount && (
          <Card>
            <p className="font-medium">Add an account first.</p>
            <p className="mt-1 text-muted">
              An expense needs an account to be paid from.{" "}
              <Link to="/accounts" className="font-semibold text-accent-ink underline">
                Add the account you pay from
              </Link>
              , then come back here.
            </p>
          </Card>
        )}

        <Card>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <label htmlFor={yearId} className="block text-muted">
                Year
              </label>
              <select
                id={yearId}
                value={year}
                onChange={(event) => setPickedYear(Number(event.target.value))}
                className={`${INPUT} mt-1 max-w-40 text-lg font-bold`}
              >
                {yearChoices.map((choice) => (
                  <option key={choice} value={choice}>
                    {choice}
                  </option>
                ))}
              </select>
            </div>
            <Figure label={filtered ? "Spent, of what is shown" : "Spent"} cents={totalCents}>
              {shown.length === 1 ? "1 expense" : `${shown.length} expenses`}
            </Figure>
            <Figure label="Still owed to you" cents={owedCents}>
              sent in or still to send
            </Figure>
            <Figure label="Reimbursed" cents={reimbursedCents}>
              has come back
            </Figure>
          </div>
        </Card>

        <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
          <div className="flex flex-col gap-3 border-b border-line pb-3 lg:flex-row lg:items-end lg:justify-between">
            <div className="min-w-0">
              <h2 id={headingId} className="text-lg font-semibold">
                Expenses in {year}
              </h2>
              {usedTags.length > 0 && <TagFilter tags={usedTags} value={tagFilter} onChange={setTagId} />}
            </div>
            <div className="flex shrink-0 flex-col gap-2 sm:flex-row sm:items-end">
              {usedCategories.length > 0 && (
                <div>
                  <label htmlFor={categoryChoiceId} className={LABEL}>
                    Category
                  </label>
                  <select
                    id={categoryChoiceId}
                    value={categoryFilter ?? ""}
                    onChange={(event) => setCategoryId(event.target.value === "" ? null : Number(event.target.value))}
                    className={`${INPUT} sm:w-52`}
                  >
                    <option value="">Any</option>
                    {categoryChoices(usedCategories).map((category) => (
                      <option key={category.id} value={category.id}>
                        {category.label}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <div>
                <label htmlFor={standingId} className={LABEL}>
                  Reimbursement
                </label>
                <select
                  id={standingId}
                  value={standing}
                  onChange={(event) => setStanding(event.target.value as Standing)}
                  className={`${INPUT} sm:w-44`}
                >
                  <option value="all">Any</option>
                  {REIMBURSEMENT_STATUSES.map((status) => (
                    <option key={status} value={status}>
                      {REIMBURSEMENT_LABELS[status]}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          {entries.isPending && <p className="pt-4 text-muted">Loading your expenses…</p>}
          {entries.isError && (
            <p role="alert" className="pt-4 text-danger">
              {entries.error.message}
            </p>
          )}
          {entries.data && expenses.length === 0 && (
            <div className="pt-4">
              <p className="font-medium">No one-off spending in {year} yet.</p>
              <p className="mt-1 text-muted">
                A vet visit, a gift, a copay: anything that is not a regular bill. Add it here with its receipt, and say if someone
                will pay you back.
              </p>
            </div>
          )}
          {expenses.length > 0 && shown.length === 0 && (
            <p className="pt-4 text-muted">Nothing in {year} matches what is chosen above. Try another category or tag, or any reimbursement.</p>
          )}

          {shown.length > 0 && (
            <>
              {/* Column headings for sighted readers at `md` up; each row says the same in its own words for everyone else. */}
              <div aria-hidden className={`hidden pt-3 pb-1 text-sm font-semibold text-muted ${COLUMNS}`}>
                <span>Date</span>
                <span>Paid to</span>
                <span>Tags</span>
                <span>Paid from</span>
                <span className="pr-2 text-right">Amount</span>
                <span>Files</span>
                <span>Reimbursement</span>
              </div>
              <ul className="divide-y divide-line">
                {shown.map((expense) => (
                  <ExpenseRow key={expense.id} expense={expense} accounts={accounts.data ?? []} tags={tags.data ?? []} />
                ))}
              </ul>
              {shown.some((expense) => expense.estimate) && (
                <div className="mt-3">
                  <EstimateKey />
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </>
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

/** Categories in name order, each sub-category under its parent as "Parent › Sub". */
function categoryChoices(used: readonly Category[]): { id: number; label: string }[] {
  const byId = new Map(used.map((category) => [category.id, category]));
  return used
    .map((category) => {
      const parent = category.parentId === null ? undefined : byId.get(category.parentId);
      return { id: category.id, label: parent ? `${parent.name} › ${category.name}` : category.name };
    })
    .sort((a, b) => a.label.localeCompare(b.label));
}

/** The row of tag pills along the top of the list: All, then every tag this year's expenses use. */
function TagFilter({ tags, value, onChange }: { tags: readonly Tag[]; value: number | null; onChange: (tagId: number | null) => void }) {
  return (
    <div className="mt-2">
      <FilterPills
        label="Show expenses with tag"
        name="Tag"
        choices={tags.map((tag) => ({ value: tag.id, label: tag.name }))}
        value={value}
        onChange={onChange}
      />
    </div>
  );
}

/**
 * One expense on the overview. The row only opens the expense's page; editing, receipts and
 * the reimbursement are there.
 */
function ExpenseRow({ expense, accounts, tags }: { expense: Entry; accounts: readonly Account[]; tags: readonly Tag[] }) {
  const account = accounts.find((candidate) => candidate.id === expense.accountId)?.name ?? "An account that was removed";
  const status = expense.reimbursement?.status ?? "none";
  const files = expense.fileCount === 1 ? "1 file" : `${expense.fileCount} files`;

  return (
    <li>
      <Link to={`/expenses/${expense.id}`} className={`${ROW} ${COLUMNS}`}>
        <span className="flex items-baseline justify-between gap-3 md:contents">
          <span className="text-muted md:text-ink">{formatDay(expense.date)}</span>
          <span className="order-first min-w-0 flex-1 md:order-none">
            <span className="block font-semibold md:truncate">{expense.payee ?? expense.description}</span>
            {expense.payee !== null && <span className="block text-sm text-muted md:truncate">{expense.description}</span>}
          </span>
        </span>
        <span className="mt-1 block min-w-0 md:mt-0">
          <TagChips tagIds={expense.tagIds} tags={tags} />
        </span>
        <span className="mt-1 block text-sm text-muted md:mt-0 md:truncate md:text-base md:text-ink">
          <span className="md:hidden">Paid from </span>
          {account}
        </span>
        <span className="mt-1 flex items-center justify-between gap-3 md:contents">
          <span className="md:text-right">
            <Amount cents={expense.amountCents} estimate={expense.estimate} className="-ml-2 font-semibold md:ml-0" />
          </span>
          <span className="flex items-center gap-3 md:contents">
            <span className="inline-flex items-center gap-1 text-muted tabular-nums" title={files}>
              {expense.fileCount > 0 && (
                <>
                  <Paperclip aria-hidden className="size-4" />
                  <span aria-hidden>{expense.fileCount}</span>
                  <span className="sr-only">{files}</span>
                </>
              )}
            </span>
            <ReimbursementBadge status={status} />
          </span>
        </span>
      </Link>
    </li>
  );
}
