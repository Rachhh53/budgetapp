import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail, AccountPoint } from "../../src/api/accounts";
import type { Bucket } from "../../src/api/buckets";
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
  latestBalance: { id: 11, date: "2026-10-02", amountCents: 170510, note: "From statement" },
};
const DETAIL: AccountDetail = {
  account: CHECKING,
  balances: [
    { id: 11, date: "2026-10-02", amountCents: 170510, note: "From statement" },
    { id: 10, date: "2026-09-01", amountCents: 180000, note: null },
  ],
  interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
  rates: [],
};
const EMPTY: AccountDetail = { ...DETAIL, account: { ...CHECKING, latestBalance: null }, balances: [] };

const GIFTS: Bucket = {
  id: 7,
  accountId: 1,
  name: "Holiday gifts",
  color: "#b45309",
  goalCents: 60000,
  note: null,
  archived: false,
  openedOn: "2026-09-01",
  balanceCents: 20000,
};

const PROJECTION: AccountPoint[] = [
  { start: "2026-10-01", end: "2026-10-14", endingCents: 309610, lowestCents: 31390, availableCents: 289610 },
  { start: "2026-10-15", end: "2026-10-31", endingCents: 412500, lowestCents: 309610, availableCents: 392500 },
];

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
    const card: AccountDetail = { ...EMPTY, account: { ...CHECKING, type: "credit_card", latestBalance: null } };
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
        lowBalanceThresholdCents: null,
      },
    ]);
  });

  it("sets a low-balance warning for a checking account, and offers none for a card", async () => {
    const fetchMock = fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
      "PUT /api/accounts/1": () => ({ status: 200, data: DETAIL }),
    });
    renderApp("/accounts/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit account" }));
    const dialog = screen.getByRole("dialog", { name: "Edit account" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Warn me when the balance dips below (optional)" }), "500");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/accounts/1")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/accounts/1")[0]).toMatchObject({ lowBalanceThresholdCents: 50000 });

    await userEvent.click(await screen.findByRole("button", { name: "Edit account" }));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Kind of account" }), "credit_card");
    expect(screen.queryByRole("textbox", { name: /Warn me when/ })).not.toBeInTheDocument();
  });

  it("says where the account is heading, in a chart and in words", async () => {
    fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: { ...DETAIL, account: { ...CHECKING, lowBalanceThresholdCents: 50000 } } }),
      "GET /api/accounts/1/projection": () => ({ status: 200, data: PROJECTION }),
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
    });
    renderApp("/accounts/1");

    const heading = await screen.findByRole("region", { name: "Where it's heading" });
    expect(within(heading).getByRole("figure")).toHaveTextContent(
      "the projection, which reaches $4,125.00 by Oct 31, 2026 ($3,925.00 available after buckets, the dotted line)",
    );
    expect(within(heading).getByRole("figure")).toHaveTextContent("Its lowest point on the way is $313.90, in the pay period that starts Oct 1, 2026.");
    expect(screen.getByText("Projected").closest("div")).toHaveTextContent("$3,096.10");
    expect(screen.getByText("Available after buckets").closest("div")).toHaveTextContent("$2,896.10");
    expect(screen.getByText(/flagged when this account is projected to dip below/)).toHaveTextContent("$500.00");
  });

  describe("buckets", () => {
    it("lists each bucket with its goal and what is left available", async () => {
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS, { ...GIFTS, id: 8, name: "Old trip", archived: true, balanceCents: 0 }] }),
      });
      renderApp("/accounts/1");

      const buckets = await screen.findByRole("region", { name: "Buckets" });
      const gifts = (await within(buckets).findByText("Holiday gifts")).closest("li")!;
      expect(gifts).toHaveTextContent("$200.00");
      expect(gifts).toHaveTextContent("33% of $600.00");
      expect(within(buckets).getByText("Set aside in buckets").closest("div")).toHaveTextContent("$200.00");
      // 1,705.10 entered, less 200.00 in buckets.
      expect(within(buckets).getByText("Left available, from the balance you entered").closest("div")).toHaveTextContent("$1,505.10");
      expect(within(buckets).getByText("Old trip · Archived")).toBeInTheDocument();
    });

    it("explains what a bucket is when there are none, and adds one", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "POST /api/buckets": () => ({ status: 201, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "GET /api/pay-periods": () => ({
          status: 200,
          data: { primarySourceId: 1, primaryAccountId: 1, periods: [{ start: "2026-01-01", end: "2099-12-31", current: true }] },
        }),
      });
      renderApp("/accounts/1");

      const buckets = await screen.findByRole("region", { name: "Buckets" });
      expect(await within(buckets).findByText(/A bucket sets part of this account aside/)).toBeInTheDocument();      await userEvent.click(within(buckets).getByRole("button", { name: "Add a bucket" }));
      const dialog = screen.getByRole("dialog", { name: "Add a bucket to Everyday Checking" });
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Holiday gifts");
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Goal (optional)" }), "600");
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Already set aside (optional)" }), "150");
      // It counts from this paycheck's payday unless told otherwise, so the paycheck in hand is included.
      await waitFor(() => expect(within(dialog).getByLabelText("Counts from")).toHaveValue("2026-01-01"));
      await userEvent.click(within(dialog).getByRole("button", { name: "Add bucket" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "POST /api/buckets")).toEqual([
        {
          accountId: 1,
          name: "Holiday gifts",
          color: expect.stringMatching(/^#[0-9a-f]{6}$/),
          goalCents: 60000,
          note: null,
          archived: false,
          openedOn: "2026-01-01",
          startingCents: 15000,
        },
      ]);
    });

    it("takes money out of a bucket by hand, and removes an earlier move", async () => {
      const detail = { bucket: GIFTS, changes: [{ id: 3, bucketId: 7, date: "2026-10-01", amountCents: 15000, note: "Starting amount" }] };
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "GET /api/buckets/7": () => ({ status: 200, data: detail }),
        "POST /api/buckets/7/changes": () => ({ status: 201, data: detail, changed: true }),
        "DELETE /api/buckets/7/changes/3": () => ({ status: 200, data: detail, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Move money in or out of Holiday gifts" }));
      const dialog = screen.getByRole("dialog", { name: "Holiday gifts — move money" });
      expect(await within(dialog).findByText("Starting amount")).toBeInTheDocument();
      await userEvent.click(within(dialog).getByRole("button", { name: "Remove the $150.00 put in on Oct 1, 2026" }));
      await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/buckets/7/changes/3"));

      await userEvent.click(within(dialog).getByRole("radio", { name: "Take money out" }));
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount" }), "80");
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Note (optional)" }), "Gift for Priya");
      await userEvent.click(within(dialog).getByRole("button", { name: "Take it out" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "POST /api/buckets/7/changes")).toEqual([
        { date: todayIso(), amountCents: -8000, note: "Gift for Priya" },
      ]);
    });

    it("changes a bucket, and deletes it without asking", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "DELETE /api/buckets/7": () => ({ status: 200, data: { deleted: true }, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      let dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      // What is already in a bucket is changed by moving money, not here.
      expect(within(dialog).queryByRole("textbox", { name: "Already set aside (optional)" })).not.toBeInTheDocument();
      const goal = within(dialog).getByRole("textbox", { name: "Goal (optional)" });
      await userEvent.clear(goal);
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
      await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")).toHaveLength(1));
      expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")[0]).toMatchObject({ name: "Holiday gifts", goalCents: null, archived: false });

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      await userEvent.click(within(dialog).getByRole("button", { name: "Delete this bucket" }));
      await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/buckets/7"));
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });
  });

  describe("interest", () => {
    const SAVINGS_DETAIL: AccountDetail = {
      ...DETAIL,
      account: { ...CHECKING, name: "Rainy Day Savings", type: "savings" },
      rates: [{ id: 4, kind: "apy", rateBp: 410, effectiveDate: "2026-01-01" }],
    };

    const SETTINGS_ROUTES = {
      "GET /api/accounts/1": () => ({ status: 200, data: SAVINGS_DETAIL }),
      "POST /api/accounts/1/rates": () => ({ status: 201, data: SAVINGS_DETAIL, changed: true }),
      "DELETE /api/accounts/1/rates/4": () => ({ status: 200, data: SAVINGS_DETAIL, changed: true }),
      "PUT /api/accounts/1/interest": () => ({ status: 200, data: SAVINGS_DETAIL, changed: true }),
    };

    /** Opens the one Edit there is for interest and returns its dialog. */
    async function editInterest() {
      const interest = await screen.findByRole("region", { name: "Interest" });
      await userEvent.click(within(interest).getByRole("button", { name: "Edit interest" }));
      return screen.getByRole("dialog", { name: "Edit interest — Rainy Day Savings" });
    }

    it("shows the rate, when it is credited and where it goes, with one Edit for all of it", async () => {
      fakeBackend(SETTINGS_ROUTES);
      renderApp("/accounts/1");

      const interest = await screen.findByRole("region", { name: "Interest" });
      expect(within(interest).getByText("4.10% APY")).toBeInTheDocument();
      expect(within(interest).getByText("since Jan 1, 2026")).toBeInTheDocument();
      expect(within(interest).getByText("The last day of the month")).toBeInTheDocument();
      expect(within(interest).getByText("Not into a bucket")).toBeInTheDocument();
      // No second form on the page: the fields only exist once Edit is pressed.
      expect(within(interest).queryByRole("textbox")).not.toBeInTheDocument();
      expect(within(interest).queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    });

    it("a new rate from a new date is added, leaving the old one in the history", async () => {
      const fetchMock = fakeBackend(SETTINGS_ROUTES);
      renderApp("/accounts/1");
      const dialog = await editInterest();
      const rate = within(dialog).getByRole("textbox", { name: "APY (%)" });
      expect(rate).toHaveValue("4.10");
      expect(within(dialog).getByLabelText("Rate applies from")).toHaveValue("2026-01-01");

      await userEvent.clear(rate);
      await userEvent.type(rate, "about four");
      await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
      expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter a rate like 4.10.");
      expect(calls(fetchMock)).not.toContain("POST /api/accounts/1/rates");

      await userEvent.clear(rate);
      await userEvent.type(rate, "3.85");
      await userEvent.clear(within(dialog).getByLabelText("Rate applies from"));
      await userEvent.type(within(dialog).getByLabelText("Rate applies from"), "2026-10-01");
      await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "POST /api/accounts/1/rates")).toEqual([{ rateBp: 385, effectiveDate: "2026-10-01" }]);
      expect(calls(fetchMock)).not.toContain("DELETE /api/accounts/1/rates/4");
    });

    it("the same rate on another date corrects when it began, replacing the entry", async () => {
      const fetchMock = fakeBackend(SETTINGS_ROUTES);
      renderApp("/accounts/1");
      const dialog = await editInterest();

      await userEvent.clear(within(dialog).getByLabelText("Rate applies from"));
      await userEvent.type(within(dialog).getByLabelText("Rate applies from"), "2025-11-15");
      await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "POST /api/accounts/1/rates")).toEqual([{ rateBp: 410, effectiveDate: "2025-11-15" }]);
      expect(calls(fetchMock)).toContain("DELETE /api/accounts/1/rates/4");
    });

    it("lists earlier rates with the day each one ended", async () => {
      fakeBackend({
        "GET /api/accounts/1": () => ({
          status: 200,
          data: {
            ...SAVINGS_DETAIL,
            rates: [
              { id: 5, kind: "apy", rateBp: 385, effectiveDate: "2026-10-01" },
              { id: 4, kind: "apy", rateBp: 410, effectiveDate: "2026-01-01" },
            ],
          },
        }),
      });
      renderApp("/accounts/1");

      const history = await screen.findByRole("region", { name: "Rate history" });
      expect(within(history).getAllByRole("listitem").map((row) => row.textContent)).toEqual([
        "3.85% from Oct 1, 2026Remove",
        "4.10% Jan 1, 2026 to Sep 30, 2026Remove",
      ]);
    });

    it("removes a rate", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: SAVINGS_DETAIL }),
        "DELETE /api/accounts/1/rates/4": () => ({ status: 200, data: SAVINGS_DETAIL, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Remove the rate from Jan 1, 2026" }));

      await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/accounts/1/rates/4"));
    });

    it("chooses the day interest is credited and the bucket it goes into", async () => {
      const fetchMock = fakeBackend({ ...SETTINGS_ROUTES, "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }) });
      renderApp("/accounts/1");

      const interest = await editInterest();
      expect(within(interest).getByRole("combobox", { name: "Credited on" })).toHaveValue("31");
      await userEvent.selectOptions(within(interest).getByRole("combobox", { name: "Credited on" }), "15th");
      await waitFor(() => expect(within(interest).getByRole("option", { name: "All into one bucket" })).toBeInTheDocument());
      await userEvent.selectOptions(within(interest).getByRole("combobox", { name: "Interest goes" }), "All into one bucket");
      await userEvent.click(within(interest).getByRole("button", { name: "Save" }));
      expect(within(interest).getByRole("alert")).toHaveTextContent("Please choose the bucket the interest goes into.");

      await userEvent.type(within(interest).getByRole("combobox", { name: "Bucket" }), "Holiday{Enter}");
      await userEvent.click(within(interest).getByRole("button", { name: "Save" }));

      await waitFor(() =>
        expect(bodiesSentTo(fetchMock, "PUT /api/accounts/1/interest")).toEqual([
          { creditDay: 15, destination: "bucket", destinationBucketId: 7 },
        ]),
      );
      // The rate was not touched, so it is not saved again.
      expect(calls(fetchMock)).not.toContain("POST /api/accounts/1/rates");
    });

    it("has no buckets or interest for a credit card", async () => {
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: { ...EMPTY, account: { ...CHECKING, type: "credit_card" } } }),
      });
      renderApp("/accounts/1");

      expect(await screen.findByRole("region", { name: "Balances you entered" })).toBeInTheDocument();
      expect(screen.queryByRole("region", { name: "Buckets" })).not.toBeInTheDocument();
      expect(screen.queryByRole("region", { name: "Interest" })).not.toBeInTheDocument();
    });
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
