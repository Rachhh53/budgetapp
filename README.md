# Budget

A private, local-only desktop budgeting app for Windows, organised around **pay periods**.
All data stays on your PC: no cloud, no telemetry, no update checks, no outbound network calls.

- `spec.md` — the build plan and source of truth
- `proposals.md` — the features being tracked
- `design/` — mockups for every screen (sample data only)
- `.github/workflows/ci.yml` — tests and dependency checks on every branch push; the installer only when asked for by hand

## Layout

```
backend/
  engine/   pure calculation code (Money, rates, …) — no I/O, JUnit-tested
  server/   Javalin API, SQLite + Flyway, sessions, lifecycle
frontend/   React + TypeScript + Vite + Tailwind
desktop/    Tauri 2 shell: starts/stops the backend, opens the window
build-installer.ps1
```

## Develop

Needs a JDK 21+ (`JAVA_HOME`) and Node LTS. Maven comes from the wrapper.

```powershell
Copy-Item .env.example .env          # dev settings; keeps dev data out of %APPDATA%\Budget

cd backend
.\mvnw.cmd verify                    # build + all backend tests
java -jar server\target\app\budget-server.jar   # run the API; prints READY port=NNNN

cd ..\frontend
npm ci
npm run dev                          # UI with hot reload; /api is forwarded to the backend
npm test                             # Vitest
npm run typecheck
```

The backend reads its settings from environment variables only (see `.env.example`); load `.env`
into your shell or IDE run configuration.

## Keyboard shortcuts

| Keys | What they do |
|---|---|
| Ctrl+N | open the **+ Add item** menu (straight to the form on a screen without the button) |
| Ctrl+K (⌘K on a Mac) | on the pay period screen: find a bill or one-off, and when it last happened or is next planned |
| Ctrl+E / Ctrl+I | add money out / money in |
| Ctrl+Z | undo the last change (left alone while typing in a field) |
| ← / → | previous / next pay period, on the home screen |
| Enter / Esc / Tab | while typing over an amount: save / cancel / save and move on |
| `+` `-` in an amount | a sum typed into any amount (`4005+3751`) is worked out when the field is left |
| digits, `+ - * /`, Enter, Backspace, Esc | in a money field's calculator: type the sum, work it out, delete a digit, cancel |

## Build the installer

Additionally needs the Rust toolchain and the Visual Studio C++ Build Tools.

```powershell
.\build-installer.ps1
```

Produces one per-user NSIS installer (no admin rights) under
`desktop\src-tauri\target\release\bundle\nsis\`, bundling a trimmed Java runtime, the backend
and the UI. Before bundling, the script starts the packaged backend once and stops the build if
it does not come up and serve the UI.

The build compiles the small Rust shell with `rustc`, which is the step that uses the most memory
(about 2 GB at its peak). It runs only during the build, never in the installed app.

## Installing, test installs and your data

You can install Budget now to try it and install a newer build later, but they are not two
separate apps: every install is "Budget" to Windows, and every install uses the same data.

- **Install location.** The installer lets you choose a folder (for example `budget-test`).
  Running a later installer detects the existing install and upgrades or replaces it rather
  than adding a second copy.
- **Data.** Your data always lives in `%APPDATA%\Budget`, wherever the program itself is
  installed. Anything you enter while testing is still there when you install a newer version.
- **Starting clean.** To go from test data to real use, either
  - open **Settings → Erase all data** in the app, which permanently removes everything and
    leaves a fresh app (backups you saved to another folder are not touched), or
  - uninstall with **Delete the application data** ticked, or
  - delete the `%APPDATA%\Budget` folder by hand while the app is closed.
- **A separate test copy** that runs alongside the real one with its own data is not available
  yet; it would need its own app name, identifier and data folder in the build.

## How it runs

1. The Tauri shell opens its window at once on a bundled "Opening your budget…" page and starts
   the bundled backend, passing its own process id, the data folder and a one-time shutdown token.
   (A `BUDGET_DATA_DIR` already in the shell's environment is passed on as it is, so a build can
   be tried on a throwaway folder.)
2. The backend takes a lock on the data folder (one instance only), backs up the database if an
   upgrade is pending, runs Flyway migrations, binds `127.0.0.1` on port 4873 (or the next free
   one) and prints `READY port=NNNN`.
3. The shell moves its window to that address. The backend serves both the API and the UI, so
   there is a single origin and no CORS.
4. Closing the window sends `POST /api/shutdown`; the backend also exits by itself if the shell dies.

## API

Every response is `{ data, error, status }`. Every route needs a session cookie except those marked open.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | open |
| POST | `/api/session` | open; unlock with `{ pin }`. With no PIN set, an empty body unlocks from this PC only. A wrong PIN is 401; too many are 429 with how long to wait |
| GET | `/api/session` | open; `{ unlocked, pinSet, retryAfterSeconds, offerPin }` |
| DELETE | `/api/session` | lock |
| PUT | `/api/pin` | set or change the PIN `{ newPin, currentPin? }` (4 to 8 digits; `currentPin` once one is set). Setting the first one answers with the recovery code, shown once. Every other session ends |
| DELETE | `/api/pin` | remove the PIN `{ currentPin }` |
| DELETE | `/api/pin/offer` | "not now" to the first-launch offer of a PIN, so it is not offered again |
| POST | `/api/pin/recovery` | open; forgot the PIN: `{ recoveryCode, newPin }` sets the new PIN, unlocks, and answers with a new recovery code. Wrong codes count with wrong PINs |
| POST | `/api/pin/recovery-code` | a new recovery code `{ currentPin }`; the old one stops working |
| GET, POST | `/api/backups` | the backups in the chosen folder, newest first, with the folder and how many are kept; POST backs up now |
| PUT | `/api/backups/folder` | this PC only; choose where backups go `{ path }`, or `null` for the app's own folder |
| POST | `/api/backups/restore` | this PC only; replace everything with a backup `{ name, confirm: "restore-backup" }`. The current data is saved first; the PIN and backup folder are kept; every session ends |
| GET, POST | `/api/folders` | this PC only; for the folder chooser: the drives, or the folders inside `?path=`; POST adds one `{ parent, name }` |
| GET | `/api/settings` | all settings with effective values |
| PUT | `/api/settings/{key}` | body `{ "value": "…" }`; validated against fixed choices |
| GET, POST | `/api/accounts` | list; add `{ name, type, color, institution?, subtypeLabel?, lowBalanceThresholdCents? }` (the threshold is for checking and savings) |
| GET, PUT, DELETE | `/api/accounts/{id}` | one account with its balances, interest settings and rate history; change; delete (refused while income, an item or an entry uses it) |
| PUT, DELETE | `/api/items/{id}/pause` | pause a recurring item from today, `{ until? }` (the first day it runs again; left out to pause until resumed); DELETE resumes it today. Paused dates never happen, even after resuming |
| GET | `/api/account-balances` | what every account holds at the end of today (`balanceCents`) and what of it is outside buckets (`availableCents`): the last balance entered plus everything since, or built up from nothing when none was entered |
| GET | `/api/accounts/{id}/transactions` | what moved the account, newest first: paychecks, recurring items, one-off entries, transfers arriving, estimated interest and balances entered, each `{ date, label, kind, amountCents, unpaid }`; optional `from`, `to` (default: this year so far through the next month) |
| PUT | `/api/account-balances` | enter balances for several accounts on one day `{ date, balances: [{ accountId, amountCents }] }`, as one change that Undo takes back together; `{ saved }` |
| GET | `/api/accounts/{id}/performance` | `thisYear` `{ since, startCents, nowCents, changeCents, depositsCents, growthCents }` and, for each balance entered, its change since the one before, what was put in between them and the rest (growth) |
| GET, POST | `/api/assets` | a home, vehicle or anything else owned, with today's value, what is owed on its linked loan and equity; add `{ name, kind, color, appreciationBp?, linkedLoanAccountId?, valueCents?, valueDate? }`. `kind` is `home`, `vehicle` or `other`; `appreciationBp` is an assumed annual change in its value: above zero it gains (up to 100000), below zero it loses (down to -9900, a loss of 99% a year) |
| GET, PUT, DELETE | `/api/assets/{id}` | one asset with every value entered; change or archive; delete |
| POST | `/api/assets/{id}/values` | enter a value `{ date, valueCents }`; the same day again replaces it |
| DELETE | `/api/assets/{id}/values/{valueId}` | remove one value |
| GET | `/api/net-worth` | today: `{ date, netWorthCents, assetsCents, liabilitiesCents, cashCents, investmentsCents, propertyCents, cardsCents, loansCents }`, every part a positive amount |
| GET | `/api/accounts/{id}/projection` | the balance, and what is available after buckets, at each pay period's end; optional `from`, `to` (any dates up to ten years apart, between 2000 and 2100: the past as worked out, or past the look-ahead) |
| PUT | `/api/accounts/{id}/interest` | when interest is credited and where it goes `{ creditDay, destination, destinationBucketId? }`; `creditDay` 31 means the last day; `destination` is `unassigned`, `bucket` or `proportional` |
| POST | `/api/accounts/{id}/rates` | a rate from a date on `{ rateBp, effectiveDate }` (4.10% is 410); the same date again replaces it |
| DELETE | `/api/accounts/{id}/rates/{rateId}` | remove one rate |
| GET | `/api/cards` | every credit card with what is owed today, its settings, regular APR, next payment and the promotion that ends soonest with a balance on it |
| GET | `/api/cards/{id}` | one card: the above plus every promotion (remaining balance, days left, warning, payoff per paycheck), its statements (the next one projected, then those entered), APR history and the next close and due dates |
| PUT | `/api/cards/{id}/minimum` | how its minimum payment is estimated until a statement with the real one is entered `{ shareBp, floorCents, plusInterest }`: a share of the statement balance (1% is 100), never less than the floor, with or without that month's interest. Needs the card's statement and due days first (409) |
| PUT | `/api/cards/{id}/settings` | how it bills `{ statementCloseDay, dueDay, monthlyEstimateCents? }`. How it is paid is not set here: the card's payment is a recurring item (`/api/items`, kind `card_payment`, with `cardPaymentMode`) |
| POST | `/api/cards/{id}/statements` | a statement as it arrived `{ closeDate, balanceCents, minimumDueCents?, dueDate? }`; the same closing date again replaces it |
| DELETE | `/api/cards/{id}/statements/{statementId}` | remove one statement |
| POST | `/api/cards/{id}/promos` | a promotional rate `{ label, kind, rateBp, startDate, endDate, originalBalanceCents?, rateAfterBp?, deferredInterest }`; `kind` is `balance_transfer`, `purchase` or `other`. `originalBalanceCents` is the amount the rate is on; left out, it is on everything the card owes, new charges included (deferred interest needs an amount) |
| PUT, DELETE | `/api/cards/{id}/promos/{promoId}` | change or remove one promotion |
| POST | `/api/cards/{id}/promos/{promoId}/payoff` | the payoff helper: adds a card payment every pay period until the promotion ends, sized to clear it |
| GET, PUT | `/api/accounts/{id}/loan` | a loan's terms and what they work out to (next payment shared into interest, principal and escrow; payoff date; interest still to pay; year by year); set `{ paymentCents, paymentDay?, escrowInsuranceCents?, escrowTaxesCents?, originalPrincipalCents?, termMonths?, startDate? }`. The rate is one of the account's rates. `paymentItemId?` links the recurring item that pays it (a bill, or a transfer into the loan); `paidBy` lists what pays it |
| POST | `/api/accounts/{id}/loan/payment` | add the loan's payment as a recurring transfer into it `{ payFromAccountId, autopay? }`, monthly on the payment day from today; refused once something pays the loan |
| GET, POST | `/api/buckets` | buckets with today's balance; add `{ accountId, name, color, goalCents?, goalDate?, note?, startingCents?, openedOn? }` (checking and savings only); `openedOn` is the day it counts from, today or earlier. With a goal and a `goalDate`, each bucket carries `goalProgress`: `{ projectedCents, shortCents, paydaysLeft, morePerPaycheckCents, morePerMonthCents }` |
| GET, PUT, DELETE | `/api/buckets/{id}` | one bucket with the money moved by hand; change or archive; delete (refused while a recurring item uses it) |
| POST | `/api/buckets/{id}/changes` | put money in or take it out by hand `{ date, amountCents, note? }` (negative takes out) |
| DELETE | `/api/buckets/{id}/changes/{changeId}` | remove one of those |
| POST | `/api/accounts/{id}/balances` | enter a balance `{ date, amountCents, note? }`; the same day again replaces it |
| DELETE | `/api/accounts/{id}/balances/{entryId}` | remove one entry |
| GET, POST | `/api/income-sources` | list; add `{ name, depositAccountId, netCents, grossCents?, netIsEstimate?, primary?, schedule, deductions?, match? }`. `match` is the employer match, paid into an investment account: `{ toAccountId, mode: "fixed", fixedCents }` or `{ toAccountId, mode: "formula", matchBp, capBpOfGross }` (50% up to 6% of gross is 5000 and 600; without `grossCents` it also needs `contributionWithinCap: true`, the user's confirmation that what they put in is within that share, and all of it is then matched). Each deduction is `{ name, toAccountId?, amountCents }` or `{ name, toAccountId?, percentBp }`, a share of gross pay (6% is 600; needs `grossCents`) that comes back with the `amountCents` it works out to. Each source comes back with `matchFigures` `{ perPaycheckCents, mostPossibleCents, contributeMoreCents }` |
| PUT, DELETE | `/api/income-sources/{id}` | change; delete (the primary one only when it is the last). The amounts sent (`grossCents`, `netCents`, `netIsEstimate`, `deductions`, `match`) correct the ones in force today, unless the body says otherwise: `effectiveFrom` (a date) makes them a change of pay from that day on — a raise — with earlier paychecks keeping theirs, and `originalAmounts: true` corrects the amounts the source started with. Each source comes back with the amounts in force today and `payHistory`, every set oldest first: `{ id, effectiveDate, grossCents, netCents, netIsEstimate, deductions, match, matchFigures, current }` (`id` and `effectiveDate` are null for the amounts it started with) |
| PUT | `/api/income-sources/{id}/primary` | make this the income whose paydays set the pay periods |
| DELETE | `/api/income-sources/{id}/pay-changes/{changeId}` | remove one change of pay, so the amounts before it carry on; returns the source |
| GET | `/api/income-sources/{id}/this-year` | what the source brings home this calendar year: `{ year, soFarCents, paychecksSoFar, grossSoFarCents, grossPaychecksSoFar, grossWholeYearCents, grossPaychecksInYear, wholeYearCents, paychecksInYear, estimate }` — paychecks that have arrived (today's included) and every paycheck of the year, each counted in the year it arrives |
| PUT | `/api/income-sources/{id}/paychecks/{scheduledDate}` | type over one paycheck `{ netCents }` |
| GET, POST | `/api/income-sources/{id}/pay-stubs` | the source's pay stubs, newest first `{ id, name, payDate, status, applied, grossCents, netCents, toCheck }`; POST adds one as `multipart/form-data` (field `file`, a single PDF). It is read on this PC and waits in review, changing nothing. 415 when it is not a PDF, 422 when it has no text in it (a scan) or cannot be opened |
| POST | `/api/pay-stubs/read` | read a stub without keeping it `{ payDate, lines, totals }`, to fill in an income source that does not exist yet |
| GET, PUT, DELETE | `/api/pay-stubs/{id}` | one stub: its lines (`section`, `label`, `amountCents`, `ytdCents`, `tracksAs`, `accountId`, `sure`), `totals` (with `differenceCents` and `balanced`: gross less everything taken out against the net pay), `toCheck`, the `paycheck` it is for and what saving it `wouldSet`; PUT sends a stub in review back `{ payDate, lines }` (409 once saved, until it is reopened); DELETE removes it and leaves what it changed, Undo brings it back |
| POST | `/api/pay-stubs/{id}/save` | save a stub in review `{ apply }`: `correct` the amounts in force, `pay_change` from the paycheck it is for, `paycheck` for just that one (409 when no paycheck is within 4 days of its date) or `none`. How each line was filed is remembered for the source |
| POST | `/api/pay-stubs/{id}/reopen` | put a saved stub back in review to be changed and saved again; what it changed stays until then |
| GET | `/api/pay-stubs/{id}/file`, `/api/pay-stubs/{id}/preview?page=` | the PDF as it was added; a page of it as a picture |
| POST | `/api/pay-stubs/{id}/copy` | "Save a copy", as for an attachment |
| GET | `/api/paychecks` | paychecks, today to the horizon; optional `from`, `to` |
| GET | `/api/pay-periods` | pay periods from the primary source, each with its projected ending balance; optional `from`, `to` |
| GET | `/api/pay-periods/{start}` | everything in the period that opens on `start`: paychecks, recurring items, one-offs, and each account's starting, in, out, ending and lowest balance, what is in its buckets, what is available after them, projected interest, and whether it dips below its low-balance threshold; `statementsPaid` says which statement each card payment that follows its card was sized from `{ itemId, scheduledDate, closeDate, entered }`. In the current period only, `unbilled` lists each billed-later item's visits no invoice covers yet, up to today: `{ itemId, name, payFromAccountId, visits, amountCents, since, through }` |
| GET, POST | `/api/categories` | list with item counts and yearly totals; add `{ name, color, parentId? }` |
| PUT, DELETE | `/api/categories/{id}` | change; delete (refused while in use or while it has sub-categories) |
| GET, POST | `/api/tags` | list; add `{ name, color }` |
| PUT, DELETE | `/api/tags/{id}` | change; delete (refused while on an item) |
| GET, POST | `/api/items` | recurring items; add `{ name, kind, amountCents, payFromAccountId, schedule, payToAccountId?, amountMode?, estimate?, categoryId?, autopay?, placement?, daysAfterPayday?, tagIds?, note?, bucketId?, saveUpBucketId?, fromBucketId?, cardPaymentMode? }`. `kind` is `bill`, `transfer`, `budget`, `card_payment` or `bucket_move`; a card payment's `cardPaymentMode` is `fixed` (the amount sent) or `statement_balance`, `full_balance` or `minimum` (it follows the card, and the card needs its statement and due days first); `bucketId` is where a bucket move or transfer lands, `saveUpBucketId` the bucket a bill or budget is saved up for in |
| GET, PUT, DELETE | `/api/items/{id}` | one item; change from `effectiveFrom` on (default today; earlier dates keep what they had, except that a change of `kind` also moves earlier versions to the new accounts and buckets, keeping their dates and amounts); delete |
| | | buckets also take `stopAtGoal` (stop recurring bucket moves once the goal is held; a move is cut down to what still fits, and left out of the pay period once nothing does) |
| | | a bill also takes `billedLater` (its schedule names visits that build up, moving no money, until an invoice is entered for them; an amount per visit, not a yearly total). Each item comes back with `billedLater` |
| GET | `/api/goal-pace?goalCents=&goalDate=&bucketId=&heldCents=` | what a goal would take before it is saved: projected amount, shortfall, paydays left, and what to add every paycheck or month. `bucketId` counts what is already scheduled into that bucket; `heldCents` is what it holds (or will start with) |
| POST | `/api/items/{id}/replacement` | archive that item and add the body (same shape as a new item) as a new one starting today; for changing what kind of item something is without touching its past |
| PUT | `/api/items/{id}/occurrences/{scheduledDate}` | "just this one": any of `{ amountCents, dueDate, periodStart, paid, paidDate, confirmationCode, note, skipped, checkNumber }` (a check number only for something paid from a checking account) |
| GET, POST | `/api/items/{id}/invoices` | billed later: `{ notBilled, notBilledCents, invoices }`, the visits up to today no invoice covers and every invoice, newest first, each `{ invoice, periodStart }`; POST adds one `{ invoiceDate, dueDate, coversThrough?, amountCents?, note? }` (left out: every visit up to the invoice date, at what they come to). 400 with no visits to bill, 409 for an item that is not billed later or a due date another of its invoices has |
| PUT, DELETE | `/api/items/{id}/invoices/{invoiceId}` | change one `{ amountCents?, invoiceDate?, dueDate?, paid?, paidDate?, confirmationCode?, checkNumber?, note? }`; remove the newest (409 for an older one), leaving its visits not billed again. An invoice is listed among a period's `occurrences` with its `invoiceId` and `coversThrough`, and its receipts are an occurrence's, by its due date |
| GET, POST | `/api/entries` | one-off money in, money out and transfers; optional `from`, `to`; add `{ kind, date, amountCents, description, accountId, toAccountId?, estimate?, payee?, categoryId?, tagIds?, note?, bucketSplits?, checkNumber? }` (`checkNumber` is kept only for money leaving a checking account); `bucketSplits` is `[{ bucketId, amountCents }]`, naming a bucket of the receiving account to set part of money in or a transfer aside there, or a bucket of the paying account to pay money out or a transfer from it (refused beyond what the bucket holds that day) |
| | | entries also take `reimbursementStatus?` (money out: `none`, `to_submit`, `submitted` or `reimbursed`) and `reimbursesEntryId?` (money in: the expense it pays back). Each comes back with `fileCount` and, for money out, `reimbursement` `{ status, submittedOn, to, paidBackCents, reimbursedCents, owedCents }` |
| GET, PUT, DELETE | `/api/entries/{id}` | one entry with the money in that paid it back (`paybacks`) and the expense it pays back (`reimburses`); change; delete (its paybacks stay, as ordinary money in) |
| PUT | `/api/entries/{id}/reimbursement` | where an expense's reimbursement stands `{ status, submittedOn?, to? }`; money out only |
| GET | `/api/expense-years` | the years with one-off money out, for the Expenses year filter |
| GET | `/api/payees` | `{ payees, reimbursers }`: the names used so far, for the pickers |
| GET, POST | `/api/entries/{id}/attachments` | the receipts and documents on an entry `{ attachments, maxBytes, maxFiles }`; POST adds files as `multipart/form-data` (field `file`, one or more): PDF, JPEG or PNG by content, all or nothing. 413 too large, 415 not one of those, 422 cannot be read, 409 more than 20 |
| GET, POST | `/api/items/{id}/occurrences/{scheduledDate}/attachments` | the same for one time a recurring item was paid |
| GET, POST | `/api/cards/{id}/statements/{statementId}/attachments` | the same for a card statement |
| GET | `/api/attachments/{id}/file` | the file as it was added, as a download |
| POST | `/api/attachments/{id}/copy` | "Save a copy": from this PC, puts the file in the Downloads folder and answers `{ saved: { fileName, folder } }` (never replacing a file: `name (2).pdf`); from anywhere else `{ saved: null }`, and the screen downloads `/file` |
| GET | `/api/attachments/{id}/preview` | a picture of it: the image itself, or `?page=` of a PDF as PNG. 410 when the file is no longer on disk |
| DELETE | `/api/attachments/{id}` | take it off; Undo puts it back |
| GET | `/api/attachment-counts?from=&to=` | how many files each recurring item's time in those dates has |
| GET, POST | `/api/reports` | saved reports; save one `{ name, definition }` (up to 100; a name already used is 409) |
| PUT, DELETE | `/api/reports/{id}` | rename or change; delete |
| POST | `/api/reports/run` | work a report out without saving it `{ definition }`: `{ from, to, groups, rows, count, totalCents, reimbursedCents, waitingCents, notSubmittedCents, estimatedCents, hasReimbursements }`. `definition` is `{ range, from?, to?, include, groupBy, show, tagIds, categoryIds, accountIds, payees, reimbursement }` (`spec.md` §4.11). With `kind: "income"` the definition is `{ kind, range, from?, to?, show, incomeInclude, incomeGroupBy, sourceIds, accountIds }` and the answer is `{ kind: "income", from, to, groupBy, groups, rows, count, grossCents, taxesCents, deductionsCents, takeHomeCents, oneOffsCents, interestCents, matchCents, receivedCents, estimatedCents, paychecksWithoutGross }`; a spending answer carries `kind: "spending"`. With `kind: "projected_vs_actual"` the definition is `{ kind, range, from?, to?, compareInclude, compareGroupBy, changedOnly, tagIds, categoryIds, accountIds }` (`compareInclude`: any of `bills`, `budgets`, `paychecks`, at least one; `compareGroupBy`: `month`, `year`, `category` or `item`; `changedOnly`: only amounts that were typed over) and the answer is `{ kind: "projected_vs_actual", from, to, groupBy, spending, paychecks, rows }`, where `spending` and `paychecks` are each `{ groups, count, changed, projectedCents, actualCents, differenceCents }` and are never added together. Running a report records nothing for Undo |
| POST | `/api/reports/export/csv`, `/api/reports/export/pdf` | the report as a file `{ name, definition, withReceipts?, save? }`; the PDF can end with every receipt's pages (at most 300, else 413). With `save: true` from this PC the file is put in the Downloads folder and the answer is `{ saved: { fileName, folder } }`; otherwise the answer is the file itself. A line not paid yet says so in words ("Not paid yet"); only an estimated amount is shaded. An income report exports the same way, with gross, taxes and other deductions beside each paycheck and no receipts; so does a projected vs. actual one, with what was projected, what it was and the difference on each line |
| GET | `/api/dashboard?range=` | the Dashboard's Overview in one answer. `range` is the dates of the spending card: `period` (this pay period), `three_months` (the default) or `year`; anything else is 400. `{ today, range, netWorth: { cents, changeCents, since }, lowestAhead: { cents, periodStart, accountId, accountName, low } or null, saved: { totalCents, transfersCents, contributionsCents, matchCents }, debt: { totalCents, cardsCents, loansCents }, spending: { from, to, totalCents, categories }, netWorthOverTime: [{ date, netWorthCents, projected }], attention }`. Each `attention` line has a `kind` (`past_due`, `low_balance`, `promo_ending`, `due_soon`, `stub_review`, `goal_short`, `price_up`, `balance_stale`, `plan_reminder`, `plan_active`), in that order, with the ids the screen links it by. `plan_reminder` is there from the plan reminder day until next year has a plan; `plan_active` for 14 days after a plan takes effect |
| GET | `/api/annual-costs?basis=` | every bill and budget over three years: `basis` is `calendar` (the default) or `rolling` (twelve months ending last month). `{ basis, windows: [{ from, to, label }], rows: [{ itemId, name, kind, categoryId, categoryName, color, totalsCents, changeBp, perMonthCents }], totalsCents, changeBp, perMonthCents, biggestIncrease, budgetedFrom }`. Archived items are left out |
| GET | `/api/items/{id}/by-month` | one bill's or budget's year, month by month, beside the year before: `{ year, months: [{ month, amountCents, beforeCents, toCome }], highest, lowest, totalCents, averageCents, budgetCents, budgetTooLow }`. 400 for a transfer or a card payment |
| POST | `/api/annual-costs/export/csv` | the annual costs table as a file `{ basis, save? }`, saved or sent like a report's ("Annual costs.csv"). Records nothing for Undo |
| GET | `/api/plans` | yearly plans, the latest first: `{ plans: [{ id, year, status, basis, startsOn, endsOn, setup, suggestedAsOf, activatedOn }], nextYear, nextStartsOn, canPlan, canSetUp, reminderDue }`. `status` is `draft`, `active` or `closed`; `canSetUp` is true only while nothing is set up yet |
| POST | `/api/plans` | start a plan `{ kind }`: `next_year` (a draft of the coming budget year; needs an income source) or `setup` (first-time setup, the year under way). 201 `{ id }`; 409 while there is already a draft, or a plan of that year |
| GET | `/api/plans/{id}` | one plan: `{ plan, historyFrom, historyTo, stale, paydays, steps: [{ step, rows, last12Cents, trendBp, suggestedCents, chosenCents, currentCents, yours, changes }], incomeCents, outCents }`. Steps are `income`, `bills`, `annual`, `budgets` and `saving`; each row has what the last 12 months came to, the trend in basis points, the suggestion and why (`history`, `fixed`, `not_enough_history`), the plan's amount and whether it was typed (`yours`). An item's amounts are totals for the year; an income source's are take-home pay for one paycheck |
| PUT | `/api/plans/{id}` | change what a draft's suggestions are worked out from `{ basis, refresh? }`: `last_12`, `last_12_trend` or `prior_year`; `refresh: true` also moves them on to the latest full months. 409 for a plan that is not a draft |
| PUT | `/api/plans/{id}/amounts` | type one amount into a draft `{ kind: "item" \| "income", id, amountCents }`; `amountCents: null` goes back to the suggestion. 409 for first-time setup, or a plan that is not a draft |
| POST | `/api/plans/{id}/accept-suggestions` | drop every typed amount of a draft, or of one step `{ step? }` |
| POST | `/api/plans/{id}/finish` | finish first-time setup: the plan becomes active at once. 409 for any other plan |
| DELETE | `/api/plans/{id}` | delete a draft before it starts; body `{ "confirm": "delete-draft" }`. 409 for an active or closed plan |
| GET | `/api/items/{id}/plan-suggestion` | what next year's plan would suggest for one bill or budget: `{ suggestion: { year, basis, perYearCents, perMonthCents, reason, trendBp } }`, or `{ suggestion: null }` when there is nothing to suggest |
| POST | `/api/planner` | Debt & decisions: where an extra amount each month could go `{ extraCents, years, order?, growthBp? }`. `years` is 1, 3, 5, 10, 20 or 30; `order` is `highest_rate` (the default) or `smallest_balance`; `growthBp` assumes a yearly rate for an investment or savings account in place of its own, by account id (7% is 700; 0 to 10000) and is not saved. Answers `{ today, extraCents, years, flags, options, payoff, hidden }`: an option is a debt (`interestSavedCents`, `payoffDate`, `payoffDateBefore`, `monthsSooner`, `freedCents`, or `problem: "no_payment"`) or an account to invest or save in (`valueCents`, `putInCents`, `growthCents`, `matchCents`); `payoff` is `{ order, debts, debtFreeDate, debtFreeDateBefore, interestSavedCents, owedOverTime }` (each debt with its own `interestSavedCents`; `owedOverTime` is every debt together at each month's end, `{ month, withCents, withoutCents }`, until both are gone, at most 30 years). A look-up: records nothing for Undo |
| PUT, DELETE | `/api/planner/hidden/{accountId}` | leave a loan, card, investment or savings account out of that comparison; DELETE brings it back. 400 for a checking account |
| POST | `/api/search` | find one-offs by description or payee and recurring items by name `{ text }` (2 to 100 characters): `{ recurring, oneOffs, more }`. A recurring item has `last` (up to today; `paid` when ticked or on autopay) and `next`, each `{ date, amountCents, estimate, paid, periodStart }`; a one-off has its `date`, `upcoming` and `periodStart`. At most 30 one-offs, newest first. A POST so the words are never in an address; records nothing for Undo |
| GET, POST | `/api/holidays` | bank holidays (US ones filled in locally); add your own `{ date, name }` |
| PUT, DELETE | `/api/holidays/{id}` | change or switch off `{ date, name, enabled }`; remove |
| GET | `/api/undo` | `{ available }`: whether there is a change to undo |
| POST | `/api/undo` | undo the most recent change; `{ undone, available }` |
| DELETE | `/api/data` | erase all data; this PC only; body `{ "confirm": "erase-all-data" }` |
| POST | `/api/shutdown` | open to the shell only: loopback + `X-Budget-Shutdown-Token` |

Every request that changes something is recorded so it can be undone, and its answer carries the header
`X-Budget-Changed: true` (the UI shows its Undo toast when it sees it). Such requests are handled one at a time.

Amounts are whole cents (`amountCents`, `netCents`); dates are `YYYY-MM-DD`. Item kinds are `bill`, `transfer`,
`budget`, `card_payment` and `bucket_move`; item schedules are `every_pay_period`, `weekly`, `biweekly`, `semi_monthly`, `monthly`,
`every_n_months`, `yearly` and `one_time`. A balance you enter stands for the start of that day (see `spec.md` §5.3). Settings keys: `theme`,
`accent_color`, `projection_horizon_months` (6, 12, 18, 24 or 36), `promo_warning_days` (30, 45, 60, 90 or 120: how long
before a card's promotional rate ends the warnings start), `unpaid_highlight_days` (0, 1, 2, 3, 5, 7, 10 or 14: how soon
before it is due a bill you pay by hand is pointed out; 0 is never), `auto_lock_minutes` (1, 5, 15, 30 or 60),
`start_amounts_hidden` (`true` or `false`), `backup_keep_count` (7, 14, 30, 60 or 90), `default_date` (empty for
today, or a day that is today or earlier: the date forms for new things open on), `budget_year_start` (`MM-DD`, the
day a yearly plan takes effect; `01-01` unless changed) and `plan_reminder` (`MM-DD`, the day the Dashboard starts
suggesting next year's plan; `10-01` unless changed). Neither takes Feb 29. `backup_folder` is read here
but set with `PUT /api/backups/folder`.

## CI and dependency checks

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to a branch:

- **Backend tests** on Windows, and the **frontend** typecheck, tests and build.
- **Dependency checks:** OSV-Scanner fails the build on any known vulnerability in a Maven, npm or
  Cargo dependency, and `check-dependency-age.ps1` fails it if a dependency version is less than
  14 days old. Both also run weekly.
- The **installer** is built in CI only when asked for by hand ("Run workflow" on the Actions tab;
  kept as a workflow artifact for 30 days). An hour on a Windows runner is billed double and used
  most of a month's allowance in a few builds, so the installer to try is built on the PC with
  `build-installer.ps1`. The manual job waits for the tests, then runs
  `build-installer.ps1 -SkipTests`, since both suites have just run on that commit. To save time, the Tauri command-line tool, Cargo's package downloads and the compiled
  Rust dependencies are kept between runs; the shell's own crate is deleted first, so the app
  itself is always compiled from clean.
- **Nothing runs on a push to `main`.** Work only reaches main through a pull request whose branch
  already passed the checks above.

To run the dependency age check yourself (it needs the network; the app itself never does):

```powershell
cd backend
.\mvnw.cmd -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
cd ..
.\check-dependency-age.ps1
```

When adding or upgrading a dependency, pick the newest version that is at least 14 days old;
`frontend/.npmrc` makes npm do this by itself.

A real, known vulnerability never passes. If its only fix is younger than 14 days, then in order:

1. use an older version that never had the vulnerability;
2. drop or replace the package, if nothing the app needs is lost;
3. only then take the fixed version early, as a named entry in `dependency-age-exceptions.json`
   (what it fixes, and why 1 and 2 were not possible). For npm, install it once with
   `npm install --min-release-age=0`.

An exception ends by itself: the check fails once the version is 14 days old, or no longer used,
until the entry is deleted. A finding that genuinely cannot apply (code that is not in the Windows
build) goes in `desktop/src-tauri/osv-scanner.toml` with the reason; anything temporary there
carries an `ignoreUntil` date.

## PIN and locking

A PIN is optional. Budget offers one the first time it opens, and it can be set, changed or removed under
**Settings → Security**. With a PIN set, the app asks for it when it opens, after **Lock now**, and after it has sat
idle for the time chosen there. It is an app lock, not encryption: for protection at rest, turn on Windows BitLocker.

When the PIN is first set, Budget shows a **recovery code** once. Keep it somewhere safe, away from the PC.
After two wrong tries the app makes you wait before the next, longer each time (30 seconds up to an hour).

### If you forget your PIN

1. On the lock screen choose **I forgot my PIN** and enter the recovery code. You pick a new PIN and get a new code.
2. If the code is lost as well, your data is still safe. Close Budget, then in PowerShell run the app's own
   backend with `--reset-pin` (it is in the folder Budget was installed to, by default under
   `%LOCALAPPDATA%\Budget`):

   ```powershell
   & "$env:LOCALAPPDATA\Budget\backend\budget-backend.exe" --reset-pin
   ```

   It removes the PIN, touches nothing else, and says so. Open Budget again and set a new PIN in Settings.
   This works only on the PC itself, with the app closed; there is no way to do it over the network.

In development: `java -jar server\target\app\budget-server.jar --reset-pin` from `backend`, with the same
`BUDGET_DATA_DIR` as the app.

## Backups

While it is open, Budget makes one backup a day: a zip holding the database and the `files/` folder. Under
**Settings → Backups** you choose the folder (another drive is best), how many to keep, back up now, and restore.
Until a folder is chosen they go in `backups\` inside the data folder.

- **The zips are not encrypted.** Anyone who can open the folder can read them, so choose it with that in mind.
- **Restore** replaces everything with the backup you pick. What is there now is saved first, as
  `budget-before-restore-….zip` in the data folder's `backups\`. Your current PIN and backup folder are kept.
- A backup made by a newer version of Budget is refused, with a message saying so.

## Data and privacy

- Data folder: `%APPDATA%\Budget` — `budget.db`, `logs/`, `backups/`, `files/`. Uninstalling keeps
  it unless you tick "Delete the application data".
- **Settings → Erase all data** deletes the database files outright and rebuilds an empty one, and
  deletes `files/` and `backups/`, so nothing is left behind on disk. Logs stay (they hold no
  financial data). It cannot be undone.
- Receipts and documents are copied into `files/`, named by their SHA-256 and never changed. Removing one
  from the app deletes the copy at the next start, once Undo can no longer bring it back. They are
  reached only through the app, by id, behind the session. `upload-cache/` holds a file only while it is
  being added.
- A report export or "Save a copy" of a receipt is written by the app into your Downloads folder, and the
  screen says what it is called there. Nothing already in the folder is replaced.
- The session token lives in an HttpOnly, SameSite=Strict cookie and in memory only.
- A strict Content-Security-Policy blocks every external origin; requests with an unrecognised
  `Host` header are refused (protection against DNS rebinding).
- The PIN is kept only as an Argon2id hash, and the recovery code likewise; neither is written to a log or to a backup's name.
- For protection at rest, turn on Windows BitLocker.
