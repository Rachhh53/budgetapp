import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { EntryKind } from "./entries";
import { PAY_KEY } from "./income";
import type { ItemKind } from "./items";

/** The fewest characters a search needs; the backend refuses fewer. */
export const SEARCH_MIN_LENGTH = 2;
export const SEARCH_MAX_LENGTH = 100;

/** One time a recurring item happens. */
export interface SearchWhen {
  date: string;
  amountCents: number;
  estimate: boolean;
  /** The last time only: ticked as paid, or on autopay (taken as paid on its day). */
  paid: boolean;
  /** The pay period that lists it; `null` when no pay period holds it. */
  periodStart: string | null;
}

export interface RecurringMatch {
  id: number;
  name: string;
  kind: ItemKind;
  payFromAccountId: number;
  /** The latest time it happened, up to today; `null` when it has not yet. */
  last: SearchWhen | null;
  /** The next time still to pay; `null` when nothing more is planned. */
  next: SearchWhen | null;
}

export interface OneOffMatch {
  id: number;
  kind: EntryKind;
  description: string;
  payee: string | null;
  date: string;
  amountCents: number;
  estimate: boolean;
  accountId: number;
  /** Dated after today: planned, not yet happened. */
  upcoming: boolean;
  periodStart: string | null;
}

export interface SearchResults {
  recurring: RecurringMatch[];
  /** Newest first. */
  oneOffs: OneOffMatch[];
  /** More one-offs matched than are listed. */
  more: boolean;
}

/**
 * One-offs and recurring items whose name (or a one-off's payee) holds `text`. Sent as a POST so
 * what is typed never sits in an address; it changes nothing. Waits until there is enough to search for.
 */
export function useSearch(text: string) {
  const trimmed = text.trim();
  return useQuery({
    queryKey: [...PAY_KEY, "search", trimmed],
    queryFn: () => api.post<SearchResults>("/api/search", { text: trimmed }),
    enabled: trimmed.length >= SEARCH_MIN_LENGTH && trimmed.length <= SEARCH_MAX_LENGTH,
    // The list stays put while the next letter is looked up, instead of blinking away.
    placeholderData: (previous) => previous,
  });
}

/** What a pay period row is found by, in the address (`?find=`) and on the row. */
export function foundEntry(id: number): string {
  return `entry-${id}`;
}

export function foundOccurrence(itemId: number, dueDate: string): string {
  return `item-${itemId}-${dueDate}`;
}
