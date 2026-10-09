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
  goalDate: null,
  stopAtGoal: false,
  goalProgress: null,
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

  it("lists what moved the account, saying what is upcoming or not paid yet", async () => {
    fakeBackend({
      "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
      "GET /api/accounts/1/transactions": () => ({
        status: 200,
        data: [
          { date: "2999-01-05", label: "Rent", kind: "bill", amountCents: -120000, unpaid: true },
          { date: "2026-10-02", label: "Balance you entered", kind: "balance", amountCents: 170510, unpaid: false },
          { date: "2026-10-01", label: "Acme Corp", kind: "paycheck", amountCents: 248620, unpaid: false },
        ],
      }),
    });
    renderApp("/accounts/1");

    const rows = within(await screen.findByRole("region", { name: "Transactions" }));
    const rent = (await rows.findByText("Rent")).closest("li")!;
    expect(rent).toHaveTextContent("-$1,200.00");
    expect(rent).toHaveTextContent("Bill · Upcoming · Not paid yet");
    expect(rows.getByText("Acme Corp").closest("li")).toHaveTextContent("+$2,486.20");
    expect(rows.getByText("Acme Corp").closest("li")).toHaveTextContent("Oct 1, 2026 · Paycheck");
    // A balance entered is shown as where the account stood, with no plus or minus.
    expect(rows.getByText("Balance you entered").closest("li")).toHaveTextContent(/^Balance you enteredOct 2, 2026 · Balance\$1,705\.10$/);
  });

  it("says so kindly when nothing moved between the dates, and when the dates are backwards", async () => {
    fakeBackend({ "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }) });
    renderApp("/accounts/1");

    const card = await screen.findByRole("region", { name: "Transactions" });
    expect(await within(card).findByText(/Nothing moved in or out of Everyday Checking between these dates/)).toBeInTheDocument();
    await userEvent.clear(within(card).getByLabelText("To"));
    await userEvent.type(within(card).getByLabelText("To"), "2000-01-01");
    expect(within(card).getByText(/Choose a From date that is on or before the To date/)).toBeInTheDocument();
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
    it("lists each bucket with its goal, and shows that buckets and the rest add up to the balance", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS, { ...GIFTS, id: 8, name: "Old trip", archived: true, balanceCents: 0 }] }),
        "POST /api/buckets/7/changes": () => ({ status: 201, data: { bucket: GIFTS, changes: [] }, changed: true }),
      });
      renderApp("/accounts/1");

      const buckets = await screen.findByRole("region", { name: "Buckets" });
      const gifts = (await within(buckets).findByRole("button", { name: "Edit Holiday gifts" })).closest("li")!;
      expect(gifts).toHaveTextContent("$200.00");
      expect(gifts).toHaveTextContent("33% of $600.00");
      // One way in to a bucket: there is no separate button for moving money.
      expect(within(buckets).queryByRole("button", { name: /move money/i })).not.toBeInTheDocument();
      // 200.00 in buckets + 1,505.10 not in one = the 1,705.10 entered.
      expect(within(buckets).getByText("In buckets").closest("div")).toHaveTextContent("$200.00");
      expect(within(buckets).getByText("Not in a bucket").closest("div")).toHaveTextContent("$1,505.10");
      expect(within(buckets).getByText("Balance you entered").closest("div")).toHaveTextContent("$1,705.10");
      // Archived buckets are tucked away until opened.
      expect(within(buckets).getByText("Archived buckets (1)")).toBeInTheDocument();
      expect(within(buckets).getByText("Old trip · Archived")).not.toBeVisible();

      // The rest can be given a job in one step.
      // Closed until asked for.
      expect(within(buckets).getByLabelText("Which bucket")).not.toBeVisible();
      await userEvent.click(within(buckets).getByText("Put the rest in a bucket"));
      expect(within(buckets).getByLabelText("Which bucket")).toBeVisible();
      expect(within(buckets).getByLabelText("Which bucket")).toHaveValue("7");
      await userEvent.click(within(buckets).getByRole("button", { name: "Put $1,505.10 in" }));
      await waitFor(() =>
        expect(bodiesSentTo(fetchMock, "POST /api/buckets/7/changes")).toEqual([
          { date: todayIso(), amountCents: 150510, note: "The rest of the balance" },
        ]),
      );
    });

    it("shows on a bucket's row what reaching its goal by its date would take", async () => {
      const short = {
        ...GIFTS,
        goalDate: "2099-12-01",
        goalProgress: { projectedCents: 40000, shortCents: 20000, paydaysLeft: 4, morePerPaycheckCents: 5000, morePerMonthCents: 10000 },
      };
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [short] }),
      });
      renderApp("/accounts/1");

      const gifts = (await screen.findByRole("button", { name: "Edit Holiday gifts" })).closest("li")!;
      expect(gifts).toHaveTextContent("33% of $600.00 by Dec 1, 2099");
      expect(gifts).toHaveTextContent("Short by $200.00.");
      expect(gifts).toHaveTextContent("it will hold $400.00 by Dec 1, 2099");
      expect(gifts).toHaveTextContent("set aside $50.00 more every paycheck, or $100.00 more a month");
      // The row says how it is going; Edit is the one place to act on it.
      expect(within(gifts).getAllByRole("button")).toHaveLength(1);
    });

    it("says in the bucket's form what its goal takes, and sets that aside every paycheck", async () => {
      const short = {
        ...GIFTS,
        goalDate: "2099-12-01",
        goalProgress: { projectedCents: 40000, shortCents: 20000, paydaysLeft: 4, morePerPaycheckCents: 5000, morePerMonthCents: 10000 },
      };
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [short] }),
        "GET /api/items": () => ({ status: 200, data: [] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: short, changes: [] }, changed: true }),
        "POST /api/items": () => ({ status: 201, data: {}, changed: true }),
        "GET /api/goal-pace": () => ({ status: 200, data: short.goalProgress }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      const dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      // Three questions, in order.
      for (const question of ["What are you saving for?", "How much, by when?", "How much each time?"]) {
        expect(within(dialog).getByRole("group", { name: question })).toBeInTheDocument();
      }
      const amount = await within(dialog).findByRole("textbox", { name: "Set aside (optional)" });
      // The form says what the goal takes, and one press fills it in; it can still be typed over.
      const advice = await within(dialog).findByRole("status");
      expect(advice).toHaveTextContent("To have $600.00 by Dec 1, 2099, set aside $50.00 every paycheck (4 paychecks left), or $100.00 a month.");
      await userEvent.click(within(advice).getByRole("button", { name: "Use $100.00 a month" }));
      expect(amount).toHaveValue("100.00");
      expect(within(dialog).getByRole("combobox", { name: "How often" })).toHaveValue("monthly");
      await userEvent.click(within(advice).getByRole("button", { name: "Use $50.00 every paycheck" }));
      expect(amount).toHaveValue("50.00");
      expect(within(dialog).getByRole("combobox", { name: "How often" })).toHaveValue("every_pay_period");
      await userEvent.clear(amount);
      await userEvent.type(amount, "75");
      await userEvent.click(within(dialog).getByRole("checkbox", { name: "Stop when the goal is reached" }));
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")[0]).toMatchObject({ goalCents: 60000, goalDate: "2099-12-01", stopAtGoal: true });
      // The bucket stops itself at its goal, so the move needs no end date of its own.
      expect(bodiesSentTo(fetchMock, "POST /api/items")[0]).toMatchObject({
        name: "Holiday gifts",
        kind: "bucket_move",
        amountCents: 7500,
        bucketId: 7,
        payFromAccountId: 1,
        schedule: { frequency: "every_pay_period", endDate: null },
      });
    });

    it("changes and stops a bucket's own recurring amount from its form", async () => {
      const fund = {
        id: 5, name: "Gift fund", kind: "bucket_move", archived: false, note: null, tagIds: [], bucketId: 7, saveUpBucketId: null,
        amountCents: 5000, amountMode: "per_occurrence", estimate: false, categoryId: null, payFromAccountId: 1, payToAccountId: null,
        autopay: true, placement: "due_period", daysAfterPayday: 0, perOccurrenceCents: 5000,
        schedule: { frequency: "every_pay_period", interval: null, day1: null, day2: null, month: null, startDate: "2026-10-01", endDate: null, weekendRule: "none" },
      };
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "GET /api/items": () => ({ status: 200, data: [fund] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "PUT /api/items/5": () => ({ status: 200, data: fund, changed: true }),
        "POST /api/items": () => ({ status: 201, data: {}, changed: true }),
      });
      renderApp("/accounts/1");

      // It opens showing what already goes in; a new amount changes that same move from today.
      await screen.findByRole("list", { name: "Recurring into Holiday gifts" });
      await userEvent.click(screen.getByRole("button", { name: "Edit Holiday gifts" }));
      const dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      const amount = within(dialog).getByRole("textbox", { name: "Set aside (optional)" });
      expect(amount).toHaveValue("50.00");
      await userEvent.clear(amount);
      await userEvent.type(amount, "60");
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "PUT /api/items/5")[0]).toMatchObject({
        name: "Gift fund",
        amountCents: 6000,
        schedule: { frequency: "every_pay_period", endDate: null },
      });
      expect(bodiesSentTo(fetchMock, "PUT /api/items/5")[0]).toHaveProperty("effectiveFrom");

      // Emptied, it stops: the move ends today and no second one is added.
      await userEvent.click(screen.getByRole("button", { name: "Edit Holiday gifts" }));
      const again = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      await userEvent.clear(within(again).getByRole("textbox", { name: "Set aside (optional)" }));
      await userEvent.click(within(again).getByRole("button", { name: "Save changes" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      const stopped = bodiesSentTo(fetchMock, "PUT /api/items/5")[1] as { amountCents: number; schedule: { endDate: string | null } };
      expect(stopped.amountCents).toBe(5000);
      expect(stopped.schedule.endDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(bodiesSentTo(fetchMock, "POST /api/items")).toEqual([]);
    });

    it("adds nothing recurring when that amount is left empty", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "POST /api/items": () => ({ status: 201, data: {}, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save changes" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "POST /api/items")).toEqual([]);
    });

    it("lists the recurring items that already put money in a bucket", async () => {
      const fund = {
        id: 5, name: "Gift fund", kind: "bucket_move", archived: false, bucketId: 7, saveUpBucketId: null, perOccurrenceCents: 5000,
        schedule: { frequency: "every_pay_period", interval: null, day1: null, day2: null, month: null, startDate: "2026-10-01", endDate: null, weekendRule: "none" },
      };
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "GET /api/items": () => ({ status: 200, data: [fund] }),
      });
      renderApp("/accounts/1");

      const feeding = await screen.findByRole("list", { name: "Recurring into Holiday gifts" });
      expect(feeding).toHaveTextContent("Gift fund · $50.00 every paycheck");
    });

    it("says so when a bucket has reached its goal, and not before", async () => {
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({
          status: 200,
          data: [GIFTS, { ...GIFTS, id: 8, name: "New bike", balanceCents: 60000 }],
        }),
      });
      renderApp("/accounts/1");

      const bike = (await screen.findByRole("button", { name: "Edit New bike" })).closest("li")!;
      expect(bike).toHaveTextContent("Goal reached");
      expect(bike).toHaveTextContent("100% of $600.00");
      const gifts = screen.getByRole("button", { name: "Edit Holiday gifts" }).closest("li")!;
      expect(gifts).not.toHaveTextContent("Goal reached");
    });

    it("says a bucket is on track when what is scheduled reaches its goal in time", async () => {
      const onTrack = {
        ...GIFTS,
        goalDate: "2099-12-01",
        goalProgress: { projectedCents: 65000, shortCents: 0, paydaysLeft: 4, morePerPaycheckCents: null, morePerMonthCents: null },
      };
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [onTrack] }),
      });
      renderApp("/accounts/1");

      const gifts = (await screen.findByRole("button", { name: "Edit Holiday gifts" })).closest("li")!;
      expect(gifts).toHaveTextContent("On track.");
      expect(gifts).toHaveTextContent("it will hold $650.00 by Dec 1, 2099");
      expect(within(gifts).queryByRole("button", { name: /Start saving/ })).not.toBeInTheDocument();
    });

    it("saves a goal date with the goal, and drops it when the goal is removed", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: GIFTS, changes: [] }, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      let dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      await userEvent.type(within(dialog).getByLabelText("Goal date (optional)"), "2026-12-01");
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
      await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")).toHaveLength(1));
      expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")[0]).toMatchObject({ goalCents: 60000, goalDate: "2026-12-01" });

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      await userEvent.type(within(dialog).getByLabelText("Goal date (optional)"), "2026-12-01");
      await userEvent.clear(within(dialog).getByRole("textbox", { name: "Goal (optional)" }));
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
      await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")).toHaveLength(2));
      expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")[1]).toMatchObject({ goalCents: null, goalDate: null });
    });

    it("says so when the buckets hold more than the account does", async () => {
      fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [{ ...GIFTS, balanceCents: 99999999 }] }),
      });
      renderApp("/accounts/1");

      const buckets = await screen.findByRole("region", { name: "Buckets" });
      expect(await within(buckets).findByRole("status")).toHaveTextContent("These buckets hold more than the account does.");
    });

    it("explains what a bucket is when there are none, and adds one", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "POST /api/buckets": () => ({ status: 201, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "POST /api/items": () => ({ status: 201, data: {}, changed: true }),
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
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Set aside (optional)" }), "50");
      // It starts today unless backdated; an earlier day builds up what it would already hold.
      const starting = within(dialog).getByLabelText("Starting");
      expect(starting).toHaveValue(new Date().toLocaleDateString("en-CA"));
      await userEvent.clear(starting);
      await userEvent.type(starting, "2026-01-01");
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
          goalDate: null,
          // A goal stops its own saving once it is reached, unless that is unticked.
          stopAtGoal: true,
          startingCents: 15000,
        },
      ]);
      // The new bucket gets its recurring move in the same step.
      expect(bodiesSentTo(fetchMock, "POST /api/items")[0]).toMatchObject({
        name: "Holiday gifts",
        kind: "bucket_move",
        amountCents: 5000,
        bucketId: 7,
        payFromAccountId: 1,
        schedule: { frequency: "every_pay_period", startDate: "2026-01-01" },
      });
    });

    it("backdates the amount a new bucket starts with, so its balance builds from then", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "POST /api/buckets": () => ({ status: 201, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "POST /api/buckets/7/changes": () => ({ status: 201, data: { bucket: GIFTS, changes: [] }, changed: true }),
      });
      renderApp("/accounts/1");

      const buckets = await screen.findByRole("region", { name: "Buckets" });
      await userEvent.click(await within(buckets).findByRole("button", { name: "Add a bucket" }));
      const dialog = screen.getByRole("dialog", { name: "Add a bucket to Everyday Checking" });
      await userEvent.type(await within(dialog).findByRole("textbox", { name: "Name" }), "Holiday gifts");
      await userEvent.type(within(dialog).getByRole("textbox", { name: "Already set aside (optional)" }), "150");
      const asOf = within(dialog).getByLabelText("As of");
      expect(asOf).toHaveValue(new Date().toLocaleDateString("en-CA"));
      await userEvent.clear(asOf);
      await userEvent.type(asOf, "2026-01-15");
      await userEvent.click(within(dialog).getByRole("button", { name: "Add bucket" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      // The bucket is added empty, and the amount goes in on the day chosen.
      expect(bodiesSentTo(fetchMock, "POST /api/buckets")[0]).toMatchObject({ name: "Holiday gifts", startingCents: 0 });
      expect(bodiesSentTo(fetchMock, "POST /api/buckets/7/changes")).toEqual([
        { date: "2026-01-15", amountCents: 15000, note: "Starting amount" },
      ]);
    });

    it("archives a bucket from the button beside Delete", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: { ...GIFTS, archived: true }, changes: [] }, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      const dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      // Archive is an action next to Delete, not a tick among the fields.
      expect(within(dialog).queryByRole("checkbox", { name: "Archived" })).not.toBeInTheDocument();
      const archive = await within(dialog).findByRole("button", { name: "Archive this bucket" });
      expect(archive.parentElement).toContainElement(within(dialog).getByRole("button", { name: "Delete this bucket" }));
      await userEvent.click(archive);

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")[0]).toMatchObject({ name: "Holiday gifts", goalCents: 60000, archived: true });
    });

    it("lists what was changed by hand in the bucket's Edit form, and removes one", async () => {
      const detail = { bucket: GIFTS, changes: [{ id: 3, bucketId: 7, date: "2026-10-01", amountCents: 15000, note: "Starting amount" }] };
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "GET /api/buckets/7": () => ({ status: 200, data: detail }),
        "POST /api/buckets/7/changes": () => ({ status: 201, data: detail, changed: true }),
        "DELETE /api/buckets/7/changes/3": () => ({ status: 200, data: detail, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      const dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      const history = within(dialog).getByRole("region", { name: "Changed by hand" });
      expect(await within(history).findByText("Starting amount")).toBeInTheDocument();
      await userEvent.click(within(history).getByRole("button", { name: "Remove the $150.00 put in on Oct 1, 2026" }));
      await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/buckets/7/changes/3"));
    });

    it("sets a new amount for a bucket from its Edit form, as an adjustment for the difference", async () => {
      const fetchMock = fakeBackend({
        "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
        "PUT /api/buckets/7": () => ({ status: 200, data: { bucket: GIFTS, changes: [] }, changed: true }),
        "POST /api/buckets/7/changes": () => ({ status: 201, data: { bucket: GIFTS, changes: [] }, changed: true }),
      });
      renderApp("/accounts/1");

      await userEvent.click(await screen.findByRole("button", { name: "Edit Holiday gifts" }));
      const dialog = screen.getByRole("dialog", { name: "Edit Holiday gifts" });
      const held = within(dialog).getByRole("textbox", { name: "In this bucket" });
      await userEvent.clear(held);
      await userEvent.type(held, "125");
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      // 200.00 before, 125.00 wanted.
      expect(bodiesSentTo(fetchMock, "POST /api/buckets/7/changes")).toEqual([
        { date: todayIso(), amountCents: -7500, note: "Amount adjusted" },
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
      expect(within(dialog).queryByRole("textbox", { name: "Already set aside (optional)" })).not.toBeInTheDocument();
      // What it holds is shown, and left alone it moves no money.
      expect(within(dialog).getByRole("textbox", { name: "In this bucket" })).toHaveValue("200.00");
      const goal = within(dialog).getByRole("textbox", { name: "Goal (optional)" });
      await userEvent.clear(goal);
      await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
      await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")).toHaveLength(1));
      expect(bodiesSentTo(fetchMock, "PUT /api/buckets/7")[0]).toMatchObject({ name: "Holiday gifts", goalCents: null, archived: false });
      expect(calls(fetchMock)).not.toContain("POST /api/buckets/7/changes");

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
