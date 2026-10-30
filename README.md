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

## Help

The app has its own user guide: **Help** at the foot of the sidebar (in More on a phone), a **?** beside every screen's title for that screen's chapter, and **ⓘ** beside words that need explaining. Its text lives in `frontend/src/help/` (`guide.ts`, `terms.ts`); when a button or card is renamed, rename it there too.

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
   While phone access is on, closing hides the window to the tray instead, and **Quit Budget** in the
   tray stops both. The backend tells the shell when that applies, and whether to keep a Windows startup
   entry, with `SHELL keep_in_tray=… start_with_windows=…` lines on the same stdout after READY.
5. On a computer set to "Connect to my other PC", the shell starts no backend: the window opens the other
   PC's phone access address, or a "Can't reach your PC" page with Try again.

### Trying phone access in development

Phone access listens on a real network address of this PC, so Windows may ask whether to let Java through its
firewall. For the tests and the dev server that isn't needed (a request from this PC to itself doesn't pass
the firewall): untick both boxes and press Cancel.

## API

Every response is `{ data, error, status }`. Every route needs a session cookie except those marked open.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | open |
| POST | `/api/session` | open; unlock with `{ pin }`. With no PIN set, an empty body unlocks from this PC only. A wrong PIN is 401; too many are 429 with how long to wait |
| GET | `/api/session` | open; `{ unlocked, pinSet, retryAfterSeconds, offerPin, fromThisPc }` (`fromThisPc` is false on a phone or another computer) |
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
| GET | `/api/accounts/{id}/transactions` | what moved the account, newest first: paychecks, recurring items, one-off entries, transfers arriving, estimated interest and balances entered, each `{ date, label, kind, amountCents, unpaid, estimatedCents, source }` (`estimatedCents` is set on interest that was typed over: what Budget had estimated; `source` is what it came from, to open it: `{ type: entry | occurrence | paycheck, entryId, itemId, scheduledDate, dueDate, periodStart }`, `null` for what Budget works out and for balances); optional `from`, `to` (default: this year so far through the next month) |
| PUT, DELETE | `/api/accounts/{id}/transactions/interest/{date}` | type over one interest line `{ amountCents }` (never negative; 0 allowed): a month of interest on savings or checking, a card's interest at a statement close, a loan's month, or an investment's growth for the month `date` falls in. It becomes that line's amount; later months are still worked out from the rate. DELETE puts Budget's estimate back. 404 when there is no interest line on that day (no rate, or not a day interest is added) or, for DELETE, nothing was typed over. Undo covers both |
| PUT, DELETE | `/api/accounts/{id}/transactions/rewards/{date}` | type over a card's rewards at one statement close `{ amountCents }` (what came off; never negative), or with DELETE put the estimate back. 409 before the close has come; 404 when there is no rewards line that day |
| PUT | `/api/account-balances` | enter balances for several accounts at the end of one day `{ date, balances: [{ accountId, amountCents }] }`, as one change that Undo takes back together; `{ saved }` |
| GET | `/api/accounts/{id}/performance` | `thisYear` `{ since, startCents, nowCents, changeCents, depositsCents, growthCents }` and, for each balance entered, its change since the one before, what was put in between them and the rest (growth). For an investment with two or more balances entered, `actualGrowth` `{ from, to, growthCents, assumedCents, yearlyRateBp }`: what it really grew by from the first balance to the latest, what its assumed growth added over the same days, and that growth as a rate a year (`null` under 90 days apart) |
| GET, POST | `/api/assets` | a home, vehicle or anything else owned, with today's value, what is owed on its linked loan and equity; add `{ name, kind, color, appreciationBp?, linkedLoanAccountId?, valueCents?, valueDate? }`. `kind` is `home`, `vehicle` or `other`; `appreciationBp` is an assumed annual change in its value: above zero it gains (up to 100000), below zero it loses (down to -9900, a loss of 99% a year) |
| GET, PUT, DELETE | `/api/assets/{id}` | one asset with every value entered; change or archive; delete |
| POST | `/api/assets/{id}/values` | enter a value `{ date, valueCents }`; the same day again replaces it |
| DELETE | `/api/assets/{id}/values/{valueId}` | remove one value |
| GET | `/api/net-worth` | today: `{ date, netWorthCents, assetsCents, liabilitiesCents, cashCents, investmentsCents, propertyCents, cardsCents, loansCents }`, every part a positive amount |
| GET | `/api/accounts/{id}/projection` | the balance, and what is available after buckets, at each pay period's end; optional `from`, `to` (any dates up to ten years apart, between 2000 and 2100: the past as worked out, or past the look-ahead) |
| GET | `/api/accounts/{id}/spending` | for a checking or savings account, each pay period's `start`, `end`, `outCents` (everything that left it) and `categories` (`categoryId`, `null` for none, and `cents`, biggest first); for a credit card, everything charged to it; empty for a loan or investment; `from`, `to` as for the projection |
| PUT | `/api/accounts/{id}/interest` | when interest is credited and where it goes `{ creditDay, destination, destinationBucketId? }`; `creditDay` 31 means the last day; `destination` is `unassigned`, `bucket` or `proportional` |
| POST | `/api/accounts/{id}/rates` | a rate from a date on `{ rateBp, effectiveDate }` (4.10% is 410); the same date again replaces it |
| DELETE | `/api/accounts/{id}/rates/{rateId}` | remove one rate |
| GET | `/api/cards` | every credit card with what is owed today, its settings, regular APR, next payment and the promotion that ends soonest with a balance on it |
| GET | `/api/cards/{id}` | one card: the above plus every promotion (remaining balance, days left, warning, payoff per paycheck), its statements (the next one projected, then those entered; an entered one has `recordsCents`, what the card's records came to at its close, and when the two differ `reconcileFrom`, the day after the last statement that matched or the last balance entered), APR history and the next close and due dates |
| PUT | `/api/cards/{id}/minimum` | how its minimum payment is estimated until a statement with the real one is entered `{ shareBp, floorCents, plusInterest }`: a share of the statement balance (1% is 100), never less than the floor, with or without that month's interest. Needs the card's statement and due days first (409) |
| PUT | `/api/cards/{id}/rewards` | the card's cash back on what is charged `{ rateBp }` (1.5% is 150; 1 to 2000; null takes rewards away). At each projected statement close the rate × what was charged since the last close comes off the balance as a `card_rewards` line. Returns the card. Needs the card's dates first (409). Also `buildUp`, `yearlyMonth` (1–12), `thresholdCents`, `startCents` and `startDate` (needed with `startCents`, up to today): rewards then build up and go on as `card_rewards_redeemed` credits by the rule or a redemption. The card's `rewards` returns them with `builtUpCents` today, `nextCreditDate`/`nextCreditCents` and `redemptions` |
| POST | `/api/cards/{id}/rewards/redemptions` | redeem built-up rewards as a statement credit `{ date, amountCents (null for everything built up by then), note }`; 201 `{ id, card }`. 409 when the card's rewards come off each statement |
| PUT, DELETE | `/api/cards/{id}/rewards/redemptions/{redemptionId}` | change or delete a redemption; returns the card |
| PUT | `/api/cards/{id}/settings` | how it bills `{ statementCloseDay, dueDay, monthlyEstimateCents? }`. How it is paid is not set here: the card's payment is a recurring item (`/api/items`, kind `card_payment`, with `cardPaymentMode`) |
| POST | `/api/cards/{id}/statements` | a statement as it arrived `{ closeDate, balanceCents, minimumDueCents?, dueDate? }`; the same closing date again replaces it |
| PUT | `/api/cards/{id}/statements/{statementId}` | change a statement in place, closing date included `{ closeDate, balanceCents, minimumDueCents?, dueDate? }`; 409 for a closing date another statement has |
| DELETE | `/api/cards/{id}/statements/{statementId}` | remove one statement |
| POST | `/api/cards/{id}/promos` | a promotional rate `{ label, kind, rateBp, startDate, endDate, originalBalanceCents?, rateAfterBp?, deferredInterest }`; `kind` is `balance_transfer`, `purchase` or `other`. `originalBalanceCents` is the amount the rate is on; left out, it is on everything the card owes, new charges included (deferred interest needs an amount) |
| PUT, DELETE | `/api/cards/{id}/promos/{promoId}` | change or remove one promotion |
| POST | `/api/cards/{id}/promos/{promoId}/payoff` | the payoff helper: adds a card payment every pay period until the promotion ends, sized to clear it |
| GET, PUT | `/api/accounts/{id}/loan` | a loan's terms and what they work out to (next payment shared into interest, principal and escrow; payoff date; interest still to pay; year by year); set `{ paymentCents, paymentDay?, escrowInsuranceCents?, escrowTaxesCents?, originalPrincipalCents?, termMonths?, startDate? }`. The rate is one of the account's rates. `paymentItemId?` links the recurring item that pays it (a bill, or a transfer into the loan); `paidBy` lists what pays it |
| POST | `/api/accounts/{id}/loan/payment` | add the loan's payment as a recurring transfer into it `{ payFromAccountId, autopay? }`, monthly on the payment day from today; refused once something pays the loan |
| GET, POST | `/api/buckets` | buckets with today's balance; add `{ accountId, name, color, goalCents?, goalDate?, note?, startingCents?, openedOn? }` (checking and savings only); `openedOn` is the day it counts from, today or earlier. With a goal and a `goalDate`, each bucket carries `goalProgress`: `{ projectedCents, shortCents, paydaysLeft, morePerPaycheckCents, morePerMonthCents }` |
| GET, PUT, DELETE | `/api/buckets/{id}` | one bucket with the money moved by hand; change or archive; delete (refused while a recurring item uses it) |
| POST | `/api/buckets/{id}/changes` | put money in or take it out by hand `{ date, amountCents, note? }` (negative takes out) |
| PUT | `/api/buckets/{id}/changes/{changeId}` | change money put in or taken out by hand `{ date, amountCents, note? }`, or an amount set (`amountCents` is what it is set to); 404 for a move out (`movedOut: true`), which is changed under `moves-out`; 409 for a day another amount is set for |
| DELETE | `/api/buckets/{id}/changes/{changeId}` | remove one of those, a move out of the bucket or an amount set included. A bucket's `changes` each have `setToCents` (an amount set for the day, its `amountCents` 0) or `null` |
| POST | `/api/buckets/{id}/moves-out` | move money out of the bucket to money not in a bucket `{ date, amountCents, note, categoryId?, tagIds?, reimbursesEntryId? }` (`reimbursesEntryId`: an expense it pays yourself back for, counted toward its paid back; 400 for anything but an expense): the balance stays, what is available goes up, and the pay period lists it under Income (`movesOut`). 201 `{ id, bucket }`. 409 for more than the bucket holds that day |
| PUT | `/api/buckets/{id}/moves-out/{changeId}` | change one, the same way. 404 for a change that is not a move out of that bucket |
| GET | `/api/buckets/{id}/held?on=` | what it held at the end of a day `{ date, heldCents }` |
| PUT | `/api/buckets/{id}/held` | set what it held at the end of a day `{ date, amountCents }`. Kept as that amount (a change with `setToCents`), so the bucket holds it at the end of that day whatever is entered or moved before it later, and later days build on it; setting the same day again replaces it |
| POST | `/api/accounts/{id}/balances` | enter a balance `{ date, amountCents, note? }`: what the account held at the **end** of that day, so that day's paycheck and payments are already in it (`endOfDay: true`; balances entered before migration `V50` keep meaning the start of their day). The same day again replaces the amount and note. A date still to come is a planned balance: the projection takes it at the end of that day, and until then it is not the account's latest balance and settles nothing |
| DELETE | `/api/accounts/{id}/balances/{entryId}` | remove one entry |
| GET, POST | `/api/income-sources` | list; add `{ name, depositAccountId, netCents, grossCents?, netIsEstimate?, primary?, schedule, deductions?, match? }`. `match` is the employer match, paid into an investment account: `{ toAccountId, mode: "fixed", fixedCents }` or `{ toAccountId, mode: "formula", matchBp, capBpOfGross }` (50% up to 6% of gross is 5000 and 600; without `grossCents` it also needs `contributionWithinCap: true`, the user's confirmation that what they put in is within that share, and all of it is then matched). Each deduction is `{ name, toAccountId?, amountCents }` or `{ name, toAccountId?, percentBp }`, a share of gross pay (6% is 600; needs `grossCents`) that comes back with the `amountCents` it works out to. Each source comes back with `matchFigures` `{ perPaycheckCents, mostPossibleCents, contributeMoreCents }` |
| PUT, DELETE | `/api/income-sources/{id}` | change; delete (the primary one only when it is the last). The amounts sent (`grossCents`, `netCents`, `netIsEstimate`, `deductions`, `match`) correct the ones in force today, unless the body says otherwise: `effectiveFrom` (a date) makes them a change of pay from that day on — a raise — with earlier paychecks keeping theirs, and `originalAmounts: true` corrects the amounts the source started with. Each source comes back with the amounts in force today and `payHistory`, every set oldest first: `{ id, effectiveDate, grossCents, netCents, netIsEstimate, deductions, match, matchFigures, current }` (`id` and `effectiveDate` are null for the amounts it started with) |
| PUT | `/api/income-sources/{id}/primary` | make this the income whose paydays set the pay periods |
| DELETE | `/api/income-sources/{id}/pay-changes/{changeId}` | remove one change of pay, so the amounts before it carry on; returns the source |
| GET | `/api/income-sources/{id}/this-year` | what the source brings home this calendar year: `{ year, soFarCents, paychecksSoFar, grossSoFarCents, grossPaychecksSoFar, grossWholeYearCents, grossPaychecksInYear, wholeYearCents, paychecksInYear, estimate }` — paychecks that have arrived (today's included) and every paycheck of the year, each counted in the year it arrives |
| PUT | `/api/income-sources/{id}/paychecks/{scheduledDate}` | type over one paycheck `{ netCents }` |
| GET, POST | `/api/income-sources/{id}/one-off-paychecks` | the source's one-off paychecks (a bonus), newest first `{ id, incomeSourceId, payDate, name, grossCents, netCents, deductions, matched, matchCents, payStubId }`; POST adds one `{ payDate, name, netCents, grossCents?, deductions?, matched? }`. Deductions are given as for the source; `matched` (true when left out) adds the employer match by the source's own rule on that day, and `matchCents` is what that comes to. Paid into the source's deposit account on its own day, on no schedule |
| PUT, DELETE | `/api/income-sources/{id}/one-off-paychecks/{oneOffId}` | change one (the same body); delete it. Undo covers both |
| GET, POST | `/api/income-sources/{id}/pay-stubs` | the source's pay stubs, newest first `{ id, name, payDate, status, applied, grossCents, netCents, toCheck }`; POST adds one as `multipart/form-data` (field `file`, a single PDF). It is read on this PC and waits in review, changing nothing. 415 when it is not a PDF, 422 when it has no text in it (a scan) or cannot be opened |
| POST | `/api/pay-stubs/read` | read a stub without keeping it `{ payDate, lines, totals }`, to fill in an income source that does not exist yet |
| GET, PUT, DELETE | `/api/pay-stubs/{id}` | one stub: its lines (`section`, `label`, `amountCents`, `ytdCents`, `tracksAs`, `accountId`, `sure`), `totals` (with `differenceCents` and `balanced`: gross less everything taken out against the net pay), `toCheck`, the `paycheck` it is for and what saving it `wouldSet`; PUT sends a stub in review back `{ payDate, lines }` (409 once saved, until it is reopened); DELETE removes it and leaves what it changed, Undo brings it back |
| POST | `/api/pay-stubs/{id}/save` | save a stub in review `{ apply, name? }`: `correct` the amounts in force, `pay_change` from the paycheck it is for, `paycheck` for just that one (409 when no paycheck is within 4 days of its date), `one_off` as a one-off paycheck on its pay date (with an optional `name`, Bonus when left out; saving the same stub again changes that paycheck) or `none`. How each line was filed is remembered for the source |
| POST | `/api/pay-stubs/{id}/reopen` | put a saved stub back in review to be changed and saved again; what it changed stays until then |
| POST | `/api/pay-stubs/{id}/cancel-edit` | stop editing a saved stub without saving: its lines, date and what it changed go back to how it was saved; 409 for a stub that isn't a saved one being edited |
| GET | `/api/pay-stubs/{id}/file`, `/api/pay-stubs/{id}/preview?page=` | the PDF as it was added; a page of it as a picture |
| POST | `/api/pay-stubs/{id}/copy` | "Save a copy", as for an attachment |
| GET, POST | `/api/accounts/{id}/statement-uploads` | a checking, savings or card account's statement uploads, newest first; POST adds one as `multipart/form-data` (field `file`: CSV, OFX/QFX or PDF; for a CSV, field `layout` with its columns as JSON, else the layout remembered for that header). Read on this PC; a CSV or OFX file is not kept, a PDF is. 201 with the upload and its lines (category and tags filled from remembered payees, a line already in an earlier upload of the account marked `duplicateOf`). 400 for an account money isn't spent from, 409 for a CSV with no layout given or remembered, 415 for a file that isn't one of these, 422 for a scan or a file with no lines Budget can read. Migration `V49` |
| POST | `/api/accounts/{id}/statement-uploads/columns` | read a CSV's columns without keeping it `{ headers, sample, guess, known }`: `known` when the layout is remembered for that header |
| GET, DELETE | `/api/statement-uploads/{id}` | one upload: its lines (`txnDate`, `postedDate`, `description`, `amountCents` (+ spent, − back), `kind` (`charge`, `refund`, `payment`, `transfer`), `leftOut`, `sure`, `categoryId`, `tagIds`, `countsToward`, `periodStart`, `newPayee`, `oneOffEntryId`, `matched` `{ itemId, scheduledDate, entryId }` or null); DELETE removes it and what it was counting (amounts already applied stay), Undo brings it back |
| PUT | `/api/statement-uploads/{id}/lines/{lineId}` | change a line `{ categoryId?, tagIds?, kind?, leftOut?, txnDate?, description?, amountCents?, countsToward? { type: category | tag, id } }`; the category and tags are remembered for its payee and given to the payee's other lines with nothing on them yet. Returns `{ upload, alsoFiled }` |
| GET | `/api/statement-uploads/{id}/changes` | by pay period (transaction date), every statement of the account together: each category's and tag's total with its lines, its `target` and `hintItemId`, the period's budgets and recurring items paid from the account, what each chosen item becomes (`changes`), lines two items claim (`undecided`), lines no chosen item takes (`unmatched`), what its charges can be matched to (`matchable`: recurring items other than budgets and one-offs paid from the account, each with `matchedLineId`), and how much of the period the statements cover |
| PUT, DELETE | `/api/statement-uploads/{id}/lines/{lineId}/match` | say what a charge already is `{ itemId, scheduledDate }` or `{ entryId }`, in its own pay period: a recurring item's date takes the charge as its amount and is ticked paid on the charge's date; a one-off is only linked. Either way the line stops counting toward totals. 400 for a budget, money back or a date outside its period, 409 when another charge has it or a total is pointed at that item. DELETE puts the date back as it was. Migration `V51` |
| POST | `/api/statement-uploads/{id}/lines/{lineId}/one-off` | add a counted charge as a one-off on its date, with its category and tags; 201 with the upload, 409 if it is matched, repeated or already added |
| PUT | `/api/statement-uploads/{id}/targets` | choose what a total changes `{ periodStart, groupType, groupId, itemId (null for nothing), mode: replace \| add }`; remembered as a suggestion for the next statement |
| POST | `/api/statement-uploads/{id}/apply` | `{ periods }`: sets each chosen item's "just this one" amount in those periods (the old amount kept as projected; Add puts the total on the amount before any statement, never twice), in one Undo. 409 while a line two items claim is undecided |
| GET | `/api/statement-uploads/{id}/file`, `/api/statement-uploads/{id}/preview?page=` | a PDF statement as it was added; a page of it as a picture (404 for CSV and OFX) |
| GET | `/api/paychecks` | paychecks, today to the horizon; optional `from`, `to` |
| GET | `/api/pay-periods` | pay periods from the primary source, each with its projected ending balance; optional `from`, `to` |
| GET | `/api/pay-periods/{start}` | everything in the period that opens on `start`: paychecks, recurring items, one-offs, and each account's starting, in, out, ending and lowest balance (a period starts where the one before ended; balances entered during it are `adjustments` `{ entryId, date, balanceCents, differenceCents, note, since }` (`since`: the balance entered before it), summed in `adjustedCents`, so `ending = starting + in − out + adjusted`), what is in its buckets, what is available after them, projected interest, and whether it dips below its low-balance threshold; `statementsPaid` says which statement each card payment that follows its card was sized from `{ itemId, scheduledDate, closeDate, entered }`. In the current period only, `unbilled` lists each billed-later item's visits no invoice covers yet, up to today: `{ itemId, name, payFromAccountId, visits, amountCents, since, through }`. `paidAheadRunningOut` lists each paid-ahead item whose money runs out in the period (the current period also lists one that already ran out): `{ itemId, name, leftCents, coversThrough, firstUncovered, payFromAccountId }`. An occurrence with `plannedPack: true` is the next pack of something paid ahead as a pack (`coversThrough` is the last session it would cover); it is paid by recording the payment, not ticked |
| GET, POST | `/api/categories` | list with item counts and yearly totals; add `{ name, color, parentId? }` |
| PUT, DELETE | `/api/categories/{id}` | change; delete (refused while in use or while it has sub-categories) |
| GET, POST | `/api/tags` | list; add `{ name, color }` |
| PUT, DELETE | `/api/tags/{id}` | change; delete (refused while on an item) |
| GET, POST | `/api/items` | recurring items; add `{ name, kind, amountCents, payFromAccountId, schedule, payToAccountId?, amountMode?, estimate?, categoryId?, autopay?, placement?, daysAfterPayday?, tagIds?, note?, bucketId?, saveUpBucketId?, fromBucketId?, cardPaymentMode?, countsFromPaid? }`. `countsFromPaid` (every so many weeks on one day, or months or years apart): the dates after each payment count on from the day it was paid; left out on a change, it stays as it was. `fromLastYear` (a bill or budget): each date still to come, unpaid and not typed over, is estimated from what the same month came to a year before (the average of its dates then, skipped ones left out; a month not over yet looks a year further), or the item's own amount when there is nothing; left out on a change, it stays as it was. `kind` is `bill`, `transfer`, `budget`, `card_payment` or `bucket_move`; a card payment's `cardPaymentMode` is `fixed` (the amount sent) or `statement_balance`, `full_balance` or `minimum` (it follows the card, and the card needs its statement and due days first); `bucketId` is where a bucket move or transfer lands, `saveUpBucketId` the bucket a bill or budget is saved up for in |
| GET, PUT, DELETE | `/api/items/{id}` | one item; change from `effectiveFrom` on (default today; earlier dates keep what they had, except that a change of `kind` also moves earlier versions to the new accounts and buckets, keeping their dates and amounts); delete |
| | | buckets also take `stopAtGoal` (stop recurring bucket moves once the goal is held; a move is cut down to what still fits, and left out of the pay period once nothing does) |
| | | a bill also takes `billedLater` (its schedule names visits that build up, moving no money, until an invoice is entered for them; an amount per visit, not a yearly total). Each item comes back with `billedLater` |
| | | a bill billed by use takes `usesHundredths` (about how many times, in hundredths: 2.5 is 250; up to 100000) and `usesPer` (`week` or `month`); its amount is then each time's cost and its schedule must be `monthly` (the bill day; `months` are when it can happen). Each bill is an estimate of the uses since the last; the item's `perOccurrenceCents` is a whole month of them. Both `null` for any other item |
| | | a paid-ahead bill also takes `packSessions` (1–1000; the amount is then the pack's price, and the next pack is planned as a bill on the first session nothing covers); `null` is a price per session |
| | | `schedule` also takes `months` (1–12, the months a seasonal schedule runs in; empty or left out is every month; not for `one_time`, and `yearly` must include its own month) and `weekdays` (1 = Monday … 7 = Sunday, for `weekly` and `biweekly`; empty is the start date's day) |
| | | bucket moves are no longer listed by the app (a recurring transfer that names a bucket fills it); existing ones are still returned, changed and deleted here |
| GET | `/api/goal-pace?goalCents=&goalDate=&bucketId=&heldCents=` | what a goal would take before it is saved: projected amount, shortfall, paydays left, and what to add every paycheck or month. `bucketId` counts what is already scheduled into that bucket; `heldCents` is what it holds (or will start with) |
| GET | `/api/items/{id}/replaced` | `{ replaced }`: the archived item this one was added in place of when a change of type used to archive it and add a new one (same name, another kind, archived the day this one starts), or `null` |
| POST | `/api/items/{id}/merge-replaced` | makes that pair one item, as a change of type in place would; returns `{ id }` of the item left (the older one). 409 when there is no pair |
| DELETE | `/api/items/{id}/versions/{versionId}` | remove one amount from the item's history (`history[].versionId`); the one before it carries on, and the first removed, the next covers everything before. 409 for its only one |
| GET | `/api/items/{id}/upcoming` | the item's next 6 dates from today within the coming year, as pay period occurrences, skipped ones included (`skipped: true`); not visits billed later or sessions paid ahead. 404 for an unknown item |
| PUT | `/api/items/{id}/occurrences/{scheduledDate}` | "just this one": any of `{ amountCents, dueDate, periodStart, paid, paidDate, confirmationCode, note, skipped, checkNumber, payFromAccountId }` (a check number only for something paid from a checking account; `payFromAccountId` pays this one from another open account, never the one it goes to, and the item's own account puts it back; `skipped` leaves it out of its pay period, and `false` puts it back) |
| GET, POST | `/api/items/{id}/invoices` | billed later: `{ notBilled, notBilledCents, skipped, upcoming, invoices }`, the visits up to today no invoice covers (and those of them skipped), the next three to come (each `{ scheduledDate, date, amountCents, skipped }`; a visit is skipped or put back with `PUT /api/items/{id}/occurrences/{scheduledDate}` `{ skipped }`, 409 once it is on an invoice) and every invoice, newest first, each `{ invoice, periodStart }`; POST adds one `{ invoiceDate, dueDate, coversThrough?, amountCents?, note? }` (left out: every visit up to the invoice date, at what they come to). 400 with no visits to bill, 409 for an item that is not billed later or a due date another of its invoices has |
| PUT, DELETE | `/api/items/{id}/invoices/{invoiceId}` | change one `{ amountCents?, invoiceDate?, dueDate?, paid?, paidDate?, confirmationCode?, checkNumber?, note? }`; remove the newest (409 for an older one), leaving its visits not billed again. An invoice is listed among a period's `occurrences` with its `invoiceId` and `coversThrough`, and its receipts are an occurrence's, by its due date |
| GET | `/api/items/{id}/paid-ahead` | paid ahead: `{ paidCents, usedCents, leftCents, coversThrough, firstUncovered, used, upcoming, payments, packSessions, packPriceCents, nextPack }` (the last three `null` unless bought as a pack; `nextPack` is the day the next one is planned); the sessions up to today, newest first, and the next three, each `{ scheduledDate, date, amountCents, skipped, covered }` (skipped or put back with `PUT /api/items/{id}/occurrences/{scheduledDate}` `{ skipped }`), and every payment, newest first, `{ entryId, date, amountCents, accountId }`. 409 for an item that was never paid ahead |
| POST | `/api/items/{id}/paid-ahead/payments` | record a payment made ahead `{ date, amountCents, accountId?, note? }` (left out: the item's account): a one-off of money going out, named and filed as the item, changed or removed as that one-off. Returns the standing as above, 201. 400 for no date, no amount or an unknown account; 409 as above |
| PUT | `/api/items/{id}/paid-ahead/packs/{plannedDate}` | move a planned pack to another day `{ date }`, naming it by the day it was planned for (the same day puts it back). Returns the standing. 404 for a day no pack is planned on |
| DELETE | `/api/items/{id}/paid-ahead/packs/{plannedDate}` | put a moved pack back on the day it was planned for. Returns the standing |
| GET, POST | `/api/entries` | one-off money in, money out and transfers; optional `from`, `to`; add `{ kind, date, amountCents, description, accountId, toAccountId?, estimate?, payee?, categoryId?, tagIds?, note?, bucketSplits?, checkNumber? }` (`checkNumber` is kept only for money leaving a checking account); `bucketSplits` is `[{ bucketId, amountCents }]`, naming a bucket of the receiving account to set part of money in or a transfer aside there, or a bucket of the paying account to pay money out or a transfer from it (refused beyond what the bucket holds that day) |
| | | entries also take `reimbursementStatus?` (money out: `none`, `to_submit`, `submitted` or `reimbursed`) and `reimbursesEntryId?` (money in, or a transfer out of an account like an FSA: the expense it pays back). Each comes back with `fileCount` and, for money out, `reimbursement` `{ status, submittedOn, to, paidBackCents, reimbursedCents, owedCents }` |
| GET, PUT, DELETE | `/api/entries/{id}` | one entry with the money in that paid it back (`paybacks`), the moves out of a bucket that paid it back (`movesOut`), and the expense it pays back (`reimburses`); change; delete (its paybacks stay, as ordinary money in) |
| PUT | `/api/entries/{id}/reimbursement` | where an expense's reimbursement stands `{ status, submittedOn?, to?, amountCents?, intoAccountId?, expectedOn?, fromAccountId? }`; money out only. With `intoAccountId`, `fromAccountId` is the account it comes out of (left out, from outside), planned as a transfer. What comes back (no more than the expense; left out, as it was) and where and about when it is planned to come back (both left out: as they were, or the usual plan, all of it about 30 days after into its own cash account or else the first checking account) |
| GET, PUT | `/api/items/{id}/reimbursement` | a bill or budget's way back `{ settings: { reimbursedBy, daysAfter, intoAccountId, fromAccountId } \| null, payments, packs }`; PUT `{ reimbursedBy?, daysAfter (0–365), intoAccountId, fromAccountId? }` (`fromAccountId`: an account it comes out of, like an FSA, other than the one it comes back into; left out, from outside); 409 for any other kind |
| GET, PUT, DELETE | `/api/items/{id}/occurrences/{scheduledDate}/reimbursement` | one payment flagged to be paid back `{ payment, paybacks, movesOut }`; PUT flags it or changes `{ amountCents? }` (left out, the payment's amount), with the item's way back in the same body when it has none; DELETE takes the flag off; 409 for a skipped payment |
| PUT | `/api/items/{id}/occurrences/{scheduledDate}/reimbursement/status` | where a flagged payment stands `{ status (to_submit, submitted, reimbursed), submittedOn?, to? }` |
| GET | `/api/payment-reimbursements?year=` | every payment flagged in a year (this year by default), for Expenses & receipts |
| PUT, DELETE | `/api/items/{id}/paid-ahead/packs/{plannedDate}/reimbursement` | flag a pack still to buy to be paid back `{ amountCents?, reimbursedBy?, daysAfter?, intoAccountId?, fromAccountId? }` (`fromAccountId` this pack's: left out, from outside; the screens start it at the item's) (left out, its price; no more than it), with the item's way back when it has none; DELETE takes the flag off. Returns the item's reimbursement with `packs`. When the pack is bought, the flag goes onto the payment |
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
| GET | `/api/dashboard?range=` | the Dashboard's Overview in one answer. `range` is the dates of the spending card: `period` (this pay period), `three_months` (the default) or `year`; anything else is 400. `{ today, range, netWorth: { cents, changeCents, since }, lowestAhead: { cents, periodStart, accountId, accountName, low } or null, saved: { totalCents, transfersCents, contributionsCents, matchCents }, debt: { totalCents, cardsCents, loansCents }, spending: { from, to, totalCents, categories }, netWorthOverTime: [{ date, netWorthCents, projected }], attention }`. Each spending category is `{ categoryId, name, color, totalCents, subcategories, payees, tags }`, the last three lists of `{ id, name, color, totalCents }`, largest first, for drilling in: a payee is the one entered, else the item's or one-off's name, and an amount with two tags counts under each. Each `attention` line has a `kind` (`past_due`, `low_balance`, `promo_ending`, `due_soon`, `stub_review`, `goal_short`, `price_up`, `balance_stale`, `plan_reminder`, `plan_active`), in that order, with the ids the screen links it by. `plan_reminder` is there from the plan reminder day until next year has a plan; `plan_active` for 14 days after a plan takes effect |
| GET | `/api/annual-costs?basis=` | every bill and budget over three years: `basis` is `calendar` (the default) or `rolling` (twelve months ending last month). `{ basis, windows: [{ from, to, label }], rows: [{ itemId, name, kind, categoryId, categoryName, color, totalsCents, changeBp, perMonthCents }], totalsCents, changeBp, perMonthCents, biggestIncrease, budgetedFrom }`. An archived item counts until the day it was archived as of |
| GET | `/api/items/{id}/by-month` | one bill's or budget's year, month by month, beside the year before: `{ year, months: [{ month, amountCents, beforeCents, toCome }], highest, lowest, totalCents, averageCents, budgetCents, budgetTooLow }`. 400 for a transfer or a card payment |
| POST | `/api/annual-costs/export/csv` | the annual costs table as a file `{ basis, save? }`, saved or sent like a report's ("Annual costs.csv"). Records nothing for Undo |
| GET | `/api/plans` | yearly plans, the latest first: `{ plans: [{ id, year, status, basis, startsOn, endsOn, setup, suggestedAsOf, activatedOn }], nextYear, nextStartsOn, canPlan, canSetUp, reminderDue }`. `status` is `draft`, `active` or `closed`; `canSetUp` is true only while nothing is set up yet |
| POST | `/api/plans` | start a plan `{ kind }`: `next_year` (a draft of the coming budget year; needs an income source) or `setup` (first-time setup, the year under way). 201 `{ id }`; 409 while there is already a draft, or a plan of that year |
| GET | `/api/plans/{id}` | one plan: `{ plan, historyFrom, historyTo, stale, paydays, steps: [{ step, rows, last12Cents, trendBp, suggestedCents, chosenCents, currentCents, yours, changes }], incomeCents, outCents }`. Steps are `income`, `bills`, `annual`, `budgets` and `saving`; each row has what the last 12 months came to, the trend in basis points, the suggestion and why (`history`, `fixed`, `not_enough_history`), the plan's amount and whether it was typed (`yours`). An item's amounts are totals for the year; an income source's are take-home pay for one paycheck |
| PUT | `/api/plans/{id}` | change what a draft's suggestions are worked out from `{ basis, refresh? }`: `last_12`, `last_12_trend` or `prior_year`; `refresh: true` also moves them on to the latest full months. 409 for a plan that is not a draft |
| PUT | `/api/plans/{id}/income/{sourceId}/pay` | plan an income source's whole paycheck for a draft's year `{ grossCents, netCents, netIsEstimate, deductions, match, raise }`, checked as an income source's amounts are; `raise: { from, bp }` (a day after the plan's first and within its year, 1–10000 bp) or null for none. Only the plan changes; on its first day it becomes the source's pay from that day, and a raise a second pay change from its own day. Each income row of `GET /api/plans/{id}` has `pay: { grossCents, netCents, netIsEstimate, deductions, match, planned, raiseFrom, raiseBp, raisedGrossCents, raisedNetCents, raisedPaychecks }`; every row has `historyMonths`, set when a suggestion was scaled up from fewer than 12 months (`reason: "short_history"`). To go back, clear the amount below. 404 for a source not in the plan; 409 for first-time setup, or a plan that is not a draft |
| PUT | `/api/plans/{id}/amounts` | type one amount into a draft `{ kind: "item" \| "income", id, amountCents }`; `amountCents: null` goes back to the suggestion. 409 for first-time setup, or a plan that is not a draft |
| POST | `/api/plans/{id}/accept-suggestions` | drop every typed amount of a draft, or of one step `{ step? }` |
| POST | `/api/plans/{id}/finish` | finish first-time setup: the plan becomes active at once. 409 for any other plan |
| DELETE | `/api/plans/{id}` | delete a draft before it starts; body `{ "confirm": "delete-draft" }`. 409 for an active or closed plan |
| GET | `/api/items/{id}/plan-suggestion` | what next year's plan would suggest for one bill or budget: `{ suggestion: { year, basis, perYearCents, perMonthCents, reason, trendBp } }`, or `{ suggestion: null }` when there is nothing to suggest |
| POST | `/api/planner` | Debt & decisions: where an extra amount each month could go `{ extraCents, years, order?, growthBp?, plans? }`. `years` is 1, 3, 5, 10, 20 or 30; `order` is `highest_rate` (the default) or `smallest_balance`; `growthBp` assumes a yearly rate for an investment or savings account in place of its own, by account id (7% is 700; 0 to 10000) and is not saved. `plans` sets Pay off debt or invest? (`{ investAccountId?, splitBp?, growthBp?, years? (1-30), freedMoves? }`). Answers `{ today, extraCents, years, flags, options, payoff, hidden, debtOrInvest }`: an option is a debt (`interestSavedCents`, `payoffDate`, `payoffDateBefore`, `monthsSooner`, `freedCents`, or `problem: "no_payment"`) or an account to invest or save in (`valueCents`, `putInCents`, `growthCents`, `matchCents`); `payoff` is `{ order, debts, debtFreeDate, debtFreeDateBefore, interestSavedCents, owedOverTime }` (each debt with its own `interestSavedCents`; `owedOverTime` is every debt together at each month's end, `{ month, withCents, withoutCents }`, until both are gone, at most 30 years); `debtOrInvest` is the three plans (`invest_all`, `pay_off_first`, `split`, each with `betterOffCents` (invested plus debt paid down: better off than today, not net worth), `investedCents`, `paidDownCents`, `owedCents`, `interestCents`, `debtFreeDate`), `owedTodayCents`, `best`, `bestSplitBp` and every month's `points` (each plan's better-off, invested and owed figures), or a `reason` (`no_debts`, `nowhere_to_invest`). A look-up: records nothing for Undo |
| PUT, DELETE | `/api/planner/hidden/{accountId}` | leave a loan, card, investment or savings account out of that comparison; DELETE brings it back. 400 for a checking account |
| GET | `/api/retirement` | Retirement: `{ today, birthDate, spouseBirthDate, missing, paychecksAYear, payAYearCents, figures, builtInFigures, accounts, scenarios }`. A scenario worked out by the rules carries `taxes` (`strategies` best first, `chosen`, `taxShareBp`, `medicareCents`, `years`; spec §5.12), null with a flat rate. `missing` is `birth_date`, `accounts` or null; each scenario carries its `outcome` (`earliestAge`, `earliestLowAge`, `earliestHighAge`, `ageNow`, `byAge` with projected at the three growth rates and in that year's dollars, `target`), null until a birth date and a counted account are there. Amounts are today's dollars |
| PUT | `/api/retirement/birth-date` | `{ birthDate }`, 18 to 100 years ago |
| PUT | `/api/retirement/spouse-birth-date` | `{ spouseBirthDate }`, 18 to 100 years ago, or null for none; 409 before your own |
| PUT, DELETE | `/api/retirement/tax-figures` | change the tax and Medicare figures (the whole of `figures`; brackets in order, a premium for each Medicare bracket, 400 otherwise), or go back to the built-in ones |
| PUT | `/api/retirement/accounts/{id}` | count an investment or savings account `{ taxType: "traditional" \| "roth" \| "taxable" \| null }` (null stops counting it). 400 for any other type |
| POST | `/api/retirement/scenarios` | add a scenario `{ name, salaryCents, inflationBp, withdrawalBp, taxBp, healthBeforeCents, healthAfterCents, healthInflationBp, growthBp, growthLowBp, growthHighBp, raiseBp, extraMonthlyCents, extraInto, retireAge, compared, taxes, incomes, oneOffs }` (`taxes`: `{ mode, filing, medicarePeople, stateTaxBp, gainsShareBp, partDPlanCents, supplementCents, workingMagiCents, magiOverrideCents, strategy }`, flat when left out; an income takes `socialSecurity` and `countedBp`) (growth 600, 400 and 800 and raise 0 when left out; lower ≤ expected ≤ higher, 400 otherwise); at most 20, and 3 compared (409) |
| PUT, DELETE | `/api/retirement/scenarios/{id}` | change a scenario (the whole of it, as POST), or delete it with its incomes and one-offs |
| POST | `/api/search` | find one-offs by description or payee and recurring items by name `{ text }` (2 to 100 characters): `{ recurring, oneOffs, more }`. A recurring item has `last` (up to today; `paid` when ticked or on autopay) and `next`, each `{ date, amountCents, estimate, paid, periodStart }`; a one-off has its `date`, `upcoming` and `periodStart`. At most 30 one-offs, newest first. A POST so the words are never in an address; records nothing for Undo |
| GET, POST | `/api/holidays` | bank holidays (US ones filled in locally); add your own `{ date, name }` |
| PUT, DELETE | `/api/holidays/{id}` | change or switch off `{ date, name, enabled }`; remove |
| GET | `/api/undo` | `{ available }`: whether there is a change to undo |
| POST | `/api/undo` | undo the most recent change; `{ undone, available }`. From a phone or another computer, 403 when that change touched a setting only this PC may change (`start_with_windows`, `phone_access_address`) |
| DELETE | `/api/data` | erase all data; this PC only; body `{ "confirm": "erase-all-data" }` |
| GET | `/api/phone-access` | `{ status: { on, address, url, listening, problem }, fromThisPc, pinSet, networks }`. `problem` is `none`, `network_not_connected` or `port_in_use`; `networks` (`{ address, name, tunnel }`: VPNs and tunnels only, never Wi-Fi or a cable) is listed only for this PC |
| PUT | `/api/phone-access` | this PC only: `{ address }` turns phone access on there (or moves it), `{ address: null }` turns it off and signs every other device out. 409 without a PIN or when the port is taken; 400 for an address that isn't one of this PC's VPNs right now |
| POST | `/api/shutdown` | open to the shell only: loopback + `X-Budget-Shutdown-Token` |

**Archiving** (migration `V39`): an account, recurring item, bucket or asset is archived as of a day. Its `PUT` takes `archived` and `archivedOn` (today or earlier; a bucket's not before its `openedOn`; left out, the day it already had, or today); each is returned with `archivedOn` (`null` when not archived). Before that day it counts as it always did, in every pay period, annual cost and net worth; from that day on it is put away: an item has no occurrences, an account is not listed in a period starting on or after it, an asset stops counting toward net worth, and a bucket lets go of whatever it still held that day and is not listed after. 400 "Please choose today or an earlier day to archive it as of." |

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
suggesting next year's plan; `10-01` unless changed). Neither takes Feb 29. `start_with_windows` (`true` or `false`; this PC only) makes the desktop shell start Budget in the tray at sign-in. `backup_folder` is read here
but set with `PUT /api/backups/folder`, and `phone_access_address` with `PUT /api/phone-access`.

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
