import { Search } from "lucide-react";
import { type KeyboardEvent, useEffect, useId, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { useAccounts } from "../../api/accounts";
import {
  foundEntry,
  foundOccurrence,
  type OneOffMatch,
  type RecurringMatch,
  SEARCH_MAX_LENGTH,
  SEARCH_MIN_LENGTH,
  useSearch,
} from "../../api/search";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { INPUT } from "../ui";

/** The address parameter naming the row a search led to, which the pay period screen points out. */
export const FIND_PARAM = "find";

/** How long typing has to pause before the search is sent, so each letter is not a request. */
const PAUSE_MS = 250;

/** One place a result leads: a pay period, with the row to point out there. */
interface Choice {
  key: string;
  title: string;
  detail: string;
  amountCents: number;
  periodStart: string | null;
  find: string;
}

function oneOffChoice(entry: OneOffMatch, accountName: (id: number) => string): Choice {
  const verb = entry.upcoming
    ? "Planned"
    : entry.kind === "money_in"
      ? "Came in"
      : entry.kind === "transfer"
        ? "Moved"
        : "Paid";
  return {
    key: `entry-${entry.id}`,
    title: entry.description,
    detail: `One-off · ${verb} ${formatDate(entry.date)} · ${accountName(entry.accountId)}`,
    amountCents: entry.amountCents,
    periodStart: entry.periodStart,
    find: foundEntry(entry.id),
  };
}

/** A recurring item leads to two places: the last time it happened and the next time it is planned. */
function recurringChoices(item: RecurringMatch): Choice[] {
  const choices: Choice[] = [];
  if (item.last !== null) {
    choices.push({
      key: `item-${item.id}-last`,
      title: item.name,
      detail: item.last.paid ? `Last paid ${formatDate(item.last.date)}` : `Last due ${formatDate(item.last.date)} · not marked paid`,
      amountCents: item.last.amountCents,
      periodStart: item.last.periodStart,
      find: foundOccurrence(item.id, item.last.date),
    });
  }
  if (item.next !== null) {
    choices.push({
      key: `item-${item.id}-next`,
      title: item.name,
      detail: `Next planned ${formatDate(item.next.date)}`,
      amountCents: item.next.amountCents,
      periodStart: item.next.periodStart,
      find: foundOccurrence(item.id, item.next.date),
    });
  }
  return choices;
}

/**
 * Finding a one-off or a recurring item from the pay period screen: when it last happened and when
 * it is next planned, each opening the pay period that lists it with the row pointed out.
 * Ctrl+K (⌘K on a Mac) puts the cursor in the box from anywhere on the screen.
 */
export function PeriodSearch() {
  const [typed, setTyped] = useState("");
  const [sent, setSent] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { data: accounts } = useAccounts();
  const search = useSearch(sent);

  useEffect(() => {
    const timer = window.setTimeout(() => setSent(typed), PAUSE_MS);
    return () => window.clearTimeout(timer);
  }, [typed]);

  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && !event.shiftKey && !event.altKey && event.key.toLowerCase() === "k") {
        event.preventDefault();
        inputRef.current?.focus();
        setOpen(true);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  // A click anywhere else puts the list away, as it does for every other list that floats.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && !boxRef.current?.contains(event.target)) {
        setOpen(false);
      }
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const accountName = (id: number) => accounts?.find((account) => account.id === id)?.name ?? "an account";
  const results = search.data;
  const recurring = results?.recurring.flatMap(recurringChoices) ?? [];
  const oneOffs = results?.oneOffs.map((entry) => oneOffChoice(entry, accountName)) ?? [];
  const choices = [...recurring, ...oneOffs].filter((choice) => choice.periodStart !== null);
  const enough = typed.trim().length >= SEARCH_MIN_LENGTH;

  const go = (choice: Choice) => {
    const next = new URLSearchParams(params);
    next.set(FIND_PARAM, choice.find);
    setOpen(false);
    void navigate(`/periods/${choice.periodStart}?${next.toString()}`);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") {
      // First Escape puts the list away; the next one empties the box.
      if (open) {
        setOpen(false);
      } else {
        setTyped("");
      }
      event.stopPropagation();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
      if (choices.length > 0) {
        const step = event.key === "ArrowDown" ? 1 : -1;
        setActive((at) => (at + step + choices.length) % choices.length);
      }
    } else if (event.key === "Enter" && open && choices[active] !== undefined) {
      event.preventDefault();
      go(choices[active]);
    }
  };

  const optionId = (index: number) => `${listId}-${index}`;
  const showList = open && typed.trim() !== "";

  return (
    <div ref={boxRef} className="relative w-full sm:w-64 lg:w-auto lg:max-w-64 lg:min-w-44 lg:flex-1">
      <label className="sr-only" htmlFor={`${listId}-input`}>
        Find a bill or one-off
      </label>
      <Search aria-hidden className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" />
      <input
        ref={inputRef}
        id={`${listId}-input`}
        type="search"
        role="combobox"
        aria-expanded={showList}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={showList && choices[active] !== undefined ? optionId(active) : undefined}
        value={typed}
        maxLength={SEARCH_MAX_LENGTH}
        onChange={(event) => {
          setTyped(event.target.value);
          setActive(0);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        placeholder="Find a bill or one-off"
        autoComplete="off"
        className={`${INPUT} w-full pl-9`}
      />
      {showList && (
        <div className="absolute top-full right-0 z-40 mt-2 max-h-[70vh] w-[min(28rem,calc(100vw-2rem))] overflow-y-auto rounded-card border border-line bg-surface p-2 shadow-lg">
          {!enough ? (
            <p className="p-2 text-sm text-muted">Type at least {SEARCH_MIN_LENGTH} letters to search.</p>
          ) : search.isError ? (
            <p role="alert" className="p-2 text-sm">
              We couldn't search just now. Please try again in a moment.
            </p>
          ) : results === undefined || search.isPending ? (
            <p className="p-2 text-sm text-muted">Looking…</p>
          ) : choices.length === 0 ? (
            <p className="p-2 text-sm text-muted">Nothing matches “{typed.trim()}”. Try part of the name or who it was paid to.</p>
          ) : (
            <ul id={listId} role="listbox" aria-label="What was found" className="flex flex-col">
              {choices.map((choice, index) => (
                <li
                  key={choice.key}
                  id={optionId(index)}
                  role="option"
                  aria-selected={index === active}
                  onPointerEnter={() => setActive(index)}
                  onClick={() => go(choice)}
                  className={`flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 ${index === active ? "bg-bg" : ""}`}
                >
                  <div className="min-w-0 flex-1 leading-tight">
                    <div className="font-medium wrap-break-word">{choice.title}</div>
                    <div className="text-sm text-muted">{choice.detail}</div>
                  </div>
                  <span data-amount className="shrink-0 tabular-nums">
                    {formatCents(choice.amountCents)}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {results?.more && enough && (
            <p className="border-t border-line p-2 text-sm text-muted">Only the newest one-offs are listed. Type more of the name to narrow it down.</p>
          )}
        </div>
      )}
    </div>
  );
}
