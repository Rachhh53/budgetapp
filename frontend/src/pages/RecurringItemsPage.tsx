import { ArrowDown, ArrowUp } from "lucide-react";
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

type Filter = "all" | ItemKind | "archived";

/** The columns the list can be put in order by, in the order they appear. */
const SORTS = [
  { key: "name", label: "Name" },
  { key: "type", label: "Type" },
  { key: "schedule", label: "Schedule" },
  { key: "amount", label: "Amount", numeric: true },
  { key: "perYear", label: "Per year", numeric: true },
  { key: "paidFrom", label: "Paid from" },
  { key: "landsIn", label: "Lands in" },
  { key: "next", label: "Next" },
] as const;
type SortKey = (typeof SORTS)[number]["key"];
interface Sort {
  key: SortKey;
  descending: boolean;
}

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
  const [sort, setSort] = useState<Sort>({ key: "name", descending: false });
  // The same header again turns the order round; a different one starts from the top.
  const sortBy = (key: SortKey) => setSort((current) => ({ key, descending: current.key === key ? !current.descending : false }));

  const all = items.data ?? [];
  // Archived items are out of the way until asked for: they have their own chip, and count nowhere else.
  const active = all.filter((item) => !item.archived);
  const archived = all.filter((item) => item.archived);
  const allAccounts = accounts.data ?? [];
  const allCategories = categories.data ?? [];
  const needsAccount = accounts.data !== undefined && allAccounts.every((account) => account.archived);

  const accountName = (id: number | null) => allAccounts.find((account) => account.id === id)?.name ?? "";
  const categoryName = (id: number | null) => allCategories.find((category) => category.id === id)?.name ?? "";

  const typed = search.trim().toLowerCase();
  const shown = (filter === "archived" ? archived : active)
    .filter((item) => filter === "all" || filter === "archived" || item.kind === filter)
    .filter(
      (item) =>
        typed === "" ||
        [item.name, accountName(item.payFromAccountId), accountName(item.payToAccountId), categoryName(item.categoryId)].some((text) =>
          text.toLowerCase().includes(typed),
        ),
    )
    .sort((a, b) => {
      const [first, second] = [sortValue(a), sortValue(b)];
      // Something with no next date goes last whichever way the dates run.
      if (first === null || second === null) {
        return first === second ? 0 : first === null ? 1 : -1;
      }
      const order =
        typeof first === "number" && typeof second === "number"
          ? first - second
          : String(first).localeCompare(String(second), undefined, { sensitivity: "base", numeric: true });
      // Ties keep the order the list arrived in, which is by name.
      return sort.descending ? -order : order;
    });

  /** What a row is ordered by in the chosen column: the words or the number it shows there. */
  function sortValue(item: Item): string | number | null {
    switch (sort.key) {
      case "name":
        return item.name;
      case "type":
        return describeKind(item);
      case "schedule":
        return describeItemSchedule(item.schedule);
      case "amount":
        return item.perOccurrenceCents;
      case "perYear":
        return item.perYearCents;
      case "paidFrom":
        return accountName(item.payFromAccountId);
      case "landsIn":
        return describeLandsIn(item);
      case "next":
        return item.next?.dueDate ?? null;
    }
  }

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
              <FilterChip label="All" count={active.length} pressed={filter === "all"} onPress={() => setFilter("all")} />
              {ITEM_KINDS.map((kind) => (
                <FilterChip
                  key={kind}
                  label={KIND_PLURALS[kind]}
                  count={active.filter((item) => item.kind === kind).length}
                  pressed={filter === kind}
                  onPress={() => setFilter(kind)}
                />
              ))}
              {/* Only when there is something in it, or while it is the one chosen (the last one may just have been restored). */}
              {(archived.length > 0 || filter === "archived") && (
                <FilterChip label="Archived" count={archived.length} pressed={filter === "archived"} onPress={() => setFilter("archived")} />
              )}
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

          {/* A phone has no header row to press, so the same choice is offered as a list. */}
          <div className="mb-3 flex items-center gap-2 md:hidden">
            <label htmlFor={`${searchId}-sort`} className="font-medium">
              Sort by
            </label>
            <select
              id={`${searchId}-sort`}
              value={sort.key}
              onChange={(event) => setSort({ key: event.target.value as SortKey, descending: false })}
              className={`${INPUT} min-w-0 flex-1`}
            >
              {SORTS.map((column) => (
                <option key={column.key} value={column.key}>
                  {column.label}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => sortBy(sort.key)}
              aria-label={sort.descending ? "Sorted last to first. Switch to first to last" : "Sorted first to last. Switch to last to first"}
              className="flex size-11 shrink-0 items-center justify-center rounded-lg border border-line bg-surface"
            >
              {sort.descending ? <ArrowDown aria-hidden className="size-5" /> : <ArrowUp aria-hidden className="size-5" />}
            </button>
          </div>

          {shown.length === 0 ? (
            <Card>
              {typed === "" && filter === "archived" ? (
                <>
                  <p className="font-medium">Nothing is archived.</p>
                  <p className="mt-1 text-muted">Choose All to see your items.</p>
                </>
              ) : typed === "" && filter === "all" ? (
                <>
                  <p className="font-medium">Everything here is archived.</p>
                  <p className="mt-1 text-muted">Choose Archived to see those items, or add a new one.</p>
                </>
              ) : (
                <>
                  <p className="font-medium">Nothing matches that.</p>
                  <p className="mt-1 text-muted">
                    Try a different search, or choose All to see every item again.
                    {archived.length > 0 && filter !== "archived" && " Archived items are under Archived."}
                  </p>
                </>
              )}
            </Card>
          ) : (
            <div role="table" aria-label="Recurring items" className="rounded-card border border-line bg-surface">
              <div role="rowgroup" className="hidden md:block">
                <div role="row" className={`grid border-b border-line px-5 py-3 text-sm font-semibold text-muted ${COLUMNS}`}>
                  {SORTS.map((column) => (
                    <SortHeader
                      key={column.key}
                      label={column.label}
                      right={"numeric" in column}
                      // The amount's own column is inset to line up with the boxed estimates beneath it.
                      className={column.key === "amount" ? "pr-2" : ""}
                      direction={sort.key === column.key ? (sort.descending ? "descending" : "ascending") : undefined}
                      onSort={() => sortBy(column.key)}
                    />
                  ))}
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

/**
 * A column heading that puts the list in order by that column when pressed, and turns the
 * order round when pressed again. The arrow shows which column and which way; `aria-sort`
 * says the same to a screen reader.
 */
function SortHeader({
  label,
  right,
  className = "",
  direction,
  onSort,
}: {
  label: string;
  /** Number columns keep their heading over the digits. */
  right: boolean;
  className?: string;
  direction: "ascending" | "descending" | undefined;
  onSort: () => void;
}) {
  const Arrow = direction === "descending" ? ArrowDown : ArrowUp;
  return (
    <span role="columnheader" aria-sort={direction ?? "none"} className={`min-w-0 ${right ? "text-right" : ""} ${className}`}>
      <button
        type="button"
        onClick={onSort}
        title={`Sort by ${label.toLowerCase()}`}
        className={`group inline-flex max-w-full items-center gap-1 rounded-md font-semibold ${direction ? "text-ink" : ""} ${
          right ? "flex-row-reverse" : ""
        }`}
      >
        {label}
        {/* Faint until hovered on the other columns, so every heading is seen to be pressable. */}
        <Arrow aria-hidden className={`size-3.5 shrink-0 ${direction ? "" : "opacity-0 group-hover:opacity-60 group-focus-visible:opacity-60"}`} />
      </button>
    </span>
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
        {item.paused && !item.archived && (
          <span className="rounded-md border border-line px-1.5 py-0.5 text-xs font-semibold">
            {item.pausedUntil ? `Paused until ${formatDay(item.pausedUntil)}` : "Paused"}
          </span>
        )}
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
