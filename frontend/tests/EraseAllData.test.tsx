import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, renderApp } from "./helpers";

const DEFAULTS = { theme: "system", accent_color: "#0f766e", projection_horizon_months: "12" };

function calls(fetchMock: ReturnType<typeof fakeBackend>): string[] {
  return fetchMock.mock.calls.map(([path, init]) => `${init?.method ?? "GET"} ${path}`);
}

describe("erase all data", () => {
  it("asks first, and sends nothing until the person confirms", async () => {
    const fetchMock = fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Erase all data…" }));

    expect(screen.getByRole("alertdialog")).toHaveTextContent("This can't be undone");
    expect(calls(fetchMock)).not.toContain("DELETE /api/data");
  });

  it("puts the focus on keeping the data, so Enter by reflex is safe", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Erase all data…" }));

    expect(screen.getByRole("button", { name: "Keep my data" })).toHaveFocus();
  });

  it("keeps everything when the person backs out", async () => {
    const fetchMock = fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULTS }) });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Erase all data…" }));
    await userEvent.click(screen.getByRole("button", { name: "Keep my data" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("DELETE /api/data");
  });

  it("erases on confirmation, says so, and reloads the fresh settings", async () => {
    let settings = { ...DEFAULTS, theme: "dark" };
    let confirmation: unknown;
    const fetchMock = fakeBackend({
      "GET /api/settings": () => ({ status: 200, data: settings }),
      "DELETE /api/data": (body) => {
        confirmation = body;
        settings = DEFAULTS;
        return { status: 200, data: { erased: true } };
      },
    });
    renderApp("/settings");
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));

    await userEvent.click(await screen.findByRole("button", { name: "Erase all data…" }));
    await userEvent.click(screen.getByRole("button", { name: "Yes, erase everything" }));

    expect(await screen.findByRole("status")).toHaveTextContent("your data has been erased");
    expect(confirmation).toEqual({ confirm: "erase-all-data" });
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    // The saved "dark" theme went with the data; the app is back on its defaults.
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("light"));
    expect(calls(fetchMock).filter((call) => call === "GET /api/settings").length).toBeGreaterThan(1);
  });

  it("explains kindly when erasing fails, and leaves the choice open", async () => {
    fakeBackend({
      "GET /api/settings": () => ({ status: 200, data: DEFAULTS }),
      "DELETE /api/data": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe." }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Erase all data…" }));
    await userEvent.click(screen.getByRole("button", { name: "Yes, erase everything" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Your data is safe");
    expect(screen.getByRole("button", { name: "Keep my data" })).toBeEnabled();
  });
});
