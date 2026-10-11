import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Asset, AssetDetail, NetWorth } from "../../src/api/assets";
import { todayIso } from "../../src/lib/dates";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: "Coastal Credit Union",
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: { id: 10, date: "2026-10-02", amountCents: 284512, note: null },
};
const VISA: Account = { ...CHECKING, id: 2, name: "Travel Rewards Visa", institution: null, type: "credit_card", latestBalance: null };
const MORTGAGE: Account = {
  ...CHECKING,
  id: 3,
  name: "Maple Street mortgage",
  institution: null,
  type: "loan",
  subtypeLabel: "Mortgage",
  latestBalance: { id: 12, date: "2026-10-01", amountCents: 28500000, note: null },
};

const HOME: Asset = {
  id: 5,
  name: "Maple Street house",
  kind: "home",
  color: "#0369a1",
  appreciationBp: 300,
  linkedLoanAccountId: 3,
  archived: false,
  latestValue: { id: 20, date: "2026-01-01", valueCents: 40000000 },
  valueCents: 40896666,
  estimate: true,
  owedCents: 28500000,
  equityCents: 12396666,
};
const CAR: Asset = {
  ...HOME,
  id: 6,
  name: "Outback",
  kind: "vehicle",
  appreciationBp: null,
  linkedLoanAccountId: null,
  latestValue: { id: 21, date: "2026-06-01", valueCents: 1850000 },
  valueCents: 1850000,
  estimate: false,
  owedCents: null,
  equityCents: 1850000,
};
const HOME_DETAIL: AssetDetail = {
  asset: HOME,
  values: [
    { id: 20, date: "2026-01-01", valueCents: 40000000 },
    { id: 19, date: "2025-01-01", valueCents: 38000000 },
  ],
};

const WORTH: NetWorth = {
  date: "2026-10-09",
  netWorthCents: 14531178,
  assetsCents: 43031178,
  liabilitiesCents: 28500000,
  cashCents: 284512,
  investmentsCents: 0,
  propertyCents: 42746666,
  cardsCents: 0,
  loansCents: 28500000,
};

function withAssets(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, VISA, MORTGAGE] }),
    "GET /api/assets": () => ({ status: 200, data: [HOME, CAR] }),
    "GET /api/assets/5": () => ({ status: 200, data: HOME_DETAIL }),
    "GET /api/net-worth": () => ({ status: 200, data: WORTH }),
    ...overrides,
  });
}

describe("accounts screen: net worth", () => {
  it("shows net worth with what is owned and owed, and what each is made of", async () => {
    withAssets();
    renderApp("/accounts");

    const worth = await screen.findByRole("region", { name: "Net worth" });
    expect(within(worth).getByText("$145,311.78")).toBeInTheDocument();
    expect(worth).toHaveTextContent("today");
    expect(within(worth).getByText("$430,311.78")).toBeInTheDocument();
    // Each total lists its parts under it, a label and an amount to a line.
    const owned = within(worth).getByLabelText("What makes up what you own");
    expect(within(owned).getByText("Cash").parentElement).toHaveTextContent("Cash$2,845.12");
    expect(within(owned).getByText("Invested").parentElement).toHaveTextContent("Invested$0.00");
    expect(within(owned).getByText("Home & assets").parentElement).toHaveTextContent("Home & assets$427,466.66");
    const owed = within(worth).getByLabelText("What makes up what you owe");
    expect(within(owed).getByText("Cards").parentElement).toHaveTextContent("Cards$0.00");
    expect(within(owed).getByText("Loans").parentElement).toHaveTextContent("Loans$285,000.00");
  });

  it("says so kindly when net worth cannot be worked out, and still lists the accounts", async () => {
    withAssets({ "GET /api/net-worth": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }) });
    renderApp("/accounts");

    expect(await screen.findByText(/Your data is safe/)).toBeInTheDocument();
    expect(await screen.findByRole("region", { name: "Checking & savings" })).toBeInTheDocument();
  });

  it("leaves net worth out until there is something to add up", async () => {
    fakeBackend();
    renderApp("/accounts");

    expect(await screen.findByText("No accounts yet.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Net worth" })).not.toBeInTheDocument();
    // Nothing to update yet either.
    expect(screen.queryByRole("button", { name: "Update balances" })).not.toBeInTheDocument();
  });
});

describe("accounts screen: home & assets", () => {
  it("lists each asset with its value, marking one grown by an assumed rate as an estimate", async () => {
    withAssets();
    renderApp("/accounts");

    const group = await screen.findByRole("region", { name: "Home & assets" });
    const home = within(group).getByRole("link", { name: /Maple Street house/ });
    expect(home).toHaveAttribute("href", "/assets/5");
    expect(home).toHaveTextContent("Home · equity $123,966.66");
    expect(home).toHaveTextContent("$408,966.66 (estimate)");
    expect(home).toHaveTextContent("entered Jan 1");
    const car = within(group).getByRole("link", { name: /Outback/ });
    expect(car).toHaveTextContent("Vehicle");
    expect(car).not.toHaveTextContent("(estimate)");
    // A row does one thing, open the asset's page: editing starts there, so no button sits on the row.
    expect(within(group).queryByRole("button")).not.toBeInTheDocument();
  });

  it("adds a home with its value, its loan and an assumed rate", async () => {
    const fetchMock = withAssets({ "POST /api/assets": () => ({ status: 201, data: HOME_DETAIL, changed: true }) });
    renderApp("/accounts");
    await screen.findByRole("region", { name: "Home & assets" });

    await userEvent.click(screen.getByRole("button", { name: "+ Add home or asset" }));
    const dialog = screen.getByRole("dialog", { name: "Add a home or asset" });
    expect(within(dialog).getByRole("radio", { name: "Home" })).toBeChecked();
    for (const section of ["What it is worth", "What it is", "Loan and growth"]) {
      expect(within(dialog).getByRole("group", { name: section })).toBeInTheDocument();
    }
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Value" }), "400,000");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Maple Street house");
    // Only loan accounts are offered.
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Loan against it (optional)" }), "maple{Enter}");
    await userEvent.type(within(dialog).getByLabelText("Assumed growth a year, % (optional)"), "3");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/assets")).toEqual([
      {
        name: "Maple Street house",
        kind: "home",
        color: expect.stringMatching(/^#[0-9a-f]{6}$/),
        appreciationBp: 300,
        linkedLoanAccountId: 3,
        archived: false,
        valueCents: 40000000,
        valueDate: todayIso(),
      },
    ]);
  });

  it("adds something with no value yet, and no loan", async () => {
    const fetchMock = withAssets({ "POST /api/assets": () => ({ status: 201, data: HOME_DETAIL }) });
    renderApp("/accounts");
    await screen.findByRole("region", { name: "Home & assets" });

    await userEvent.click(screen.getByRole("button", { name: "+ Add home or asset" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByRole("radio", { name: "Something else" }));
    await userEvent.type(within(dialog).getByLabelText("Name"), "Grandmother's ring");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/assets")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/assets")[0]).toMatchObject({
      name: "Grandmother's ring",
      kind: "other",
      appreciationBp: null,
      linkedLoanAccountId: null,
      valueCents: null,
      valueDate: null,
    });
  });

  it("asks again kindly for a rate that is not a rate, and shows the backend's reason when it refuses", async () => {
    const fetchMock = withAssets({
      "GET /api/assets/6": () => ({ status: 200, data: { asset: CAR, values: [] } }),
      "PUT /api/assets/6": () => ({ status: 400, error: "Please add a name for this." }),
    });
    renderApp("/assets/6");

    // The same button, pencil and wording as on an account's and an income source's page.
    await userEvent.click(await screen.findByRole("button", { name: "Edit asset" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Outback" });
    // Its value has its own history, so it is not asked for again here.
    expect(within(dialog).queryByRole("textbox", { name: "Value" })).not.toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText("Assumed growth a year, % (optional)"), "lots");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter a yearly rate like 3.00, or leave it empty.");
    expect(calls(fetchMock)).not.toContain("PUT /api/assets/6");

    await userEvent.clear(within(dialog).getByLabelText("Assumed growth a year, % (optional)"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please add a name for this.");
  });
});

describe("accounts screen: update balances", () => {
  it("saves the accounts that were filled in, in one request, and leaves the rest alone", async () => {
    const fetchMock = withAssets({ "PUT /api/account-balances": () => ({ status: 200, data: { saved: 2 }, changed: true }) });
    renderApp("/accounts");
    await screen.findByRole("region", { name: "Checking & savings" });

    await userEvent.click(screen.getByRole("button", { name: "Update balances" }));
    const dialog = screen.getByRole("dialog", { name: "Update balances" });
    // Grouped as the screen is, each saying what was entered last.
    const cash = within(dialog).getByRole("group", { name: "Checking & savings" });
    expect(cash).toHaveTextContent("last entered $2,845.12 on Oct 2, 2026");
    expect(within(dialog).getByRole("group", { name: "Credit cards" })).toHaveTextContent("no balance entered yet · enter what you owe");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Everyday Checking" }), "3,012.40");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Maple Street mortgage" }), "284,100");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save balances" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/account-balances")).toEqual([
      {
        date: todayIso(),
        balances: [
          { accountId: 1, amountCents: 301240 },
          { accountId: 3, amountCents: 28410000 },
        ],
      },
    ]);
  });

  it("asks for at least one balance, and for an amount that reads as one", async () => {
    const fetchMock = withAssets();
    renderApp("/accounts");
    await screen.findByRole("region", { name: "Checking & savings" });

    await userEvent.click(screen.getByRole("button", { name: "Update balances" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save balances" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter a balance for at least one account.");

    await userEvent.type(within(dialog).getByRole("textbox", { name: "Everyday Checking" }), "about three grand");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save balances" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00 for Everyday Checking, or leave it empty.");
    expect(calls(fetchMock)).not.toContain("PUT /api/account-balances");
  });
});

describe("asset screen", () => {
  it("shows the value as an estimate, what is owed on its loan, and the equity left", async () => {
    withAssets();
    renderApp("/assets/5");

    expect(await screen.findByRole("heading", { level: 1, name: "Maple Street house" })).toBeInTheDocument();
    expect(screen.getByText("$408,966.66")).toHaveTextContent("(estimate)");
    expect(screen.getByText(/at your assumed 3.00% a year · you entered \$400,000.00 on Jan 1, 2026/)).toBeInTheDocument();
    expect(screen.getByText("$285,000.00")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Maple Street mortgage" })).toHaveAttribute("href", "/accounts/3");
    expect(screen.getByText("$123,966.66")).toBeInTheDocument();
    expect(screen.getByText(/an estimate from your own assumed rate, not an appraisal/)).toBeInTheDocument();
  });

  it("lists every value entered with how far it moved", async () => {
    withAssets();
    renderApp("/assets/5");

    const values = await screen.findByRole("region", { name: "Values you entered" });
    const rows = within(values).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("Jan 1, 2026");
    expect(rows[0]).toHaveTextContent("+$20,000.00");
    expect(rows[1]).toHaveTextContent("Jan 1, 2025");
    expect(rows[1]).toHaveTextContent("—");
  });

  it("records a new value for today", async () => {
    const fetchMock = withAssets({ "POST /api/assets/5/values": () => ({ status: 201, data: HOME_DETAIL, changed: true }) });
    renderApp("/assets/5");

    await userEvent.click(await screen.findByRole("button", { name: "Update value" }));
    const dialog = screen.getByRole("dialog", { name: "Update value — Maple Street house" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save value" }));
    expect(calls(fetchMock)).not.toContain("POST /api/assets/5/values");

    await userEvent.type(within(dialog).getByRole("textbox", { name: "Value" }), "415000");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save value" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/assets/5/values")).toEqual([{ date: todayIso(), valueCents: 41500000 }]);
  });

  it("removes a value", async () => {
    const fetchMock = withAssets({ "DELETE /api/assets/5/values/19": () => ({ status: 200, data: HOME_DETAIL, changed: true }) });
    renderApp("/assets/5");

    await userEvent.click(await screen.findByRole("button", { name: "Remove the value from Jan 1, 2025" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/assets/5/values/19"));
  });

  it("invites a first value when none was entered", async () => {
    const bare: Asset = { ...CAR, latestValue: null, valueCents: null, equityCents: null };
    withAssets({ "GET /api/assets/6": () => ({ status: 200, data: { asset: bare, values: [] } }) });
    renderApp("/assets/6");

    expect(await screen.findByText("No value entered yet.")).toBeInTheDocument();
    expect(screen.getByText(/Each value you enter is kept with its date/)).toBeInTheDocument();
  });

  it("says so when the asset cannot be found, with a way back", async () => {
    withAssets();
    renderApp("/assets/99");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that.");
    expect(screen.getByRole("link", { name: "Back to all accounts" })).toHaveAttribute("href", "/accounts");
  });
});
