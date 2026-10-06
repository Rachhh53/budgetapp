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
  latestBalance: { id: 11, date: "2026-10-02", amountCents: 170510, note: "From statement" },
};
const DETAIL: AccountDetail = {
  account: CHECKING,
  balances: [
    { id: 11, date: "2026-10-02", amountCents: 170510, note: "From statement" },
    { id: 10, date: "2026-09-01", amountCents: 180000, note: null },
  ],
};
const EMPTY: AccountDetail = { account: { ...CHECKING, latestBalance: null }, balances: [] };

describe("account detail screen", () => {
  it("shows the balance and every balance entered, with the change between them", async () => {
    fakeBackend({ "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }) });
    renderApp("/accounts/1");

    expect(await screen.findByRole("heading", { level: 1, name: "Everyday Checking" })).toBeInTheDocument();
    expect(screen.getByText("entered by you Oct 2, 2026")).toBeInTheDocument();
    const rows = within(screen.getByRole("region", { name: "Balances you entered" })).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Oct 2, 2026");
    expect(rows[0]).toHaveTextContent("$1,705.10");
    // Down $94.90 since the September entry; the oldest entry has nothing to compare with.
    expect(rows[0]).toHaveTextContent("-$94.90");
    expect(rows[0]).toHaveTextContent("From statement");
    expect(rows[1]).toHaveTextContent("—");
  });

  it("invites a first balance when none has been entered", async () => {
    fakeBackend({ "GET /api/accounts/1": () => ({ status: 200, data: EMPTY }) });
    renderApp("/accounts/1");

    expect(await screen.findByText("No balance entered yet.")).toBeInTheDocument();
    expect(screen.getByText(/projections start from the latest number you enter/)).toBeInTheDocument();
  });

  it("says so kindly when the account does not exist", async () => {
    fakeBackend();
    renderApp("/accounts/999");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that.");
    expect(screen.getByRole("link", { name: "Back to all accounts" })).toHaveAttribute("href", "/accounts");
  });

  it("records a balance in whole cents, dated today by default", async () => {
    let detail = EMPTY;
    const fetchMock = fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: detail }),
      "POST /api/accounts/1/balances": () => {
        detail = DETAIL;
        return { status: 201, data: detail };
      },
    });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Update balance" }));
    const dialog = screen.getByRole("dialog", { name: "Update balance — Everyday Checking" });
    expect(within(dialog).getByLabelText("As of")).toHaveValue(todayIso());
    await userEvent.type(within(dialog).getByLabelText("Balance"), "1,705.10");
    await userEvent.type(within(dialog).getByLabelText("Note (optional)"), "From statement");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save balance" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/accounts/1/balances")).toEqual([
      { date: todayIso(), amountCents: 170510, note: "From statement" },
    ]);
    expect(await screen.findByText("entered by you Oct 2, 2026")).toBeInTheDocument();
  });

  it("asks again when what was typed is not an amount, and sends nothing", async () => {
    const fetchMock = fakeBackend({ "GET /api/accounts/1": () => ({ status: 200, data: EMPTY }) });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Update balance" }));
    await userEvent.type(screen.getByLabelText("Balance"), "about a grand");
    await userEvent.click(screen.getByRole("button", { name: "Save balance" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00.");
    expect(calls(fetchMock)).not.toContain("POST /api/accounts/1/balances");
  });

  it("asks what is owed for a credit card", async () => {
    const card: AccountDetail = { account: { ...CHECKING, type: "credit_card", latestBalance: null }, balances: [] };
    fakeBackend({ "GET /api/accounts/1": () => ({ status: 200, data: card }) });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Update balance" }));

    expect(screen.getByLabelText("Amount owed")).toBeInTheDocument();
  });

  it("removes one entry", async () => {
    const fetchMock = fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
      "DELETE /api/accounts/1/balances/10": () => ({ status: 200, data: DETAIL }),
    });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Remove the balance from Sep 1, 2026" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/accounts/1/balances/10"));
  });

  it("changes the account's details", async () => {
    const fetchMock = fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }),
      "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
      "PUT /api/accounts/1": () => ({ status: 200, data: DETAIL }),
    });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit account" }));
    const dialog = screen.getByRole("dialog", { name: "Edit account" });
    const name = within(dialog).getByLabelText("Name");
    await userEvent.clear(name);
    await userEvent.type(name, "Household Checking");
    await userEvent.click(within(dialog).getByLabelText("Archived"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/accounts/1")).toEqual([
      {
        name: "Household Checking",
        type: "checking",
        subtypeLabel: null,
        institution: "Coastal Credit Union",
        color: "#0f766e",
        archived: true,
      },
    ]);
  });

  it("deletes the account only after asking, with keeping it as the default", async () => {
    const fetchMock = fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
      "DELETE /api/accounts/1": () => ({ status: 200, data: { deleted: true } }),
    });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit account" }));
    await userEvent.click(screen.getByRole("button", { name: "Delete account…" }));

    const confirm = screen.getByRole("alertdialog");
    expect(confirm).toHaveTextContent("Delete Everyday Checking?");
    expect(within(confirm).getByRole("button", { name: "Keep it" })).toHaveFocus();
    expect(calls(fetchMock)).not.toContain("DELETE /api/accounts/1");

    await userEvent.click(within(confirm).getByRole("button", { name: "Yes, delete it" }));

    // Back on the list, with the account gone.
    expect(await screen.findByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("DELETE /api/accounts/1");
  });

  it("explains why an account in use cannot be deleted", async () => {
    fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
      "DELETE /api/accounts/1": () => ({ status: 409, error: "This account is still used by an income source." }),
    });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit account" }));
    await userEvent.click(screen.getByRole("button", { name: "Delete account…" }));
    await userEvent.click(screen.getByRole("button", { name: "Yes, delete it" }));

    expect(await within(screen.getByRole("alertdialog")).findByRole("alert"))
      .toHaveTextContent("still used by an income source");
  });
});
