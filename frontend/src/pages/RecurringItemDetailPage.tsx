import { type ReactNode, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useAccounts } from "../api/accounts";
import { useBuckets } from "../api/buckets";
import { useCategories, useTags } from "../api/categories";
import { type Item, useItems } from "../api/items";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditPageButton } from "../components/EditButton";
import { ItemForm } from "../components/items/ItemForm";
import { describeItemSchedule, describeKind, ITEM_FREQUENCY_LABELS, KIND_LABELS } from "../components/items/labels";
import { PauseControls } from "../components/items/PauseItem";
import { PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";

/**
 * One recurring item, laid out like an account's page and an income source's: the big figures
 * first (what it costs each time and over a year, when it is next due, what pays for it), then
 * a card per topic. The Recurring items screen is the overview; this is where the detail
 * lives, and where pausing and the amounts it has had are, so the edit form stays short.
 */
export function RecurringItemDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const items = useItems();
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const buckets = useBuckets();
  const [editing, setEditing] = useState(false);

  if (items.isPending) {
    return <p className="text-muted">Loading…</p>;
  }
  const item = items.data?.find((candidate) => candidate.id === id);
  if (items.isError || !item) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Recurring items</h1>
        <Card>
          <p role="alert" className="font-medium">
            {items.isError ? items.error.message : "We couldn't find that item. It may have been removed."}
          </p>
          <Link to="/recurring" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all recurring items
          </Link>
        </Card>
      </>
    );
  }

  const allAccounts = accounts.data ?? [];
  const allCategories = categories.data ?? [];
  const accountName = (accountId: number | null) =>
    allAccounts.find((account) => account.id === accountId)?.name ?? "An account that was removed";
  const bucketName = (bucketId: number | null) => (buckets.data ?? []).find((bucket) => bucket.id === bucketId)?.name ?? "a bucket that was removed";
  const category = allCategories.find((candidate) => candidate.id === item.categoryId);
  const parent = allCategories.find((candidate) => candidate.id === category?.parentId);
  const itemTags = (tags.data ?? []).filter((tag) => item.tagIds.includes(tag.id));
  const everyPaycheck = item.schedule.frequency === "every_pay_period";

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/recurring" className="text-accent-ink underline">
              Recurring items
            </Link>{" "}
            / {KIND_LABELS[item.kind]}
          </p>
          <h1 className={`${PAGE_TITLE} truncate`}>{item.name}</h1>
          <p className="text-muted">
            {describeKind(item)} · {describeItemSchedule(item.schedule)}
            {/* Said in words, never by color alone. */}
            {item.archived && " · Archived"}
            {item.paused && !item.archived && (item.pausedUntil ? ` · Paused until ${formatDate(item.pausedUntil)}` : " · Paused")}
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <EditPageButton label="Edit item" onClick={() => setEditing(true)} />
        </div>
      </div>

      <div className="space-y-4">
        <Card>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <p className="text-muted">Amount</p>
              <Amount cents={item.perOccurrenceCents} estimate={item.estimate} className="-ml-2 text-3xl font-bold" />
              <p className="text-muted">
                each time
                {item.amountMode === "annual" && " · entered as a yearly total"}
                {item.estimate && " · an estimate"}
              </p>
            </div>
            <div>
              <p className="text-muted">A year</p>
              <p data-amount className="text-2xl font-bold tabular-nums">
                {formatCents(item.perYearCents)}
              </p>
              <p className="text-muted">{everyPaycheck ? "over a year of paychecks" : "what it adds up to over a year"}</p>
            </div>
            <div>
              <p className="text-muted">Next date</p>
              <p className="text-2xl font-bold tabular-nums">{item.next ? formatDate(item.next.dueDate) : "—"}</p>
              <p className="text-muted">{nextNote(item)}</p>
            </div>
            <div className="min-w-0">
              <p className="text-muted">{item.kind === "transfer" ? "From" : "Paid from"}</p>
              <p className="text-2xl font-bold break-words">{accountName(item.payFromAccountId)}</p>
              <p className="text-muted">
                {item.kind === "bucket_move"
                  ? `set aside in ${bucketName(item.bucketId)}`
                  : item.payToAccountId !== null
                    ? `to ${accountName(item.payToAccountId)}`
                    : item.kind === "budget"
                      ? "money set aside to spend"
                      : item.autopay
                        ? "paid automatically"
                        : "paid by hand"}
              </p>
            </div>
          </div>
        </Card>

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <div className="space-y-4">
            <Card title="Schedule">
              <dl className="grid gap-4 sm:grid-cols-3">
                <Fact term="How often">{describeItemSchedule(item.schedule)}</Fact>
                <Fact term={item.schedule.frequency === "one_time" ? "Date" : "Starts"}>{formatDate(item.schedule.startDate)}</Fact>
                {item.schedule.endDate !== null && <Fact term="Ends">{formatDate(item.schedule.endDate)}</Fact>}
                <Fact term="Which paycheck pays it">
                  {everyPaycheck
                    ? item.daysAfterPayday === 0
                      ? "Every paycheck, on payday"
                      : `Every paycheck, ${item.daysAfterPayday} ${item.daysAfterPayday === 1 ? "day" : "days"} after payday`
                    : item.placement === "one_early"
                      ? "One period early"
                      : "The period it's due in"}
                </Fact>
                {item.kind !== "budget" && item.kind !== "bucket_move" && <Fact term="Autopay">{item.autopay ? "Yes" : "No, paid by hand"}</Fact>}
              </dl>
            </Card>

            <Card title="Details">
              <dl className="grid gap-4 sm:grid-cols-3">
                <Fact term="Category">{category ? (parent ? `${parent.name} › ${category.name}` : category.name) : "None"}</Fact>
                <Fact term="Tags">{itemTags.length === 0 ? "None" : itemTags.map((tag) => tag.name).join(", ")}</Fact>
                <Fact term="Date added">{formatDate(item.createdOn)}</Fact>
                {item.fromBucketId !== null && <Fact term="Comes out of">{bucketName(item.fromBucketId)}</Fact>}
                {item.kind === "transfer" && item.bucketId !== null && <Fact term="Into bucket">{bucketName(item.bucketId)}</Fact>}
                {item.saveUpBucketId !== null && (
                  <Fact term="Saved up for in">
                    {bucketName(item.saveUpBucketId)}
                    {item.saveUpPerPaycheckCents !== null && (
                      <span className="block text-sm font-normal text-muted">
                        The next paycheck sets aside <span data-amount>{formatCents(item.saveUpPerPaycheckCents)}</span>
                      </span>
                    )}
                  </Fact>
                )}
              </dl>
              <p className="mt-4 text-sm text-muted">Note</p>
              <p className={item.note ? "break-words" : "text-muted"}>{item.note ?? "No note. Choose Edit item to add one."}</p>
            </Card>
          </div>

          <div className="space-y-4">
            <Card title="Pause">
              {item.archived ? (
                <p className="text-muted">
                  This item is archived, so nothing it is scheduled for happens. To bring it back, choose Edit item and clear Archived.
                </p>
              ) : (
                <PauseControls item={item} />
              )}
            </Card>
            <AmountHistory item={item} />
          </div>
        </div>
      </div>

      {editing && (
        <ItemForm
          item={item}
          accounts={allAccounts}
          categories={allCategories}
          tags={tags.data ?? []}
          onClose={() => setEditing(false)}
          onDeleted={() => navigate("/recurring")}
        />
      )}
    </>
  );
}

/** What sits under the next date: which paycheck pays for it, or why there is no date. */
function nextNote(item: Item): string {
  if (item.archived) {
    return "archived, so nothing is scheduled";
  }
  if (item.next === null) {
    return item.paused ? "paused, so nothing is scheduled" : "nothing is due in the coming year";
  }
  if (item.next.periodStart === null) {
    return ITEM_FREQUENCY_LABELS[item.schedule.frequency].toLowerCase();
  }
  const paycheck = `paid from the ${formatDate(item.next.periodStart)} paycheck`;
  return item.next.early ? `${paycheck}, one period early` : paycheck;
}

/** What the item has been set to, version by version, newest first. "Amount", not "price": a budget or a bucket move has no price. */
function AmountHistory({ item }: { item: Item }) {
  return (
    <Card title="Amount history">
      <ul className="divide-y divide-line">
        {[...item.history].reverse().map((point) => (
          <li key={point.effectiveFrom} className="flex flex-wrap items-center justify-between gap-x-3 py-2">
            <span className="text-muted">From {formatDate(point.effectiveFrom)}</span>
            <span data-amount className="tabular-nums">
              {formatCents(point.perOccurrenceCents)} · <span className="font-semibold">{formatCents(point.perYearCents)}</span> a year
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-sm text-muted">
        {item.history.length < 2
          ? "No changes yet. When the amount changes, choose Edit item: everything dated before that day keeps this amount."
          : "Each date uses the newest amount set on or before it."}
      </p>
    </Card>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted">{term}</dt>
      <dd className="font-semibold break-words">{children}</dd>
    </div>
  );
}
