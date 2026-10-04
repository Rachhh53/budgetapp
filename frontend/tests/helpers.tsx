import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { vi } from "vitest";
import { App } from "../src/App";

type Handler = (body: unknown) => { status: number; data?: unknown; error?: string };

/**
 * Replaces `fetch` with a tiny fake backend. Routes are keyed `"METHOD /path"`;
 * anything unlisted answers 404, in the same envelope the real backend uses.
 */
export function fakeBackend(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const handler = routes[`${init?.method ?? "GET"} ${path}`];
    const body: unknown = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    const { status, data = null, error = null } = handler?.(body) ?? { status: 404, error: "We couldn't find that." };
    return new Response(JSON.stringify({ data, error, status }), { status });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
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
