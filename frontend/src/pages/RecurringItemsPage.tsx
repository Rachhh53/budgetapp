import { type ReactNode, useId, useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { useCategories, useTags } from "../api/categories";
import { type Item, ITEM_KINDS, type ItemKind, useItems } from "../api/items";
import { Amount, EstimateKey } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { ItemForm } from "../components/items/ItemForm";
import { describeItemSchedule, describeKind, describeLandsIn, KIND_PLURALS } from "../components/items/labels";
import { BUTTON_PRIMARY, INPUT, PAGE_TITLE } from "../components/ui";
import { formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";

/** What the form is open for: a new item, an existing one, or closed. */
type FormState = { item?: Item } | null;

type Filter = "all" | ItemKind;

/**
 * Column widths from `md` up. Below that each row becomes a card instead: the same cells in
 * the same order, two to a line, with the amount lifted beside the name.
 */
const COLUMNS =
  "md:grid-cols-[minmax(0,1.6fr)_minmax(0,1.1fr)_minmax(0,1.3fr)_7.5rem_6.5rem_minmax(0,1.1fr)_5.5rem_4.5rem_4.75rem] md:items-center md:gap-x-3";

/**
 * Recurring items (design/recurring.png): every bill, transfer, budget and card payment,
 * filterable by kind and by search, with what each costs a year, which paycheck pays for it
 * and when it is next due. Choosing one opens it for editing.
 */
export function RecurringItemsPage() {
  const searchId = useId();
  const items = useItems();
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const [form, setForm] = useState<FormState>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [search, setSearch] = useState("");

  const all = items.data ?? [];
  const allAccounts = accounts.data ?? [];
  const allCategories = categories.data ?? [];
  const needsAccount = accounts.data !== undefined && allAccounts.every((account) => account.archived);

  const accountName = (id: number | null) => allAccounts.find((account) => account.id === id)?.name ?? "";
  const categoryName = (id: number | null) => allCategories.find((category) => category.id === id)?.name ?? "";

  const typed = search.trim().toLowerCase();
  const shown = all
    .filter((item) => filter === "all" || item.kind === filter)
    .filter(
      (item) =>
        typed === "" ||
        [item.name, accountName(item.payFromAccountId), accountName(item.payToAccountId), categoryName(item.categoryId)].some((text) =>
          text.toLowerCase().includes(typed),
        ),
    )
    // Archived items sink to the bottom; the backend already sorts by name.
    .sort((a, b) => Number(a.archived) - Number(b.archived));

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Setup</p>
          <h1 className={PAGE_TITLE}>Recurring items</h1>
        </div>
        <button type="button" disabled={needsAccount} onClick={() => setForm({})} className={BUTTON_PRIMARY}>
          + Add item
        </button>
      </div>

      {needsAccount && (
        <div className="mb-4">
          <Card>
            <p className="font-medium">Add an account first.</p>
            <p className="mt-1 text-muted">
              Every bill, budget and transfer is paid from an account.{" "}
              <Link to="/accounts" className="font-semibold text-accent-ink underline">
                Add the account you pay from
              </Link>
              , then come back here.
            </p>
          </Card>
        </div>
      )}

      {items.isPending && <p className="text-muted">Loading your recurring items…</p>}
      {items.isError && (
        <p role="alert" className="text-danger">
          {items.error.message}
        </p>
      )}

      {items.data && all.length === 0 && !needsAccount && (
        <Card>
          <p className="font-medium">No recurring items yet.</p>
          <p className="mt-1 text-muted">
            Add what comes round again and again — rent, the electric bill, a grocery budget, a transfer to savings.
            Each one lands in the pay period that pays for it.
          </p>
        </Card>
      )}

      {all.length > 0 && (
        <>
          <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center">
            <div role="group" aria-label="Show" className="flex flex-wrap gap-2">
              <FilterChip label="All" count={all.length} pressed={filter === "all"} onPress={() => setFilter("all")} />
              {ITEM_KINDS.map((kind) => (
                <FilterChip
                  key={kind}
                  label={KIND_PLURALS[kind]}
                  count={all.filter((item) => item.kind === kind).length}
                  pressed={filter === kind}
                  onPress={() => setFilter(kind)}
                />
              ))}
            </div>
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center lg:ml-auto">
              {all.some((item) => item.estimate) && <EstimateKey />}
              <div className="flex items-center gap-2">
                <label htmlFor={searchId} className="font-medium">
                  Search
                </label>
                <input
                  id={searchId}
                  type="search"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Name, account, category"
                  autoComplete="off"
                  className={`${INPUT} sm:w-64`}
                />
              </div>
            </div>
          </div>

          {shown.length === 0 ? (
            <Card>
              <p className="font-medium">Nothing matches that.</p>
              <p className="mt-1 text-muted">Try a different search, or choose All to see every item again.</p>
            </Card>
          ) : (
            <div role="table" aria-label="Recurring items" className="rounded-card border border-line bg-surface">
              <div role="rowgroup" className="hidden md:block">
                <div role="row" className={`grid border-b border-line px-5 py-3 text-sm font-semibold text-muted ${COLUMNS}`}>
                  <span role="columnheader">Name</span>
                  <span role="columnheader">Type</span>
                  <span role="columnheader">Schedule</span>
                  <span role="columnheader" className="pr-2 text-right">
                    Amount
                  </span>
                  <span role="columnheader" className="text-right">
                    Per year
                  </span>
                  <span role="columnheader">Paid from</span>
                  <span role="columnheader">Lands in</span>
                  <span role="columnheader">Next</span>
                  <span role="columnheader" className="sr-only">
                    Edit
                  </span>
                </div>
              </div>
              <div role="rowgroup" className="divide-y divide-line">
                {shown.map((item) => (
                  <ItemRow key={item.id} item={item} accounts={allAccounts} onEdit={() => setForm({ item })} />
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {form && (
        <ItemForm
          item={form.item}
          accounts={allAccounts}
          categories={allCategories}
          tags={tags.data ?? []}
          onClose={() => setForm(null)}
        />
      )}
    </>
  );
}

function FilterChip({ label, count, pressed, onPress }: { label: string; count: number; pressed: boolean; onPress: () => void }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onPress}
      className={`min-h-11 rounded-full border px-4 font-medium ${
        pressed ? "border-ink bg-ink text-surface" : "border-line bg-surface"
      }`}
    >
      {label} <span className={pressed ? "" : "text-muted"}>· {count}</span>
    </button>
  );
}

function ItemRow({ item, accounts, onEdit }: { item: Item; accounts: readonly Account[]; onEdit: () => void }) {
  const accountName = (id: number | null) => accounts.find((account) => account.id === id)?.name ?? "An account that was removed";
  const paidFrom =
    item.payToAccountId === null
      ? accountName(item.payFromAccountId)
      : `${accountName(item.payFromAccountId)} → ${accountName(item.payToAccountId)}`;

  return (
    <div
      role="row"
      onClick={clickRowToEdit}
      className={`grid grid-cols-2 gap-x-3 gap-y-1 px-4 py-3 md:px-5 ${COLUMNS} ${ROW_OPENS} ${item.archived ? "text-muted" : ""}`}
    >
      <span role="cell" className="min-w-0">
        <span className="block max-w-full truncate font-semibold">{item.name}</span>
        {item.archived && <span className="ml-2 rounded-md border border-line px-1.5 py-0.5 text-xs font-semibold">Archived</span>}
      </span>
      <Cell className="text-sm md:text-base">{describeKind(item)}</Cell>
      <Cell className="text-sm md:text-base">{describeItemSchedule(item.schedule)}</Cell>
      {/* On a phone the amount sits beside the name, where the eye looks for it. */}
      <Cell className="col-start-2 row-start-1 text-right font-semibold md:col-start-auto md:row-start-auto">
        <Amount cents={item.perOccurrenceCents} estimate={item.estimate} />
      </Cell>
      <Cell label="Per year" className="text-sm tabular-nums md:text-right md:text-base">
        <span data-amount>{formatCents(item.perYearCents)}</span>
      </Cell>
      <Cell label="Paid from" className="text-sm md:truncate md:text-base">
        {paidFrom}
      </Cell>
      <Cell label="Lands in" className="text-sm md:text-base">
        {describeLandsIn(item)}
      </Cell>
      <Cell label="Next" className="text-sm md:text-base">
        {item.next ? formatDay(item.next.dueDate) : "—"}
      </Cell>
      {/* On a phone it closes the card, on the right where a thumb reaches. */}
      <Cell className="col-span-2 -my-2 justify-self-end md:col-span-1 md:-my-1">
        <EditButton name={item.name} onClick={onEdit} labelled />
      </Cell>
    </div>
  );
}

/** A table cell. `label` repeats the column heading on a phone, where the header row is hidden. */
function Cell({ label, className = "", children }: { label?: string; className?: string; children: ReactNode }) {
  return (
    <span role="cell" className={`min-w-0 ${className}`}>
      {label && <span className="text-muted md:hidden">{label}: </span>}
      {children}
    </span>
  );
}
