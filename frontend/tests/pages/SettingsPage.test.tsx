import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Holiday } from "../../src/api/holidays";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, fakeBackend, renderApp, UNLOCKED } from "../helpers";

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

/** A backend that keeps whatever setting is saved, as the real one does. */
function savingSettings(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  let saved: Record<string, string> = { ...DEFAULT_SETTINGS };
  const save = (key: string) => (body: unknown) => {
    saved = { ...saved, [key]: (body as { value: string }).value };
    return { status: 200, data: saved };
  };
  return fakeBackend({
    "GET /api/settings": () => ({ status: 200, data: saved }),
    ...Object.fromEntries(Object.keys(DEFAULT_SETTINGS).map((key) => [`PUT /api/settings/${key}`, save(key)])),
    ...overrides,
  });
}

describe("appearance", () => {
  it("takes an accent color from the picker and applies it", async () => {
    const fetchMock = savingSettings();
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: /Accent color/ }));
    await userEvent.click(screen.getByRole("button", { name: /Raspberry/ }));

    await waitFor(() => expect(document.documentElement.style.getPropertyValue("--accent")).toBe("#be185d"));
    expect(bodiesSentTo(fetchMock, "PUT /api/settings/accent_color")).toEqual([{ value: "#be185d" }]);
  });
});

describe("pay periods follow", () => {
  const source = (id: number, name: string, primary: boolean) => ({ id, name, primary });

  it("lists the income sources and hands the pay periods to the one chosen", async () => {
    let primaryId = 1;
    const fetchMock = fakeBackend({
      "GET /api/income-sources": () => ({
        status: 200,
        data: [source(1, "Acme Corp", primaryId === 1), source(2, "Riverside Bakery", primaryId === 2)],
      }),
      "PUT /api/income-sources/2/primary": () => {
        primaryId = 2;
        return { status: 200, data: source(2, "Riverside Bakery", true), changed: true };
      },
    });
    renderApp("/settings");

    const follow = await screen.findByLabelText("Pay periods follow");
    await waitFor(() => expect(follow).toHaveValue("1"));
    expect(within(follow).getAllByRole("option").map((option) => option.textContent)).toEqual(["Acme Corp", "Riverside Bakery"]);
    await userEvent.selectOptions(follow, "Riverside Bakery");

    await waitFor(() => expect(screen.getByLabelText("Pay periods follow")).toHaveValue("2"));
    expect(calls(fetchMock)).toContain("PUT /api/income-sources/2/primary");
  });

  it("says where to start when there is no paycheck yet", async () => {
    fakeBackend();
    renderApp("/settings");

    expect(await screen.findByText(/Add a paycheck on the/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Income screen" })).toHaveAttribute("href", "/income");
    expect(screen.queryByLabelText("Pay periods follow")).not.toBeInTheDocument();
  });
});

describe("warnings", () => {
  it("chooses how early a card promo warning starts", async () => {
    const fetchMock = savingSettings();
    renderApp("/settings");

    const promo = await screen.findByLabelText("Warn before a card promo ends");
    expect(promo).toHaveValue("60");
    await userEvent.selectOptions(promo, "90 days before");

    await waitFor(() => expect(screen.getByLabelText("Warn before a card promo ends")).toHaveValue("90"));
    expect(bodiesSentTo(fetchMock, "PUT /api/settings/promo_warning_days")).toEqual([{ value: "90" }]);
  });

  it("chooses how far ahead unpaid bills are pointed out, or not at all", async () => {
    const fetchMock = savingSettings();
    renderApp("/settings");

    const unpaid = await screen.findByLabelText("Highlight unpaid bills");
    expect(within(unpaid).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Don't highlight", "1 day before", "2 days before", "3 days before", "5 days before", "7 days before", "10 days before", "14 days before",
    ]);
    await userEvent.selectOptions(unpaid, "7 days before");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/settings/unpaid_highlight_days")).toEqual([{ value: "7" }]));
  });

  it("points to the account's page for the low balance warning", async () => {
    fakeBackend();
    renderApp("/settings");

    expect(await screen.findByText("Low balance warning")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "the account's own page" })).toHaveAttribute("href", "/accounts");
  });
});

describe("security", () => {
  const WITH_PIN = { ...UNLOCKED, pinSet: true };
  const CODE = "K7QM-2XHP-9FTB-WRNC";

  it("offers to set a PIN when there is none, and nothing that needs one", async () => {
    fakeBackend();
    renderApp("/settings");

    const card = await screen.findByRole("region", { name: "Security" });
    expect(within(card).getByText(/No PIN is set/)).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Set a PIN" })).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Change PIN" })).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Make new code" })).not.toBeInTheDocument();
    expect(within(card).getByText(/Takes effect once a PIN is set/)).toBeInTheDocument();
  });

  it("changes the PIN, asking for the one in use", async () => {
    const fetchMock = fakeBackend({
      "GET /api/session": () => ({ status: 200, data: WITH_PIN }),
      "PUT /api/pin": () => ({ status: 200, data: { recoveryCode: null, session: WITH_PIN } }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Change PIN" }));
    const dialog = screen.getByRole("dialog", { name: "Change your PIN" });
    await userEvent.type(within(dialog).getByLabelText("PIN you use now"), "4829");
    await userEvent.type(within(dialog).getByLabelText("New PIN"), "1357");
    await userEvent.type(within(dialog).getByLabelText("New PIN again"), "1357");
    await userEvent.click(within(dialog).getByRole("button", { name: "Change PIN" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/pin")).toEqual([{ currentPin: "4829", newPin: "1357" }]);
  });

  it("shows the backend's message when the PIN in use didn't match, and stays open", async () => {
    fakeBackend({
      "GET /api/session": () => ({ status: 200, data: WITH_PIN }),
      "PUT /api/pin": () => ({ status: 403, error: "That isn't the PIN in use now. Please try again." }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Change PIN" }));
    const dialog = screen.getByRole("dialog", { name: "Change your PIN" });
    await userEvent.type(within(dialog).getByLabelText("PIN you use now"), "0000");
    await userEvent.type(within(dialog).getByLabelText("New PIN"), "1357");
    await userEvent.type(within(dialog).getByLabelText("New PIN again"), "1357");
    await userEvent.click(within(dialog).getByRole("button", { name: "Change PIN" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("That isn't the PIN in use now.");
    // Still unlocked: a wrong PIN here is not the lock screen.
    expect(screen.getByRole("heading", { level: 1, name: "Settings" })).toBeInTheDocument();
  });

  it("removes the PIN only with the PIN, and keeping it is the easy way out", async () => {
    const fetchMock = fakeBackend({
      "GET /api/session": () => ({ status: 200, data: WITH_PIN }),
      "DELETE /api/pin": () => ({ status: 200, data: UNLOCKED }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Remove PIN" }));
    await userEvent.click(screen.getByRole("button", { name: "Keep my PIN" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("DELETE /api/pin");

    await userEvent.click(screen.getByRole("button", { name: "Remove PIN" }));
    const dialog = screen.getByRole("dialog", { name: "Remove your PIN" });
    expect(within(dialog).getByRole("button", { name: "Remove PIN" })).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText("PIN you use now"), "4829");
    await userEvent.click(within(dialog).getByRole("button", { name: "Remove PIN" }));

    expect(await screen.findByRole("button", { name: "Set a PIN" })).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "DELETE /api/pin")).toEqual([{ currentPin: "4829" }]);
  });

  it("makes a new recovery code and shows it once", async () => {
    const fetchMock = fakeBackend({
      "GET /api/session": () => ({ status: 200, data: WITH_PIN }),
      "POST /api/pin/recovery-code": () => ({ status: 200, data: { recoveryCode: CODE, session: WITH_PIN } }),
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "Make new code" }));
    const ask = screen.getByRole("dialog", { name: "Make a new recovery code" });
    await userEvent.type(within(ask).getByLabelText("Your PIN"), "4829");
    await userEvent.click(within(ask).getByRole("button", { name: "Make new code" }));

    const shown = await screen.findByRole("dialog", { name: "Your new recovery code" });
    expect(within(shown).getByTestId("recovery-code")).toHaveTextContent(CODE);
    expect(bodiesSentTo(fetchMock, "POST /api/pin/recovery-code")).toEqual([{ currentPin: "4829" }]);
    await userEvent.click(within(shown).getByRole("checkbox", { name: "I've saved my recovery code" }));
    await userEvent.click(within(shown).getByRole("button", { name: "Done" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("chooses how long before it locks", async () => {
    const fetchMock = savingSettings();
    renderApp("/settings");

    const lockAfter = await screen.findByLabelText("Lock after");
    expect(within(lockAfter).getAllByRole("option").map((option) => option.textContent))
      .toEqual(["1 minute", "5 minutes", "15 minutes", "30 minutes", "60 minutes"]);
    await userEvent.selectOptions(lockAfter, "5 minutes");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/settings/auto_lock_minutes")).toEqual([{ value: "5" }]));
  });

  it("ticks Start with amounts hidden", async () => {
    const fetchMock = savingSettings();
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("checkbox", { name: "Start with amounts hidden" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/settings/start_amounts_hidden")).toEqual([{ value: "true" }]));
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Start with amounts hidden" })).toBeChecked());
  });

  it("opens with amounts hidden when that is ticked", async () => {
    fakeBackend({ "GET /api/settings": () => ({ status: 200, data: { ...DEFAULT_SETTINGS, start_amounts_hidden: "true" } }) });
    renderApp();

    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-hide-amounts"));
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
