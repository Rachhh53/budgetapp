import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { vi } from "vitest";
import { App } from "../src/App";

/** `changed` marks the answer the way the backend marks a change that can be undone. */
/** `file` answers with that text as a file to save, the way the backend sends one, in place of the usual envelope. */
type Handler = (body: unknown) => { status: number; data?: unknown; error?: string; changed?: boolean; file?: string };

export const DEFAULT_SETTINGS = {
  theme: "system",
  accent_color: "#0f766e",
  projection_horizon_months: "12",
  promo_warning_days: "60",
  auto_lock_minutes: "15",
  start_amounts_hidden: "false",
  // Off, so no test depends on how near today is to a fixture's due date; tests of it turn it on.
  unpaid_highlight_days: "0",
  backup_folder: "",
  backup_keep_count: "30",
};

/** Backups before any has been made, kept in the app's own folder. */
export const NO_BACKUPS = {
  folder: "C:\\Users\\Amara\\AppData\\Roaming\\Budget\\backups",
  usingDefaultFolder: true,
  keepCount: 30,
  backups: [],
  lastBackupAt: null,
  lastDailyBackupFailed: false,
};

/** Open, with no PIN, and the offer to set one already answered: how most tests want the app. */
export const UNLOCKED = { unlocked: true, pinSet: false, retryAfterSeconds: 0, offerPin: false };

/** What a brand-new install answers: default settings and nothing entered yet. */
const FRESH_INSTALL: Record<string, Handler> = {
  "GET /api/session": () => ({ status: 200, data: UNLOCKED }),
  "GET /api/settings": () => ({ status: 200, data: DEFAULT_SETTINGS }),
  "GET /api/accounts": () => ({ status: 200, data: [] }),
  "GET /api/income-sources": () => ({ status: 200, data: [] }),
  "GET /api/paychecks": () => ({ status: 200, data: [] }),
  "GET /api/pay-periods": () => ({ status: 200, data: { primarySourceId: null, primaryAccountId: null, periods: [] } }),
  "GET /api/items": () => ({ status: 200, data: [] }),
  "GET /api/buckets": () => ({ status: 200, data: [] }),
  "GET /api/categories": () => ({ status: 200, data: [] }),
  "GET /api/tags": () => ({ status: 200, data: [] }),
  "GET /api/holidays": () => ({ status: 200, data: [] }),
  "GET /api/assets": () => ({ status: 200, data: [] }),
  "GET /api/net-worth": () => ({ status: 200, data: NOTHING_OWNED }),
  "GET /api/cards": () => ({ status: 200, data: [] }),
  "GET /api/backups": () => ({ status: 200, data: NO_BACKUPS }),
};

/** Net worth before anything is entered. */
export const NOTHING_OWNED = {
  date: "2026-10-09",
  netWorthCents: 0,
  assetsCents: 0,
  liabilitiesCents: 0,
  cashCents: 0,
  investmentsCents: 0,
  propertyCents: 0,
  cardsCents: 0,
  loansCents: 0,
};

/** Any account's chart and transactions: on a fresh install there is nothing to show, whichever account it is. */
const NO_PROJECTION = /^GET \/api\/accounts\/\d+\/(projection|transactions)(\?.*)?$/;
const nothingProjected: Handler = () => ({ status: 200, data: [] });

/** Any account's year so far and balance-by-balance changes: nothing to say until a test gives some. */
const NO_PERFORMANCE = /^GET \/api\/accounts\/\d+\/performance$/;
const nothingToCompare: Handler = () => ({ status: 200, data: { thisYear: null, balances: [] } });

/** Any income source's year: nothing has arrived until a test says so. */
const NO_YEAR = /^GET \/api\/income-sources\/\d+\/this-year$/;
const nothingThisYear: Handler = () => ({
  status: 200,
  data: { year: 2026, soFarCents: 0, paychecksSoFar: 0, wholeYearCents: 0, paychecksInYear: 0, estimate: false },
});

/** Any loan before its terms are entered. */
const NO_LOAN = /^GET \/api\/accounts\/\d+\/loan$/;
const noLoanTerms: Handler = () => ({
  status: 200,
  data: {
    settings: null,
    rateBp: null,
    balanceCents: null,
    standardPaymentCents: null,
    nextPayment: null,
    payoffDate: null,
    interestRemainingCents: null,
    paysOff: true,
    paidBy: [],
    years: [],
  },
});

/**
 * Replaces `fetch` with a tiny fake backend. Routes are keyed `"METHOD /path"` and are laid
 * over a fresh install; anything else answers 404, in the same envelope the real backend uses.
 */
export function fakeBackend(overrides: Record<string, Handler> = {}) {
  const routes = { ...FRESH_INSTALL, ...overrides };
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    // A route can be given with its exact query, or without one to answer whatever is asked.
    const handler =
      routes[`${method} ${path}`] ?? routes[`${method} ${path.split("?")[0]}`] ?? (NO_PROJECTION.test(`${method} ${path}`) ? nothingProjected : NO_PERFORMANCE.test(`${method} ${path}`) ? nothingToCompare : NO_YEAR.test(`${method} ${path}`) ? nothingThisYear : NO_LOAN.test(`${method} ${path}`) ? noLoanTerms : undefined);
    const body: unknown = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    const answer: ReturnType<Handler> = handler?.(body) ?? { status: 404, error: "We couldn't find that." };
    const { status, data = null, error = null } = answer;
    if (answer.file !== undefined) {
      return new Response(answer.file, {
        status,
        headers: { "Content-Type": "application/octet-stream", "Content-Disposition": "attachment; filename*=UTF-8''report.csv" },
      });
    }
    return new Response(JSON.stringify({ data, error, status }), {
      status,
      headers: { "Content-Type": "application/json", ...(answer.changed ? { "X-Budget-Changed": "true" } : {}) },
    });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** Every request the app made, as `"METHOD /path"`, in order. */
export function calls(fetchMock: ReturnType<typeof fakeBackend>): string[] {
  return fetchMock.mock.calls.map(([path, init]) => `${init?.method ?? "GET"} ${path}`);
}

/** The JSON bodies sent to one route, in order. */
export function bodiesSentTo(fetchMock: ReturnType<typeof fakeBackend>, route: string): unknown[] {
  return fetchMock.mock.calls
    .filter(([path, init]) => `${init?.method ?? "GET"} ${path}` === route)
    .map(([, init]) => (typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined));
}

export function renderApp(startAt = "/") {
  // retry: false so an error state shows immediately instead of after a backoff.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[startAt]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
