import type { TermId } from "./terms";

/**
 * The user guide (Rachel, 2026-10-14: "a help screen that functions as a user guide … where to
 * start, help finding stuff"). Everything the Help screen says lives here as data, so the screen,
 * the "Where is…?" finder and each screen's ? link all read the same words.
 *
 * Button and card names are quoted exactly as the app shows them; when one changes, change it here.
 */

/** One screen's chapter of the guide. `path` is the screen it explains; `id` is its address under /help. */
export interface Topic {
  id: string;
  title: string;
  /** The screen it is about, opened by "Go to …". */
  path: string;
  /** One sentence: what the screen is for. */
  summary: string;
  sections: readonly { heading: string; body: readonly string[] }[];
  /** Words explained on this screen, listed at the foot of its chapter. */
  terms: readonly TermId[];
}

/** A "How do I…?" question the finder answers, with a button that goes where it's done. */
export interface Task {
  question: string;
  answer: string;
  to: string;
  button: string;
  /** The chapter it belongs to. */
  topic: string;
  /** Other words someone might type for it. */
  keywords: string;
}

/** A step of "Where to start": written out, with a button to the screen it's done on. */
export interface Step {
  title: string;
  body: string;
  to: string;
  button: string;
}

export const STEPS: readonly Step[] = [
  {
    title: "Let Budget walk you through it",
    body: "On Plan next year, Set up Budget goes through six short steps: accounts and income, bills, what comes once a year, spending budgets, and saving. You can stop at any point and carry on later. Prefer to do it yourself? Steps 2 to 5 are the same thing by hand.",
    to: "/plan",
    button: "Go to Plan next year",
  },
  {
    title: "Add your accounts",
    body: "On Accounts, + Add account for each checking, savings, credit card, investment and loan account, with what it holds today (the number your bank's website shows). A home or car goes in with + Add home or asset.",
    to: "/accounts",
    button: "Go to Accounts",
  },
  {
    title: "Add your paycheck",
    body: "On Income, + Add income source: what lands in your account and when. The first one you add is your primary paycheck, and its paydays set your pay periods. Add a second job or side income the same way.",
    to: "/income",
    button: "Go to Income",
  },
  {
    title: "Add your bills and budgets",
    body: "On Recurring items, + Add item for everything that repeats: rent, utilities, subscriptions, card and loan payments, and spending budgets like groceries. Not sure of an amount? Tick Estimate and fix it when the bill comes.",
    to: "/recurring",
    button: "Go to Recurring items",
  },
  {
    title: "Check your pay period",
    body: "Pay periods is home. It shows what's coming in and going out this paycheck and what's left. Tick each bill under Pay manually as you pay it, and add one-offs (a vet bill, a gift) with + Add item.",
    to: "/",
    button: "Go to Pay periods",
  },
  {
    title: "Keep it right, and keep it safe",
    body: "Every so often, enter your real balances (Update balances on Accounts) so the projections start from the truth. In Settings, choose a backup folder and, if you'd like, set a PIN to lock the app.",
    to: "/settings",
    button: "Go to Settings",
  },
];

export const TOPICS: readonly Topic[] = [
  {
    id: "pay-periods",
    title: "Pay periods",
    path: "/",
    summary: "Your home screen: one paycheck at a time, what comes in, what goes out and what's left.",
    sections: [
      {
        heading: "Reading the screen",
        body: [
          "The header names the pay period. Use the arrows (or ← and → on the keyboard) to move between periods, and the strip of Ending balance by period to jump to one.",
          "The five figures add up the period for the account chosen under Accounts & buckets: Starting balance + Coming in − Going out = Projected ending, and what's free after buckets.",
          "A red box above them means a bill you pay yourself is past due.",
        ],
      },
      {
        heading: "The lists",
        body: [
          "Income: paychecks and money coming in. Auto-pay & transfers: things that pay themselves. Budgets: spending set for this paycheck.",
          "Pay manually & one-offs: bills you pay yourself, each with a box to tick once it's paid, then anything that happens only once. Click a row to change it just this once, add a confirmation code or check number, or keep a receipt.",
          "To change a bill for good, open its row and choose Change … itself, or edit it on Recurring items.",
        ],
      },
      {
        heading: "Amounts",
        body: [
          "Click an amount to type the real one. Something that repeats asks whether you mean Just this one or This and all future.",
          "An amount in a blue box is an estimate. Hide amounts blurs every number, for when someone is looking over your shoulder.",
        ],
      },
      {
        heading: "Finding something",
        body: [
          "Find a bill or one-off (Ctrl+K) searches one-offs by description or payee and recurring items by name, and opens the pay period that has it, with the row outlined.",
        ],
      },
      {
        heading: "Billed later",
        body: [
          "A bill marked Billed later (like a weekly appointment invoiced every few months) shows here as visits not billed yet. When the invoice arrives, press Mark as invoiced: it becomes a bill on its due date, ticked when paid like any other.",
          "A session that didn't happen can be skipped on the item's own page, under Billed later.",
        ],
      },
    ],
    terms: ["payPeriod", "endingByPeriod", "startingBalance", "comingIn", "goingOut", "projectedEnding", "available", "payManually", "autopay", "budgets", "oneOff", "estimate", "billedLater"],
  },
  {
    id: "dashboard",
    title: "Dashboard, reports and annual costs",
    path: "/dashboard",
    summary: "The big picture: net worth, what needs attention, spending, reports and what things cost each year.",
    sections: [
      {
        heading: "Overview",
        body: [
          "Net worth, the lowest balance ahead, what you've saved and invested this year, and total debt across the top.",
          "Coming up & needs attention lists what to deal with: bills past due or due soon, a balance dipping low, a promotional rate ending, and more. Each line opens where it's dealt with.",
        ],
      },
      {
        heading: "Reports",
        body: [
          "The Reports tab builds a report from dates, what to include, filters and how to group it, as a chart and table. A report is of spending, of income, or of projected vs. actual. Save report keeps one; Export CSV, Export PDF and Export PDF with receipts make files.",
        ],
      },
      {
        heading: "Annual costs",
        body: [
          "The Annual costs tab shows every bill and budget by year, with the annual change. Open one to see it month by month and whether its amount is too low.",
        ],
      },
    ],
    terms: ["netWorth", "lowestAhead", "savedInvested"],
  },
  {
    id: "recurring",
    title: "Recurring items",
    path: "/recurring",
    summary: "Everything that repeats: bills, budgets, transfers, bucket moves and card payments.",
    sections: [
      {
        heading: "Adding one",
        body: [
          "+ Add item, then choose the type: Bill, Budget, Transfer, Bucket move or Card payment. A few short steps ask what it is, the amount, where it's paid from, and the schedule.",
          "Not sure of the amount? Tick Estimate. For something yearly, enter the year's total and Budget shares it out; Save up for it in a bucket sets money aside each paycheck so it's there when due.",
        ],
      },
      {
        heading: "Which paycheck pays it",
        body: [
          "A bill due on a payday is paid from the paycheck before, unless it's on autopay. Choose One period early to always pay it from the paycheck before.",
        ],
      },
      {
        heading: "Changing one",
        body: [
          "Open an item to see its page, then Edit on any card. A change can apply from a date on, so the past stays as it was.",
          "A bill billed later (visits invoiced afterwards) has a Billed later card listing the visits waiting, the next few to come, and every invoice. Skip a visit that didn't (or won't) happen; Put back undoes it.",
        ],
      },
    ],
    terms: ["recurringItem", "estimate", "saveUp", "billedLater", "bucket"],
  },
  {
    id: "accounts",
    title: "Accounts, cards, loans and buckets",
    path: "/accounts",
    summary: "Everything you own and owe, the real balances behind every projection, and buckets for setting money aside.",
    sections: [
      {
        heading: "Accounts",
        body: [
          "+ Add account for checking, savings, credit cards, investments and loans; + Add home or asset for a home, car or anything else of value.",
          "Update balances enters the real number for several accounts at once. Budget works forward from the latest balance you entered, so this is how you keep it honest.",
          "To be warned when an account runs low, set Warn me when the balance dips below on the account.",
        ],
      },
      {
        heading: "Buckets",
        body: [
          "On a checking or savings account's page, Add bucket sets part of the account aside for something, with a goal if you like. The money stays in the account but isn't counted as available.",
        ],
      },
      {
        heading: "Credit cards",
        body: [
          "A card's page has its Statements (Save statement for each one), Promo rates with warnings before they end, a Payoff helper that works out what to pay each paycheck, and If it's not paid off.",
          "How the card is paid is set on its card payment in Recurring items. Add as a recurring payment sets one up.",
        ],
      },
      {
        heading: "Loans and homes",
        body: [
          "A loan's page shows the payoff date and interest still to pay. Its monthly payment is a recurring item; the loan's page can add or link one.",
          "A home linked to its mortgage shows your equity.",
        ],
      },
    ],
    terms: ["enteredBalance", "bucket", "available", "netWorth"],
  },
  {
    id: "income",
    title: "Income and pay stubs",
    path: "/income",
    summary: "Your paychecks: when they come, what lands in your account, and what comes out before it does.",
    sections: [
      {
        heading: "Income sources",
        body: [
          "+ Add income source for each job or regular income. The primary one's paydays set your pay periods.",
          "Coming out of every paycheck holds deductions like a 401(k) or HSA; Employer match holds what your employer adds.",
        ],
      },
      {
        heading: "A raise or new deductions",
        body: [
          "On the source's page, Record a pay change from the date it starts. Paychecks before it keep what they had, and Pay history lists every change.",
        ],
      },
      {
        heading: "Pay stubs",
        body: [
          "+ Add item › Upload a pay stub or receipt… reads a PDF pay stub. Check the lines it read, then save it to correct your amounts, record a pay change, or set just that paycheck.",
        ],
      },
    ],
    terms: ["primaryIncome", "payPeriod"],
  },
  {
    id: "expenses",
    title: "Expenses & receipts",
    path: "/expenses",
    summary: "One-off spending, the receipts that go with it, and money you're owed back.",
    sections: [
      {
        heading: "Adding",
        body: [
          "+ Add item › Money out adds a one-off expense; Money in adds a refund, gift or bonus. Either can go on any account, a credit card included.",
        ],
      },
      {
        heading: "Receipts",
        body: [
          "Open an expense and add files under Receipts & documents (PDF, JPEG or PNG). A bill on the pay period keeps its own: open its row and choose Add a receipt.",
        ],
      },
      {
        heading: "Reimbursements",
        body: [
          "On an expense's page, Reimbursement tracks money owed back: to submit, submitted, or reimbursed. When the money arrives, add it as money in that pays back the expense.",
        ],
      },
    ],
    terms: ["oneOff"],
  },
  {
    id: "plan",
    title: "Plan next year",
    path: "/plan",
    summary: "Set next year's amounts from what this year actually cost, or set up Budget for the first time.",
    sections: [
      {
        heading: "Planning a year",
        body: [
          "Start a plan and Budget suggests an amount for every bill and budget from what the last 12 months came to. Accept them or type your own, step by step.",
          "Nothing changes until the plan's year starts. A draft can be deleted and redone any time before then.",
        ],
      },
      {
        heading: "First-time setup",
        body: [
          "With nothing set up yet, Set up Budget walks through the same steps for the year under way, adding your accounts, income and recurring items as you go.",
        ],
      },
    ],
    terms: ["suggestionBasis"],
  },
  {
    id: "planner",
    title: "Debt & decisions",
    path: "/planner",
    summary: "Compare where extra money would do the most: paying down a debt, investing or saving.",
    sections: [
      {
        heading: "Comparing",
        body: [
          "Enter an Extra each month and a period to compare over. Each debt and savings or investment account gets a card saying what the extra would do there. Nothing is moved and nothing is advice: it's estimates, for comparing.",
          "Worth knowing first lists an employer match you aren't getting in full and promotional rates ending.",
        ],
      },
      {
        heading: "Payoff order",
        body: [
          "The payoff card shows when you'd be debt-free with the extra, how much interest it saves, and Debt over time with and without it. Switch between highest rate first and smallest balance first.",
          "Leave out an account you don't want compared; Bring back returns it.",
        ],
      },
    ],
    terms: ["extraEachMonth", "payoffOrder"],
  },
  {
    id: "categories",
    title: "Categories & tags",
    path: "/categories",
    summary: "Group spending so reports and the dashboard can add it up.",
    sections: [
      {
        heading: "Categories and tags",
        body: [
          "Every item and one-off can have one category (like Home › Utilities) and any number of tags (like Taxes or Kids). Add them here or by typing a new name in any category or tag picker.",
          "Click a color dot to change its color.",
        ],
      },
    ],
    terms: [],
  },
  {
    id: "settings",
    title: "Settings, security and backups",
    path: "/settings",
    summary: "How Budget looks and warns you, your PIN, your backups, and opening Budget on your phone.",
    sections: [
      {
        heading: "Appearance and defaults",
        body: [
          "Appearance chooses light, dark or the system's setting, and your accent color.",
          "Under Pay periods & projections, New things start on sets the date new things open with: today, or an earlier day if you're entering a year from its start.",
        ],
      },
      {
        heading: "Security",
        body: [
          "Set a PIN to lock Budget after it sits idle. You'll get a recovery code once: keep it somewhere safe. On the lock screen, I forgot my PIN takes the recovery code.",
          "Start with amounts hidden blurs every number each time Budget opens.",
        ],
      },
      {
        heading: "Backups",
        body: [
          "Choose folder… picks where daily backups go (another drive is safest). Back up now makes one at once; Restore… brings one back.",
        ],
      },
      {
        heading: "Phone access",
        body: [
          "Phone access opens Budget on your phone while this PC is on and awake, with Budget open. Your data stays on this PC, and the phone always needs your PIN. It's off until you choose a network under Phone access and press Turn on phone access; Turn off phone access signs every phone out at once.",
          "Use a VPN: it encrypts everything between your phone and this PC, at home or away. With Tailscale, install it on this PC and on your iPhone (from the App Store), sign in to the same account on both, then choose Tailscale under Phone access. Tailscale's company can see which devices you have and when they're online, never your budget.",
          "WireGuard keeps any company out of it, but needs a WireGuard server on this PC or on your router (many routers have one built in), and reaching it from away means opening one port on the router. Install WireGuard from the App Store and add the setup your router or PC gives you.",
          "Home Wi-Fi or a network cable works too, but isn't encrypted: anyone else on that network could see your numbers and your PIN as they go by. Budget warns you when you choose one.",
          "A VPN for browsing privately (Hotspot Shield, NordVPN, ExpressVPN and the like) can't connect your phone to this PC, even though it's listed under Phone access: it only links this PC to that company's servers. It can also get in the way of Tailscale or home Wi-Fi, so you may need to turn it off while you use phone access.",
          "Never use a public tunnel such as Cloudflare Tunnel or ngrok. It puts Budget on the internet, and the company in the middle can see everything.",
          "On the iPhone, open the address shown under Phone access in Safari, then Share › Add to Home Screen, and use Budget from the Home Screen rather than a Safari tab.",
          "To keep your iPhone from holding on to anything: in the iPhone's Settings › Apps › Safari, turn off Search Engine Suggestions and Safari Suggestions; if it offers to save your Budget PIN in Passwords, choose Not Now; and don't use Summarize, Translate or other Apple Intelligence features on Budget.",
          "If Windows asks whether to let Budget through its firewall, allow Private networks only. Allowed Public by mistake? Open Windows Defender Firewall with Advanced Security › Inbound Rules, delete Budget's rules, and Windows asks again next time.",
          "If Windows calls the network Public, its firewall keeps your phone out, and the phone just keeps loading. For your own home Wi-Fi, in Windows Settings › Network & internet › Wi-Fi, open the network and choose Private network. That also lets other devices on it find this PC (for sharing files and printers), so only do it on a network you trust.",
          "A sleeping PC can't answer your phone. To keep it awake while it's plugged in, in Windows Settings › System › Power & battery, under Screen and sleep, set the sleep time when plugged in to Never. Budget never changes this for you.",
        ],
      },
      {
        heading: "Your data",
        body: [
          "Everything stays on this PC. Budget never sends anything to the internet; with Phone access on, it only answers your own phone, after your PIN. Erase all data, at the bottom, removes everything for good.",
        ],
      },
    ],
    terms: [],
  },
];

export const TASKS: readonly Task[] = [
  { question: "How do I mark a bill as paid?", answer: "On Pay periods, tick its box under Pay manually & one-offs. The day you tick it is kept as the day it was paid.", to: "/", button: "Go to Pay periods", topic: "pay-periods", keywords: "tick check paid done pay bill checkbox" },
  { question: "How do I add a one-off expense?", answer: "Press + Add item (or Ctrl+N) and choose Money out.", to: "/", button: "Go to Pay periods", topic: "expenses", keywords: "expense spend purchase vet gift once one-off add new money out" },
  { question: "How do I add money that came in once?", answer: "Press + Add item and choose Money in: a refund, gift, bonus or side cash.", to: "/", button: "Go to Pay periods", topic: "expenses", keywords: "refund bonus gift income money in once" },
  { question: "How do I add a bill that repeats?", answer: "On Recurring items, + Add item and choose Bill.", to: "/recurring", button: "Go to Recurring items", topic: "recurring", keywords: "bill rent utility subscription repeat monthly weekly new" },
  { question: "How do I set a spending budget?", answer: "On Recurring items, + Add item and choose Budget, like groceries every paycheck.", to: "/recurring", button: "Go to Recurring items", topic: "recurring", keywords: "budget groceries gas spending limit" },
  { question: "How do I change a bill's amount just this once?", answer: "On its pay period, click the amount and type the real one, then choose Just this one.", to: "/", button: "Go to Pay periods", topic: "pay-periods", keywords: "change amount once this time edit different" },
  { question: "How do I change a bill for good?", answer: "Open it on Recurring items and press Edit on the card to change, or click its amount on the pay period and choose This and all future.", to: "/recurring", button: "Go to Recurring items", topic: "recurring", keywords: "edit change amount schedule future permanent" },
  { question: "How do I find a bill or expense?", answer: "On Pay periods, use Find a bill or one-off (Ctrl+K). It shows when each was last paid and is next planned.", to: "/", button: "Go to Pay periods", topic: "pay-periods", keywords: "search find look where when last paid planned" },
  { question: "How do I keep a receipt?", answer: "For a one-off, open it on Expenses & receipts and add the file under Receipts & documents. For a bill, open its row on the pay period and choose Add a receipt.", to: "/expenses", button: "Go to Expenses & receipts", topic: "expenses", keywords: "receipt photo pdf document invoice attach file upload" },
  { question: "How do I track money I'm owed back?", answer: "Open the expense and use its Reimbursement card. When the money arrives, add it as money in that pays back the expense.", to: "/expenses", button: "Go to Expenses & receipts", topic: "expenses", keywords: "reimburse reimbursement owed back work hsa claim submit" },
  { question: "How do I enter my real balance?", answer: "On Accounts, Update balances, or Update balance on one account's page. Use the number your bank shows.", to: "/accounts", button: "Go to Accounts", topic: "accounts", keywords: "balance real actual bank correct fix wrong update" },
  { question: "How do I set money aside for something?", answer: "On a checking or savings account's page, Add bucket. Give it a goal if you like.", to: "/accounts", button: "Go to Accounts", topic: "accounts", keywords: "bucket save aside goal sinking fund envelope" },
  { question: "How do I save up for a yearly bill?", answer: "Edit the bill and choose Save up for it in a bucket: a share is set aside each paycheck so it's there when due.", to: "/recurring", button: "Go to Recurring items", topic: "recurring", keywords: "save up yearly annual insurance registration bucket" },
  { question: "How do I get warned when money runs low?", answer: "Edit the account and set Warn me when the balance dips below. Pay periods that go lower are flagged.", to: "/accounts", button: "Go to Accounts", topic: "accounts", keywords: "low balance warning alert threshold overdraft" },
  { question: "How do I add a credit card statement?", answer: "Open the card on Accounts and use its Statements card.", to: "/accounts", button: "Go to Accounts", topic: "accounts", keywords: "credit card statement minimum due" },
  { question: "How do I track a 0% promo rate?", answer: "Open the card on Accounts, then Add promo rate. Budget warns you before it ends, and the Payoff helper works out what to pay.", to: "/accounts", button: "Go to Accounts", topic: "accounts", keywords: "promo promotion 0% balance transfer deferred interest" },
  { question: "How do I record a raise?", answer: "On Income, open the source and press Record a pay change, from the date it starts.", to: "/income", button: "Go to Income", topic: "income", keywords: "raise pay change salary deductions new job" },
  { question: "How do I read a pay stub?", answer: "Press + Add item, then Upload a pay stub or receipt…, and drop the PDF.", to: "/income", button: "Go to Income", topic: "income", keywords: "pay stub paystub pdf upload payslip" },
  { question: "How do I handle a bill that's invoiced later?", answer: "Edit the bill and tick Billed later. Visits add up on the pay period until you press Mark as invoiced.", to: "/recurring", button: "Go to Recurring items", topic: "recurring", keywords: "billed later invoice visits therapy appointment accumulate quarterly" },
  { question: "How do I skip a session that didn't happen?", answer: "Open the item on Recurring items. Under Billed later, press Skip beside the visit. Put back undoes it.", to: "/recurring", button: "Go to Recurring items", topic: "recurring", keywords: "skip session visit cancel missed appointment" },
  { question: "How do I undo something?", answer: "Press Undo on the message that appears after a change, or Ctrl+Z. It goes back one change at a time.", to: "/", button: "Go to Pay periods", topic: "pay-periods", keywords: "undo mistake oops back revert" },
  { question: "How do I hide my numbers?", answer: "Press Hide amounts at the top of Pay periods. Start with amounts hidden, in Settings under Security, hides them each time Budget opens.", to: "/", button: "Go to Pay periods", topic: "pay-periods", keywords: "hide blur privacy amounts screen" },
  { question: "How do I see where my money went?", answer: "On the Dashboard, Spending by category; for more, the Reports tab.", to: "/dashboard", button: "Go to Dashboard", topic: "dashboard", keywords: "spending where report category chart" },
  { question: "How do I make or export a report?", answer: "Dashboard › Reports. Choose dates and filters, then Save report, or Export CSV or PDF.", to: "/dashboard/reports", button: "Go to Reports", topic: "dashboard", keywords: "report export csv pdf taxes print" },
  { question: "How do I see what things cost each year?", answer: "Dashboard › Annual costs.", to: "/dashboard/annual", button: "Go to Annual costs", topic: "dashboard", keywords: "annual yearly cost total year increase" },
  { question: "How do I plan next year's budget?", answer: "On Plan next year, start the plan and accept or change each suggestion.", to: "/plan", button: "Go to Plan next year", topic: "plan", keywords: "plan next year budget suggestions" },
  { question: "Should I pay off debt or save?", answer: "Debt & decisions compares what an extra amount each month would do on each debt and account.", to: "/planner", button: "Go to Debt & decisions", topic: "planner", keywords: "debt payoff invest save extra interest decide" },
  { question: "How do I back up my data?", answer: "In Settings, under Backups: Choose folder…, then Back up now. Budget backs up daily after that.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "backup back up restore copy safe data" },
  { question: "How do I lock Budget with a PIN?", answer: "In Settings, under Security, Set a PIN. Keep the recovery code somewhere safe.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "pin lock password security protect" },
  { question: "I forgot my PIN.", answer: "On the lock screen, press I forgot my PIN and type your recovery code.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "forgot pin recovery code locked out reset" },
  { question: "How do I use Budget on my phone?", answer: "Set a PIN, then in Settings, under Phone access, choose your VPN (or home Wi-Fi) and press Turn on phone access. Open the address it shows in Safari and add it to your Home Screen.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "phone iphone mobile remote tailscale wireguard vpn wifi away home screen" },
  { question: "My phone can't reach Budget.", answer: "This PC has to be on and awake with Budget open, and the network chosen under Phone access connected (for a VPN, running on both). A private-browsing VPN like Hotspot Shield can't work, and a network Windows calls Public is blocked by its firewall; Help › Settings explains both.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "phone can't connect reach unreachable offline sleep vpn firewall" },
  { question: "How do I change the colors or dark mode?", answer: "In Settings, under Appearance.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "theme dark light color colour accent appearance" },
  { question: "How do I add a holiday that moves paydays?", answer: "In Settings, under Bank holidays, Add a holiday.", to: "/settings", button: "Go to Settings", topic: "settings", keywords: "holiday payday weekend bank" },
  { question: "How do I add or change categories?", answer: "On Categories & tags, or by typing a new name in any category picker.", to: "/categories", button: "Go to Categories & tags", topic: "categories", keywords: "category categories tag tags group label" },
];

/** ? on a screen opens the chapter for it: the longest path that starts the address wins. */
export function topicForPath(pathname: string): Topic | undefined {
  const matches = TOPICS.filter((topic) => (topic.path === "/" ? pathname === "/" || pathname.startsWith("/periods") : pathname.startsWith(topic.path)));
  // Detail pages that belong to a chapter whose screen has another address.
  if (matches.length === 0 && pathname.startsWith("/assets")) {
    return TOPICS.find((topic) => topic.id === "accounts");
  }
  return matches.sort((a, b) => b.path.length - a.path.length)[0];
}

export function topicById(id: string | undefined): Topic | undefined {
  return TOPICS.find((topic) => topic.id === id);
}
