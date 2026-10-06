import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Holiday } from "../../src/api/holidays";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, fakeBackend, renderApp } from "../helpers";

const THANKSGIVING: Holiday = { id: 1, date: "2026-11-26", name: "Thanksgiving Day", source: "us_bank", enabled: true };
const COMPANY_DAY: Holiday = { id: 2, date: "2026-11-27", name: "Company day off", source: "user", enabled: true };
const VETERANS: Holiday = { id: 3, date: "2026-11-11", name: "Veterans Day", source: "us_bank", enabled: false };

describe("projection horizon", () => {
  it("is chosen from a list and saved", async () => {
    let saved = { ...DEFAULT_SETTINGS };
    const fetchMock = fakeBackend({
      "GET /api/settings": () => ({ status: 200, data: saved }),
      "PUT /api/settings/projection_horizon_months": (body) => {
        saved = { ...saved, projection_horizon_months: (body as { value: string }).value };
        return { status: 200, data: saved };
      },
    });
    renderApp("/settings");

    const horizon = await screen.findByLabelText("Look ahead");
    expect(horizon).toHaveValue("12");
    expect(within(horizon).getAllByRole("option").map((option) => option.textContent))
      .toEqual(["6 months", "12 months", "18 months", "24 months", "36 months"]);
    await userEvent.selectOptions(horizon, "24 months");

    await waitFor(() => expect(screen.getByLabelText("Look ahead")).toHaveValue("24"));
    expect(bodiesSentTo(fetchMock, "PUT /api/settings/projection_horizon_months")).toEqual([{ value: "24" }]);
  });
});

describe("bank holidays", () => {
  it("lists holidays, marking the user's own and any switched off", async () => {
    fakeBackend({ "GET /api/holidays": () => ({ status: 200, data: [VETERANS, THANKSGIVING, COMPANY_DAY] }) });
    renderApp("/settings");

    const card = await screen.findByRole("region", { name: "Bank holidays" });
    const thanksgiving = await within(card).findByRole("checkbox", { name: /Thanksgiving Day/ });
    expect(thanksgiving).toBeChecked();
    expect(thanksgiving).toHaveAccessibleName(/Thursday, Nov 26, 2026/);
    expect(within(card).getByRole("checkbox", { name: /Company day off/ })).toHaveAccessibleName(/added by you/);
    const veterans = within(card).getByRole("checkbox", { name: /Veterans Day/ });
    expect(veterans).not.toBeChecked();
    // Off is said in words as well as shown struck through.
    expect(veterans).toHaveAccessibleName(/switched off/);
  });

  it("switches one off without changing its date or name", async () => {
    const fetchMock = fakeBackend({
      "GET /api/holidays": () => ({ status: 200, data: [THANKSGIVING] }),
      "PUT /api/holidays/1": () => ({ status: 200, data: { saved: true } }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("checkbox", { name: /Thanksgiving Day/ }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/holidays/1")).toEqual([
        { date: "2026-11-26", name: "Thanksgiving Day", enabled: false },
      ]),
    );
  });

  it("adds a holiday of the user's own and clears the form", async () => {
    let holidays = [THANKSGIVING];
    const fetchMock = fakeBackend({
      "GET /api/holidays": () => ({ status: 200, data: holidays }),
      "POST /api/holidays": () => {
        holidays = [THANKSGIVING, COMPANY_DAY];
        return { status: 201, data: { saved: true } };
      },
    });
    renderApp("/settings");
    const card = await screen.findByRole("region", { name: "Bank holidays" });

    await userEvent.type(within(card).getByLabelText("Date"), "2026-11-27");
    await userEvent.type(within(card).getByLabelText("Holiday name"), "Company day off");
    await userEvent.click(within(card).getByRole("button", { name: "Add holiday" }));

    expect(await within(card).findByRole("checkbox", { name: /Company day off/ })).toBeChecked();
    expect(bodiesSentTo(fetchMock, "POST /api/holidays")).toEqual([{ date: "2026-11-27", name: "Company day off" }]);
    expect(within(card).getByLabelText("Holiday name")).toHaveValue("");
  });

  it("says so when the date is already a holiday", async () => {
    fakeBackend({
      "GET /api/holidays": () => ({ status: 200, data: [THANKSGIVING] }),
      "POST /api/holidays": () => ({ status: 409, error: "There's already a holiday on that date." }),
    });
    renderApp("/settings");
    const card = await screen.findByRole("region", { name: "Bank holidays" });

    await userEvent.type(within(card).getByLabelText("Date"), "2026-11-26");
    await userEvent.type(within(card).getByLabelText("Holiday name"), "Mine");
    await userEvent.click(within(card).getByRole("button", { name: "Add holiday" }));

    expect(await within(card).findByRole("alert")).toHaveTextContent("There's already a holiday on that date.");
    // What was typed is kept, so it can be corrected.
    expect(within(card).getByLabelText("Holiday name")).toHaveValue("Mine");
  });

  it("changes a holiday's date, keeping it switched on", async () => {
    const fetchMock = fakeBackend({
      "GET /api/holidays": () => ({ status: 200, data: [COMPANY_DAY] }),
      "PUT /api/holidays/2": () => ({ status: 200, data: { saved: true } }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Change Company day off, Nov 27, 2026" }));
    const date = screen.getAllByLabelText("Date")[0]!;
    expect(date).toHaveValue("2026-11-27");
    await userEvent.clear(date);
    await userEvent.type(date, "2026-12-24");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/holidays/2")).toEqual([
        { date: "2026-12-24", name: "Company day off", enabled: true },
      ]),
    );
  });

  it("removes a holiday", async () => {
    const fetchMock = fakeBackend({
      "GET /api/holidays": () => ({ status: 200, data: [THANKSGIVING] }),
      "DELETE /api/holidays/1": () => ({ status: 200, data: { saved: true } }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Remove Thanksgiving Day, Nov 26, 2026" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/holidays/1"));
  });

  it("has copy for when there are none", async () => {
    fakeBackend();
    renderApp("/settings");

    expect(await screen.findByText("No holidays coming up. Add one below.")).toBeInTheDocument();
  });
});
