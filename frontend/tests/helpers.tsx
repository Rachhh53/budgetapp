import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { vi } from "vitest";
import { App } from "../src/App";

type Handler = (body: unknown) => { status: number; data?: unknown; error?: string };

export const DEFAULT_SETTINGS = { theme: "system", accent_color: "#0f766e", projection_horizon_months: "12" };

/** What a brand-new install answers: default settings and nothing entered yet. */
const FRESH_INSTALL: Record<string, Handler> = {
  "GET /api/settings": () => ({ status: 200, data: DEFAULT_SETTINGS }),
  "GET /api/accounts": () => ({ status: 200, data: [] }),
  "GET /api/income-sources": () => ({ status: 200, data: [] }),
  "GET /api/paychecks": () => ({ status: 200, data: [] }),
  "GET /api/pay-periods": () => ({ status: 200, data: { primarySourceId: null, primaryAccountId: null, periods: [] } }),
  "GET /api/items": () => ({ status: 200, data: [] }),
  "GET /api/categories": () => ({ status: 200, data: [] }),
  "GET /api/tags": () => ({ status: 200, data: [] }),
  "GET /api/holidays": () => ({ status: 200, data: [] }),
};

/**
 * Replaces `fetch` with a tiny fake backend. Routes are keyed `"METHOD /path"` and are laid
 * over a fresh install; anything else answers 404, in the same envelope the real backend uses.
 */
export function fakeBackend(overrides: Record<string, Handler> = {}) {
  const routes = { ...FRESH_INSTALL, ...overrides };
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const handler = routes[`${init?.method ?? "GET"} ${path}`];
    const body: unknown = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    const { status, data = null, error = null } = handler?.(body) ?? { status: 404, error: "We couldn't find that." };
    return new Response(JSON.stringify({ data, error, status }), { status });
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
