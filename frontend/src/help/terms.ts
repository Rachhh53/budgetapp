/**
 * The words Budget uses, each said once in plain language. The ⓘ tips beside a figure or heading
 * and the "Words Budget uses" list on the Help screen both read from here, so a word never means
 * two things in two places (Rachel, 2026-10-14: "maybe some tooltips for help too").
 */
export const TERMS = {
  estimate: {
    term: "Estimate",
    about: "An amount in a blue box is an estimate: Budget's best guess until the real figure is known. Click it to type the real amount.",
  },
  payPeriod: {
    term: "Pay period",
    about: "From one payday up to the day before the next. Your primary paycheck's schedule sets them.",
  },
  endingByPeriod: {
    term: "Ending balance by period",
    about: "What the account is projected to hold at the end of each pay period. Pick one to open it; a warning sign means it dips low.",
  },
  startingBalance: {
    term: "Starting balance",
    about: "What the account holds as the pay period starts: the latest balance you entered, carried forward with everything scheduled since.",
  },
  comingIn: {
    term: "Coming in",
    about: "Paychecks and any other money arriving in this pay period.",
  },
  goingOut: {
    term: "Going out",
    about: "Everything paid from the account in this pay period: bills, budgets, transfers, card payments and one-offs.",
  },
  projectedEnding: {
    term: "Projected ending",
    about: "Starting balance + coming in − going out: what the account should hold when the pay period ends.",
  },
  available: {
    term: "Available after buckets",
    about: "The projected ending less what is set aside in buckets: the money that is free to spend. Lowest is the least it gets to during the period.",
  },
  bucket: {
    term: "Bucket",
    about:
      "Part of a checking or savings account set aside for something, like Holiday gifts. The money stays in the account; it just isn't counted as available.",
  },
  autopay: {
    term: "Auto-pay & transfers",
    about: "What gets paid on its own, and money moving between your accounts. There is nothing to tick.",
  },
  payManually: {
    term: "Pay manually & one-offs",
    about: "Bills you pay yourself, each with a box to tick once it's paid, then anything that happens only once.",
  },
  budgets: {
    term: "Budgets",
    about: "Money set for spending each pay period, like groceries or gas. Each one counts on its own day.",
  },
  oneOff: {
    term: "One-off",
    about: "Something that happens once: a vet bill, a gift, a refund, a bonus. Add one with + Add item.",
  },
  recurringItem: {
    term: "Recurring item",
    about: "Anything that repeats: a bill, a budget, a transfer, a bucket move or a card payment.",
  },
  billedLater: {
    term: "Billed later",
    about:
      "Visits that add up and are billed afterwards, like a weekly appointment invoiced every few months. They wait on the current pay period until you mark them invoiced.",
  },
  saveUp: {
    term: "Saving up",
    about: "Budget sets a share aside in a bucket every paycheck, so the money is there when the bill comes due.",
  },
  primaryIncome: {
    term: "Primary paycheck",
    about: "The income source whose paydays set your pay periods. The first one you add is primary.",
  },
  enteredBalance: {
    term: "Balance you entered",
    about: "The real number from your bank's website or statement. Budget works forward from the latest one, so entering one now and then keeps everything right.",
  },
  netWorth: {
    term: "Net worth",
    about: "Everything you own (accounts, a home, other assets) less everything you owe on cards and loans.",
  },
  lowestAhead: {
    term: "Lowest balance ahead",
    about: "The lowest a checking or savings account is projected to get in the pay periods ahead, and when.",
  },
  savedInvested: {
    term: "Saved & invested this year",
    about: "Transfers into savings and investment accounts, what your paychecks put into them, and any employer match.",
  },
  extraEachMonth: {
    term: "Extra each month",
    about: "An amount you could put toward a debt or savings each month. Nothing is moved: it's only for comparing.",
  },
  payoffOrder: {
    term: "Payoff order",
    about:
      "The extra goes to one debt at a time. Highest rate first saves the most interest; smallest balance first clears one debt sooner. When a debt is gone, its payment rolls into the next.",
  },
  suggestionBasis: {
    term: "Suggestions based on",
    about: "What each suggested amount comes from: the last 12 months, the last 12 with each price's trend, or the last full year.",
  },
} as const satisfies Record<string, { term: string; about: string }>;

export type TermId = keyof typeof TERMS;
