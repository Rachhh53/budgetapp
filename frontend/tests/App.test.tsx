import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { contrastRatio } from "../src/lib/color";
import { calls, fakeBackend, renderApp } from "./helpers";

const DEFAULTS = { theme: "system", accent_color: "#0f766e", projection_horizon_months: "12" };

describe("app shell", () => {
  it("shows the sidebar with every screen and the privacy note", () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp();

    const sidebar = screen.getAllByRole("navigation", { name: "Main" })[0]!;
    expect(within(sidebar).getByRole("link", { name: "Pay periods" })).toHaveAttribute("aria-current", "page");
    expect(within(sidebar).getAllByRole("link")).toHaveLength(13);
    expect(screen.getByText("Data stored only on this PC")).toBeInTheDocument();
  });

  it("moves between screens", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp();

    await userEvent.click(screen.getAllByRole("link", { name: "Dashboard" })[0]!);

    expect(screen.getByRole("heading", { level: 1, name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByText("This screen is on its way.")).toBeInTheDocument();
  });

  it("offers the remaining screens behind More on a phone", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp();

    const more = screen.getByRole("button", { name: "More" });
    expect(more).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(more);

    const sheet = screen.getByRole("dialog", { name: "More screens" });
    await userEvent.click(within(sheet).getByRole("link", { name: "Income" }));

    expect(screen.getByRole("heading", { level: 1, name: "Income" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("has a kind message for an address that is not a screen", () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp("/nowhere");

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("We couldn't find that screen");
  });
});

describe("unlocking", () => {
  it("starts a session when the backend says the app is locked, then carries on", async () => {
    let unlocked = false;
    const fetchMock = fakeBackend({
      "GET /api/settings": () => (unlocked ? { status: 200, data: DEFAULTS } : { status: 401, error: "Locked" }),
      "POST /api/session": () => {
        unlocked = true;
        return { status: 201, data: { unlocked: true } };
      },
    });
    renderApp("/settings");

    expect(await screen.findByLabelText("Theme")).toHaveValue("system");
    // Other cards on the screen load their own data alongside; follow the settings request only.
    expect(calls(fetchMock).filter((call) => call !== "GET /api/holidays")).toEqual([
      "GET /api/settings",
      "POST /api/session",
      "GET /api/settings",
    ]);
  });
});

describe("theme", () => {
  it("applies the saved theme and accent color to the page", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: { theme: "dark", accent_color: "#f4c95d" } }) });
    renderApp();

    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
    expect(document.documentElement.style.getPropertyValue("--accent")).toBe("#f4c95d");
    // A light accent gets dark text on top so it stays readable.
    expect(document.documentElement.style.getPropertyValue("--on-accent")).toBe("#000000");
  });

  it("keeps accent-colored text readable on the dark theme with the default accent", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: { ...DEFAULTS, theme: "dark" } }) });
    renderApp();

    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
    const text = document.documentElement.style.getPropertyValue("--accent-text");
    // The fill keeps the chosen color; only text drawn in it is lightened.
    expect(document.documentElement.style.getPropertyValue("--accent")).toBe("#0f766e");
    expect(contrastRatio(text, "#1a202b")).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(text, "#11151c")).toBeGreaterThanOrEqual(4.5);
  });

  it("follows Windows until told otherwise", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp();

    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("light"));
  });

  it("saves a new theme picked from the dropdown and applies it", async () => {
    let saved = { ...DEFAULTS };
    const fetchMock = fakeBackend({
      "GET /api/settings": () => ({ status: 200, data: saved }),
      "PUT /api/settings/theme": (body) => {
        saved = { ...saved, theme: (body as { value: string }).value };
        return { status: 200, data: saved };
      },
    });
    renderApp("/settings");

    await userEvent.selectOptions(await screen.findByLabelText("Theme"), "Dark");

    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
    expect(fetchMock).toHaveBeenCalledWith("/api/settings/theme", expect.objectContaining({ method: "PUT" }));
  });

  it("shows the backend's message when settings cannot be loaded", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 500, error: "Something went wrong on our side." }) });
    renderApp("/settings");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side.");
  });

  it("explains when the app on the PC cannot be reached", async () => {
    fakeBackend({});
    globalThis.fetch = () => Promise.reject(new TypeError("Failed to fetch"));
    renderApp("/settings");

    // Every card on the screen that needs the backend says so.
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent("We couldn't reach Budget on your PC");
  });
});
