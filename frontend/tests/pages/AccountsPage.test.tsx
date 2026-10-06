import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail } from "../../src/api/accounts";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: "Coastal Credit Union",
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false,
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
        return { status: 201, data: { account: created, balances: [] } satisfies AccountDetail };
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
      },
    ]);
    const investments = await screen.findByRole("region", { name: "Investments" });
    expect(within(investments).getByRole("link", { name: /Retirement/ })).toHaveTextContent("401(k) · Coastal Credit Union");
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
