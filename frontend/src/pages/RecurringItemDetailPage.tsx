import { TriangleAlert } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router";
import { useCameFrom } from "../lib/cameFrom";
import { useAccounts } from "../api/accounts";
import { useBuckets } from "../api/buckets";
import { useCategories, useTags } from "../api/categories";
import { type Item, type ReplacedItem, useItems, useMergeReplaced, useRemoveVersion, useReplacedItem } from "../api/items";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton, EditPageButton } from "../components/EditButton";
import { ByMonthCard } from "../components/items/ByMonthCard";
import { BilledLaterCard } from "../components/items/BilledLaterCard";
import { PaidAheadCard } from "../components/items/PaidAheadCard";
import { PaidBackCard } from "../components/items/PaidBackCard";
import { UpcomingCard } from "../components/items/UpcomingCard";
import { ItemForm, type ItemPart } from "../components/items/ItemForm";
import { PAYMENT_MODE_LABELS } from "../components/cards/labels";
import { describeItemSchedule, describeKind, ITEM_FREQUENCY_LABELS, KIND_LABELS } from "../components/items/labels";
import { PauseControls } from "../components/items/PauseItem";
import { BUTTON_DANGER, BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";
import { HelpLink } from "../components/help/HelpLink";

/**
 * One recurring item, laid out like an account's page and an income source's: the big figures
 * first (what it costs each time and over a year, when it is next due, what pays for it), then
 * a card per topic. The Recurring items screen is the overview; this is where the detail lives.
 *
 * Like an account's page, each card is changed from the card: Edit on Schedule opens the
 * schedule and nothing else, and so on, so no form is longer than what it is about. Edit item
 * is the small one (type, name, archiving, deleting), as Edit account is. A bucket's own
 * recurring amount is the exception: it is changed in its bucket's form, so it has the one button.
 *
 * A credit card's payment is an item like any other and is changed here, including how much it
 * pays (the statement balance, say): the card's page shows the payment, and this page sets it.
 */
export function RecurringItemDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const back = useCameFrom({ to: "/recurring", label: "Recurring items" });
  const items = useItems();
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const buckets = useBuckets();
  // Which part of the item is open for editing, if any.
  const [editing, setEditing] = useState<ItemPart | null>(null);

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
  // A bucket move lives on its bucket's account (Rachel, 2026-10-19): an old link to it opens that account.
  if (item.kind === "bucket_move") {
    return <Navigate to={`/accounts/${item.payFromAccountId}`} replace />;
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
  const edit = (part: ItemPart, name: string) => <EditButton name={name} onClick={() => setEditing(part)} />;
  // A payment that follows its card says how, in place of an amount.
  const follows = item.followsCard && item.cardPaymentMode ? PAYMENT_MODE_LABELS[item.cardPaymentMode] : null;

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to={back.to} className="text-accent-ink underline">
              {back.label}
            </Link>{" "}
            / {KIND_LABELS[item.kind]}
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} min-w-0 truncate`}>{item.name}</h1>
            <HelpLink />
          </div>
          <p className="text-muted">
            {describeKind(item)} · {describeItemSchedule(item.schedule)}
            {/* Said in words, never by color alone. */}
            {item.archived && " · Archived"}
            {item.paused && !item.archived && (item.pausedUntil ? ` · Paused until ${formatDate(item.pausedUntil)}` : " · Paused")}
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <EditPageButton label="Edit item" onClick={() => setEditing("item")} />
          <button type="button" onClick={() => setEditing("amount")} aria-label={`Change the amount of ${item.name}`} className={BUTTON_PRIMARY}>
            Change the amount
          </button>
        </div>
      </div>

      <div className="space-y-4">
        {!item.archived && <MergeReplaced item={item} onMerged={(merged) => navigate(`/recurring/${merged}`, { replace: true })} />}
        <Card>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <p className="text-muted">Amount</p>
              {follows ? (
                <>
                  <p className="text-2xl font-bold">{follows}</p>
                  <p className="text-muted">worked out from the card each time</p>
                </>
              ) : item.usesHundredths != null ? (
                // Billed by use: what each time costs, and about how often, which together make each month's bill.
                <>
                  <Amount cents={item.amountCents} estimate={false} className="-ml-2 text-3xl font-bold" />
                  <p className="text-muted">
                    each time · about {item.usesHundredths / 100} a {item.usesPer === "month" ? "month" : "week"}, about{" "}
                    <span data-amount>{formatCents(item.perOccurrenceCents)}</span> a month
                  </p>
                </>
              ) : (
                <>
                  <Amount cents={item.perOccurrenceCents} estimate={item.estimate} className="-ml-2 text-3xl font-bold" />
                  <p className="text-muted">
                    each time
                    {item.amountMode === "annual" && " · entered as a yearly total"}
                    {item.estimate && " · an estimate"}
                  </p>
                </>
              )}
            </div>
            <div>
              <p className="text-muted">A year</p>
              {follows ? (
                <>
                  <p className="text-2xl font-bold">—</p>
                  <p className="text-muted">it changes with what is charged</p>
                </>
              ) : (
                <>
                  <p data-amount className="text-2xl font-bold tabular-nums">
                    {formatCents(item.perYearCents)}
                  </p>
                  <p className="text-muted">{everyPaycheck ? "over a year of paychecks" : "what it adds up to over a year"}</p>
                </>
              )}
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
                {item.payToAccountId !== null
                  ? `to ${accountName(item.payToAccountId)}`
                  : item.kind === "budget"
                    ? "money set aside to spend"
                    : item.paidAhead
                      ? "paid ahead, used session by session"
                      : item.autopay
                        ? "paid automatically"
                        : "paid by hand"}
              </p>
            </div>
          </div>
        </Card>

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <div className="space-y-4">
            {item.billedLater && <BilledLaterCard item={item} />}
            {item.paidAhead && <PaidAheadCard item={item} />}
            <UpcomingCard item={item} />
            <ByMonthCard item={item} />
            <Card title="Schedule" action={edit("schedule", "the schedule")}>
              <dl className="grid gap-4 sm:grid-cols-3">
                <Fact term="How often">{describeItemSchedule(item.schedule)}</Fact>
                <Fact term={item.schedule.frequency === "one_time" ? "Date" : "Starts"}>{formatDate(item.schedule.startDate)}</Fact>
                {item.schedule.endDate !== null && <Fact term="Last date">{formatDate(item.schedule.endDate)}</Fact>}
                {/* Every paycheck is part of the schedule; otherwise which paycheck pays it is edited with where it's paid from. */}
                {everyPaycheck && (
                  <Fact term="Which paycheck pays it">
                    {item.daysAfterPayday === 0
                      ? "Every paycheck, on payday"
                      : `Every paycheck, ${item.daysAfterPayday} ${item.daysAfterPayday === 1 ? "day" : "days"} after payday`}
                  </Fact>
                )}
              </dl>
              {item.countsFromPaid && (
                <p className="mt-3 text-sm text-muted">
                  The next one counts from the day the last one was paid, so paying late or early moves the dates after it.
                </p>
              )}
            </Card>

            <Card
              title={item.payToAccountId !== null ? "From and to" : "Where it's paid from"}
              action={edit("accounts", "where it is paid from")}
            >
              <dl className="grid gap-4 sm:grid-cols-3">
                <Fact term={item.kind === "transfer" ? "From" : "Paid from"}>{accountName(item.payFromAccountId)}</Fact>
                {item.payToAccountId !== null && <Fact term={item.kind === "card_payment" ? "Card" : "To"}>{accountName(item.payToAccountId)}</Fact>}
                {item.kind !== "budget" && <Fact term="Autopay">{item.autopay ? "Yes" : "No, paid by hand"}</Fact>}
                {!everyPaycheck && (
                  <Fact term="Which paycheck pays it">{item.placement === "one_early" ? "One period early" : "The period it's due in"}</Fact>
                )}
                {item.fromBucketId !== null && <Fact term="Comes out of">{bucketName(item.fromBucketId)}</Fact>}
                {item.kind === "transfer" && item.bucketId !== null && (
                  <Fact term="Into bucket">{bucketName(item.bucketId)}</Fact>
                )}
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
            </Card>

            <Card title="Details" action={edit("details", "the details")}>
              <dl className="grid gap-4 sm:grid-cols-3">
                <Fact term="Category">{category ? (parent ? `${parent.name} › ${category.name}` : category.name) : "None"}</Fact>
                <Fact term="Tags">{itemTags.length === 0 ? "None" : itemTags.map((tag) => tag.name).join(", ")}</Fact>
                <Fact term="Date added">{formatDate(item.createdOn)}</Fact>
              </dl>
              <p className="mt-4 text-sm text-muted">Note</p>
              <p className={item.note ? "break-words" : "text-muted"}>{item.note ?? "No note."}</p>
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
            {(item.kind === "bill" || item.kind === "budget") && <PaidBackCard item={item} />}
          </div>
        </div>
      </div>

      {editing && (
        <ItemForm
          item={item}
          part={editing}
          accounts={allAccounts}
          categories={allCategories}
          tags={tags.data ?? []}
          onClose={() => setEditing(null)}
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
  // One put in by mistake can be removed; the one before it then carries on. The last one always stays. It asks first:
  // an amount carries its own schedule, so removing one changes the dates it covered (Rachel, 2026-10-22: "I deleted
  // an estimate from a recurring bill and it deleted all my real payments").
  const remove = useRemoveVersion(item.id);
  const [confirming, setConfirming] = useState<number | null>(null);
  return (
    <Card title="Amount history">
      <ul className="divide-y divide-line">
        {[...item.history].reverse().map((point) => (
          <li key={point.effectiveFrom} className="py-2">
            <div className="flex flex-wrap items-center justify-between gap-x-3">
              <span className="text-muted">From {formatDate(point.effectiveFrom)}</span>
              <span className="flex items-center gap-2">
                <span data-amount className="tabular-nums">
                  {formatCents(point.perOccurrenceCents)} · <span className="font-semibold">{formatCents(point.perYearCents)}</span> a year
                </span>
                {item.history.length > 1 && confirming !== point.versionId && (
                  <button
                    type="button"
                    disabled={remove.isPending}
                    onClick={() => setConfirming(point.versionId)}
                    aria-label={`Remove the amount from ${formatDate(point.effectiveFrom)}`}
                    className={BUTTON_LINK}
                  >
                    Remove
                  </button>
                )}
              </span>
            </div>
            {confirming === point.versionId && (
              <ConfirmRemove
                from={point.effectiveFrom}
                pending={remove.isPending}
                onKeep={() => setConfirming(null)}
                onRemove={() => remove.mutate(point.versionId, { onSuccess: () => setConfirming(null) })}
              />
            )}
          </li>
        ))}
      </ul>
      {remove.isError && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
      <p className="mt-2 text-sm text-muted">
        {item.history.length < 2
          ? "No changes yet. When the amount changes, choose Change the amount: everything dated before that day keeps this amount."
          : "Each date uses the newest amount set on or before it."}
        {item.fromLastYear &&
          " Dates still to come are estimated from what the same month came to last year; a month with nothing then uses this amount."}
      </p>
    </Card>
  );
}

/**
 * Offers to make this item one with the archived item it was added in place of. Changing an item's type used to offer
 * "Archive this and add a new item", which left two items for one thing, the old one archived with its past and the
 * new one current (Rachel, 2026-10-24). A change of type now always changes the one item, and this puts a pair left
 * the old way back together the same way. Shows nothing when there is no such pair.
 */
function MergeReplaced({ item, onMerged }: { item: Item; onMerged: (id: number) => void }) {
  const replaced = useReplacedItem(item.id);
  const merge = useMergeReplaced(item.id, onMerged);
  const [confirming, setConfirming] = useState(false);
  const old: ReplacedItem | null | undefined = replaced.data;
  if (!old) {
    return null;
  }
  const oldKind = KIND_LABELS[old.kind].toLowerCase();
  return (
    <Card title="Merge with the archived one">
      <p>
        There's also an archived {oldKind} called {old.name}, archived on {formatDate(old.archivedOn)}, the day this one
        starts. That's what changing the type used to do. Merge them to make one {KIND_LABELS[item.kind].toLowerCase()} with
        all of its history.
      </p>
      {confirming ? (
        <div role="alertdialog" aria-label={`Merge ${item.name} with the archived ${oldKind}?`} className="mt-3 rounded-lg border border-line p-3">
          <p className="font-semibold">Merge {item.name} with the archived {oldKind}?</p>
          <p className="mt-1 text-sm text-muted">
            You'll have one item, as if you had changed its type: its earlier dates count as a {KIND_LABELS[item.kind].toLowerCase()} too,
            with the amounts they had then, and paid dates and receipts stay where they are. From {formatDate(old.archivedOn)} on, this
            one's amounts and dates are used. You can undo this straight afterwards.
          </p>
          <div className="mt-3 flex flex-col gap-2 sm:flex-row">
            <button type="button" disabled={merge.isPending} onClick={() => setConfirming(false)} className={BUTTON_SECONDARY}>
              Not now
            </button>
            <button type="button" disabled={merge.isPending} onClick={() => merge.mutate()} className={BUTTON_PRIMARY}>
              {merge.isPending ? "Merging…" : "Yes, merge them"}
            </button>
          </div>
        </div>
      ) : (
        <button type="button" onClick={() => setConfirming(true)} className={`${BUTTON_SECONDARY} mt-3`}>
          Merge with the archived {oldKind}
        </button>
      )}
      {merge.isError && (
        <p role="alert" className="mt-2 text-danger">
          {merge.error.message}
        </p>
      )}
    </Card>
  );
}

/** Asks before an amount is removed, saying what changes and that payments stay. Keeping it is the default. */
function ConfirmRemove({ from, pending, onKeep, onRemove }: { from: string; pending: boolean; onKeep: () => void; onRemove: () => void }) {
  const keep = useRef<HTMLButtonElement>(null);
  useEffect(() => keep.current?.focus(), []);
  return (
    <div role="alertdialog" aria-label={`Remove the amount from ${formatDate(from)}?`} className="mt-2 rounded-lg border border-danger p-3">
      <p className="flex items-center gap-2 font-semibold">
        <TriangleAlert aria-hidden className="size-5 shrink-0 text-danger" />
        Remove the amount from {formatDate(from)}?
      </p>
      <p className="mt-1 text-sm text-muted">
        The dates it covered go back to the amount before it, with that amount's schedule. Bills already paid stay paid, on
        their own dates. You can undo this straight afterwards.
      </p>
      <div className="mt-3 flex flex-col gap-2 sm:flex-row">
        <button ref={keep} type="button" disabled={pending} onClick={onKeep} className={BUTTON_SECONDARY}>
          Keep it
        </button>
        <button type="button" disabled={pending} onClick={onRemove} className={BUTTON_DANGER}>
          {pending ? "Removing…" : "Yes, remove it"}
        </button>
      </div>
    </div>
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
