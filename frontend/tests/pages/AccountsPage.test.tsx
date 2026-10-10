import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail } from "../../src/api/accounts";
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
  latestBalance: { id: 10, date: "2026-10-02", amountCents: 170510, note: null },
};
const SAVINGS: Account = {
  id: 2,
  name: "Rainy Day",
  institution: "Sunrise Bank",
  type: "savings",
  subtypeLabel: "High-yield",
  color: "#c2410c",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: { id: 11, date: "2026-09-30", amountCents: 842000, note: null },
};
const CARD: Account = {
  id: 3,
  name: "Travel Card",
  institution: null,
  type: "credit_card",
  subtypeLabel: null,
  color: "#4338ca",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: null,
};
const OLD: Account = { ...SAVINGS, id: 4, name: "Old Savings", archived: true, latestBalance: null };

describe("accounts screen", () => {
  it("welcomes someone with no accounts yet", async () => {
    fakeBackend();
    renderApp("/accounts");

    expect(await screen.findByText("No accounts yet.")).toBeInTheDocument();
    expect(screen.getByText(/Nothing connects to your bank/)).toBeInTheDocument();
  });

  it("groups accounts by kind with the balance last entered", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING, SAVINGS, CARD] }) });
    renderApp("/accounts");

    const cash = await screen.findByRole("region", { name: "Checking & savings" });
    // The group total is the two balances added in cents.
    expect(within(cash).getByText("$10,125.10")).toBeInTheDocument();
    const checking = within(cash).getByRole("link", { name: /Everyday Checking/ });
    expect(checking).toHaveTextContent("Checking · Coastal Credit Union");
    expect(checking).toHaveTextContent("$1,705.10");
    expect(checking).toHaveTextContent("entered Oct 2");
    expect(within(cash).getByRole("link", { name: /Rainy Day/ })).toHaveTextContent("High-yield · Sunrise Bank");

    const cards = screen.getByRole("region", { name: "Credit cards" });
    expect(within(cards).getByRole("link", { name: /Travel Card/ })).toHaveTextContent("No balance yet");
    expect(screen.queryByRole("region", { name: "Loans" })).not.toBeInTheDocument();
  });

  it("shows what each account holds today once that is known", async () => {
    fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING, SAVINGS, CARD] }),
      "GET /api/account-balances": () => ({
        status: 200,
        data: [
          { accountId: CHECKING.id, date: "2026-10-09", balanceCents: 309610, availableCents: 289610 },
          { accountId: SAVINGS.id, date: "2026-10-09", balanceCents: 887000, availableCents: 887000 },
          { accountId: CARD.id, date: "2026-10-09", balanceCents: 0, availableCents: 0 },
        ],
      }),
    });
    renderApp("/accounts");

    const cash = await screen.findByRole("region", { name: "Checking & savings" });
    const checking = within(cash).getByRole("link", { name: /Everyday Checking/ });
    // Not the $1,705.10 typed in a week ago: that plus everything since.
    await waitFor(() => expect(checking).toHaveTextContent("$3,096.10"));
    expect(checking).toHaveTextContent("today");
    expect(within(cash).getByText("$11,966.10")).toBeInTheDocument();
    // No balance was entered for the card; it is added up from nothing like any other account.
    expect(within(screen.getByRole("region", { name: "Credit cards" })).getByRole("link", { name: /Travel Card/ })).toHaveTextContent("$0.00");
  });

  it("tucks archived accounts away from the groups", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING, OLD] }) });
    renderApp("/accounts");

    const cash = await screen.findByRole("region", { name: "Checking & savings" });
    expect(within(cash).queryByText("Old Savings")).not.toBeInTheDocument();
    expect(screen.getByText("Archived accounts (1)")).toBeInTheDocument();
  });

  it("shows the backend's message when accounts cannot be loaded", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 500, error: "Something went wrong on our side." }) });
    renderApp("/accounts");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side.");
  });

  it("adds an account, picking the bank and color rather than typing them", async () => {
    let accounts: Account[] = [CHECKING];
    const fetchMock = fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: accounts }),
      "POST /api/accounts": (body) => {
        const created = { ...(body as Omit<Account, "id" | "latestBalance">), id: 9, latestBalance: null };
        accounts = [...accounts, created];
        return { status: 201, data: { account: created, balances: [], interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null }, rates: [] } satisfies AccountDetail };
      },
    });
    renderApp("/accounts");
    await screen.findByRole("link", { name: /Everyday Checking/ });

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    const dialog = screen.getByRole("dialog", { name: "Add an account" });
    expect(within(dialog).getByLabelText("Name")).toHaveFocus();
    await userEvent.type(within(dialog).getByLabelText("Name"), "Retirement");
    await userEvent.selectOptions(within(dialog).getByLabelText("Kind of account"), "Investment");
    // Labels suggested for an investment account, and a bank that already exists.
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Label (optional)" }), "401{Enter}");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Bank or institution (optional)" }), "coast{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: /Color/ }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Plum" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add account" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/accounts")).toEqual([
      {
        name: "Retirement",
        type: "investment",
        subtypeLabel: "401(k)",
        institution: "Coastal Credit Union",
        color: "#7e22ce",
        archived: false,
        lowBalanceThresholdCents: null,
      },
    ]);
    const investments = await screen.findByRole("region", { name: "Investments" });
    expect(within(investments).getByRole("link", { name: /Retirement/ })).toHaveTextContent("401(k) · Coastal Credit Union");
  });

  it("takes a savings account's interest rate while adding it, and saves it from today", async () => {
    const detail = (account: Account): AccountDetail => ({
      account,
      balances: [],
      interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
      rates: [],
    });
    const fetchMock = fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }),
      "POST /api/accounts": (body) => ({
        status: 201,
        data: detail({ ...(body as Omit<Account, "id" | "latestBalance">), id: 9, latestBalance: null }),
      }),
      "POST /api/accounts/9/rates": () => ({ status: 201, data: detail({ ...CHECKING, id: 9 }) }),
    });
    renderApp("/accounts");
    await screen.findByRole("link", { name: /Everyday Checking/ });

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    const dialog = screen.getByRole("dialog", { name: "Add an account" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Rainy Day Savings");
    await userEvent.selectOptions(within(dialog).getByLabelText("Kind of account"), "Savings");
    await userEvent.type(within(dialog).getByLabelText("Interest rate, APY % (optional)"), "4.1");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add account" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/accounts/9/rates")).toEqual([{ rateBp: 410, effectiveDate: todayIso() }]);
  });

  it("takes what is owed on a loan while adding it, in the same request", async () => {
    const fetchMock = fakeBackend({
      "POST /api/accounts": (body) => ({
        status: 201,
        data: {
          account: { ...(body as Omit<Account, "id" | "latestBalance">), id: 9, latestBalance: null },
          balances: [],
          interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
          rates: [],
        } satisfies AccountDetail,
      }),
    });
    renderApp("/accounts");
    await screen.findByText("No accounts yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    const dialog = screen.getByRole("dialog", { name: "Add an account" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Maple Street mortgage");
    expect(within(dialog).getByLabelText("Balance (optional)")).toBeInTheDocument();
    await userEvent.selectOptions(within(dialog).getByLabelText("Kind of account"), "Loan");
    await userEvent.type(within(dialog).getByLabelText("Amount owed (optional)"), "285000");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add account" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/accounts")).toEqual([
      expect.objectContaining({ type: "loan", balanceCents: 28_500_000, balanceDate: todayIso() }),
    ]);
  });

  it("asks kindly when the first balance is not an amount, and adds nothing", async () => {
    const fetchMock = fakeBackend();
    renderApp("/accounts");
    await screen.findByText("No accounts yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    const dialog = screen.getByRole("dialog", { name: "Add an account" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Everyday Checking");
    await userEvent.type(within(dialog).getByLabelText("Balance (optional)"), "lots");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add account" }));

    expect(await within(dialog).findByText("Please enter an amount like 1,250.00.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/accounts")).toEqual([]);
  });

  it("offers no interest rate for an account that earns none this way", async () => {
    fakeBackend();
    renderApp("/accounts");
    await screen.findByText("No accounts yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    const dialog = screen.getByRole("dialog", { name: "Add an account" });
    await userEvent.selectOptions(within(dialog).getByLabelText("Kind of account"), "Credit card");

    expect(within(dialog).queryByLabelText("Interest rate, APY % (optional)")).not.toBeInTheDocument();
  });

  it("says Open on every account, so the way into its page is not a guess", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }) });
    renderApp("/accounts");

    const row = await screen.findByRole("link", { name: /Everyday Checking/ });
    expect(row).toHaveTextContent(/Open$/);
    expect(row).toHaveAttribute("href", `/accounts/${CHECKING.id}`);
  });

  it("has an Edit button on every account, which opens it for changing without leaving the list", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }) });
    renderApp("/accounts");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Everyday Checking" }));

    const dialog = screen.getByRole("dialog", { name: "Edit account" });
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Everyday Checking");
  });

  it("starts a new account on a color no other account uses", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }) });
    renderApp("/accounts");
    await screen.findByRole("link", { name: /Everyday Checking/ });

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));

    // Teal is taken by Everyday Checking, so the next preset is offered.
    expect(screen.getByRole("button", { name: /Color/ })).toHaveAccessibleName("Color Raspberry");
  });

  it("keeps the form open and shows why when the account cannot be saved", async () => {
    fakeBackend({ "POST /api/accounts": () => ({ status: 400, error: "Please add a name for the account." }) });
    renderApp("/accounts");
    await screen.findByText("No accounts yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), " ");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add account" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please add a name for the account.");
  });

  it("closes the form with Escape without saving", async () => {
    const fetchMock = fakeBackend();
    renderApp("/accounts");
    await screen.findByText("No accounts yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add account" }));
    await userEvent.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Add account" })).toHaveFocus();
    expect(calls(fetchMock)).not.toContain("POST /api/accounts");
  });
});
