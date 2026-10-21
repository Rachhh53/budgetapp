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
  startingAvailable: {
    term: "Starting available",
    about:
      "What you have to spend as the pay period starts: the account's starting balance less what its buckets hold. It's always what the pay period before ended with available after buckets, unless you entered a balance.",
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
  payEarly: {
    term: "Pay a period early",
    about:
      "Paid from the paycheck before the pay period it's due in, so the money is there in time. Something due on a payday that you pay by hand is always paid this way.",
  },
  withdrawalRate: {
    term: "Withdrawal rate",
    about:
      "How much of your savings you'd take out a year once retired. At 4% (the 4% rule), enough is 25 times what you need a year. A lower rate is more cautious and asks for more saved. Years before Social Security or Medicare start are counted in full on top.",
  },
  retirementSalary: {
    term: "Salary in retirement",
    about:
      "What you'd like to spend a year once retired, in today's dollars. What you need saved is this divided by the withdrawal rate: $40,000 at 4% is $1,000,000.",
  },
  retireAt: {
    term: "Retire at",
    about:
      "An age you'd like to retire, to see what it takes: whether you're on track, or how much more to save a month and a paycheck to get there. Leave it empty to see only the earliest age that works.",
  },
  neededThen: {
    term: "Needed then",
    about:
      "What you'd need saved to retire at that age, in today's dollars: your salary plus health costs, less other income, divided by the withdrawal rate. Years before Social Security or Medicare start are counted in full on top, and one-offs still to come are taken off.",
  },
  forAge: {
    term: "For age",
    about:
      "For the age you chose under Goal: On track if what you save now is enough, or the extra to save a month (and a paycheck) to make it enough, at the expected growth. The extra rises with prices.",
  },
  byAgeChart: {
    term: "By age",
    about:
      "Your projected savings at each age, at the expected growth (solid) and the lower and higher growth (faint), against what retiring at that age needs (dashed). Where the solid line is above the dashed one, that age is enough. Hover or tap for the amounts.",
  },
  earliestRetirement: {
    term: "You can retire at",
    about:
      "The first age your projected savings reach what retiring then needs, at the expected growth. Beside it, the same at the lower and higher growth.",
  },
  projectedSavings: {
    term: "Projected savings",
    about:
      "What the accounts counted would hold at that age: today's balances, plus what goes in each year, compounding monthly at the scenario's growth. Traditional money counts after the retirement tax. It's shown in today's dollars, with the amount in that year's dollars beneath.",
  },
  growthRange: {
    term: "Lower, expected and higher growth",
    about:
      "How much the accounts grow a year before inflation, compounding monthly. Expected is your best guess; lower and higher show a bad and a good run, so you see a range instead of one number. Every counted account uses them.",
  },
  yearlyRaise: {
    term: "Raise a year",
    about:
      "How much more goes into your accounts each year than the year before, like a yearly raise with your contribution kept as a share of pay. Below inflation, what goes in shrinks in today's dollars.",
  },
  extraSaved: {
    term: "Extra saved a month",
    about: "A what-if: more saved every month on top of what already goes in, rising with prices, into the kind of account you pick.",
  },
  goesInAYear: {
    term: "Goes in a year",
    about:
      "What goes into the account over the coming year from your budget, less what comes out: paycheck contributions and the employer match (only from deductions whose Goes to is this account), recurring transfers and one-offs. Growth isn't part of it; the scenario's growth is added on top. Lower than you expect? A paycheck deduction may be missing its account.",
  },
  retirementTaxType: {
    term: "How it's taxed",
    about:
      "Traditional (pre-tax IRA or 401(k)) counts after the scenario's tax in retirement. Roth counts in full. Taxable or savings counts in full and is the only kind that covers years before 59½.",
  },
  retirementTax: {
    term: "Tax in retirement",
    about: "The share of traditional (pre-tax) money that goes to tax as you take it out. Roth and taxable money count in full.",
  },
  retirementHealth: {
    term: "Health costs rise",
    about:
      "How fast your health amounts go up each year. It applies only to the dollar amounts you enter for before and from 65; it isn't a share of your savings or income. At 5%, $9,000 a year counts as $9,450 the next year and $9,923 the year after. Since everything is shown in today's dollars, only the part above inflation shows.",
  },
  healthBefore65: {
    term: "Health insurance before 65",
    about:
      "What you'd pay a year, in today's dollars, for health insurance if you retire before Medicare starts at 65, such as $9,000. It's added to what each year before 65 needs, rising by Health costs rise. 0.00 means it isn't counted.",
  },
  healthFrom65: {
    term: "Health costs from 65",
    about:
      "What you'd pay a year, in today's dollars, once on Medicare: premiums, supplemental plans and what they don't cover, such as $4,000. It's added to what each year from 65 needs, rising by Health costs rise. 0.00 means it isn't counted.",
  },
  retirementInflation: {
    term: "Inflation",
    about: "How fast prices rise a year. Budget uses it to show every future amount in today's dollars and to raise the extra you save with prices.",
  },
  otherIncome: {
    term: "Other income",
    about: "Money that starts at an age, like Social Security or a pension, in today's dollars a year. It takes that much off what each year needs.",
  },
  retirementOneOff: {
    term: "One-off",
    about: "Money in or out once at an age, like selling a house (in) or a new roof (out). Money in is added to taxable savings that year.",
  },
  todaysDollars: {
    term: "Today's dollars",
    about:
      "Every retirement figure is what it would buy today: future amounts are brought back by the scenario's inflation, so $60,000 means what $60,000 buys now.",
  },
  beforeSixty: {
    term: "Retiring before 59½",
    about:
      "Money taken out of an IRA or 401(k) before 59½ usually carries a 10% penalty, so the years until then need to come from taxable or savings accounts. Budget checks they cover them.",
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
    about: "Something that happens once: a vet bill, a gift, a refund. Add one with + Add item. A bonus from work is a one-off paycheck, on its income source.",
  },
  recurringItem: {
    term: "Recurring item",
    about: "Anything that repeats: a bill, a budget, a transfer or a card payment.",
  },
  billedLater: {
    term: "Billed later",
    about:
      "Visits that add up and are billed afterwards, like a weekly appointment invoiced every few months. They wait on the current pay period until you mark them invoiced.",
  },
  paidAhead: {
    term: "Paid ahead",
    about:
      "Paid up front, like a course: each session uses up some of what you paid, and nothing more comes out of your account until you record another payment. The pay period warns you when it runs out.",
  },
  billedByUse: {
    term: "Billed by use",
    about:
      "For something used an uncertain number of times, like snowplowing: what each time costs × about how many times a week or a month, counted only in the months it can happen in. It's billed once a month for the days since the last bill, as an estimate you type the real bill over.",
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
