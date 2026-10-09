# Budget App — Build Plan

A private, local-only desktop budgeting app for Windows, organized around **pay periods** (one view per paycheck, like a spreadsheet tab per paycheck), with optional access from an iPhone over the home network or Tailscale.

This plan is written for Claude Code. Build it **one phase at a time**, in order (§10). Finish each phase's acceptance criteria and tests before starting the next. If anything here is ambiguous or seems wrong once you're in the code, **stop and ask** rather than guessing.

Mockups for every screen are in `design/` (see §9). They show layout, hierarchy and behavior. **All numbers in them are sample data — never hard-code them.**

---

## 1. Ground rules (apply to every phase)

### Privacy — non-negotiable
- **All data stays on the user's computer.** No cloud services, no third-party APIs, no bank connections, no sync services.
- **No telemetry, analytics, crash reporting, or update checks.** Do not add any dependency that phones home.
- **No runtime internet requests.** Fonts, icons, chart libraries and all assets are bundled locally (no Google Fonts or CDN links, even though the mockups use them for preview).
- PDF reading (pay stubs, receipts) and any text recognition (OCR) run **on the PC**.
- The only network listener is the app's own local HTTP server (§3). It binds to `127.0.0.1` by default. Phone access is **off by default**.
- Strict Content Security Policy in Tauri that blocks external origins.

### Money, rates and dates
- **Store money as integer cents** (`INTEGER` in SQLite, `long` in Java, number of cents in TypeScript). $14.99 is stored as `1499`. Convert to dollars only for display. Wrap it in a small `Money` value type rather than passing raw longs around.
- **Store rates as basis points** (4.10% = `410`). Interest, growth and amortization use `BigDecimal` internally and round to cents with one documented rule (`RoundingMode.HALF_EVEN`) before storing or displaying. Never use `double` for money.
- Dates are local calendar dates stored as `YYYY-MM-DD` text.

### Every amount is the user's to change
- Any amount the app calculates or estimates (bill estimates, interest, growth, card estimates, suggestions, projected balances) can be typed over. The typed value simply **becomes the value**.
- There is **no "revert to calculated" button**. The original projected value is kept in the database and shown on hover (desktop) / long-press (phone), and in the Projected vs. Actual report.
- **Estimated amounts** are shown inside a **light blue box** (not an "est." tag). Final amounts use the same box size with no fill, so amount columns stay aligned. A small key ("$0.00 = estimate") appears where estimates are shown. An amount stops being an estimate when the user edits it, marks it paid, or unchecks "Estimate, not final yet".

### Pickers, not free text
- Anything chosen from a list (category, tag, account, bucket, payee, frequency, month, day, settings options) uses a **dropdown**, never a free-text field.
- Lists the user builds (categories, tags, payees, accounts) use a **type-to-filter picker**: typing filters existing entries; if nothing matches, the last option is **Add "…" as new**. This prevents misspellings. See `design/picker.png`.

### Colors are the user's choice
- **Categories, tags, accounts and buckets** each have a color the user picks. Clicking any color dot opens a **color picker**: a row of preset swatches, the user's recently used colors first, and a custom option that accepts any hex code.
- The app's **accent color** (buttons, highlights, current pay period) is a setting under Appearance, using the same picker.
- The mockup colors are only defaults for new installs.
- **Readability**: text drawn on a user color automatically switches between dark and light to keep at least 4.5:1 contrast. The user's color is never changed.
- Charts, pills, bucket bars and dots all read colors from the data, never from hard-coded values.

### Code quality
- Calculation logic (pay periods, recurrence, period assignment, balances, interest, growth, amortization, suggestions, planner) lives in a **separate Java module (`engine`) with no database, HTTP or UI dependencies**, covered by JUnit tests. Write the tests from §5 **before** wiring up the UI.
- Use Java records for data, `java.time.LocalDate` for dates, and constructor injection without a DI framework. Keep it plain and readable.
- Commit at the end of each phase with a clear message.

---

## 2. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Desktop shell | **Tauri 2** | Native Windows window and installer. Its own code is a small Rust file (~50–100 lines) that only starts/stops the Java backend and opens the window. No business logic in Rust. |
| Backend | **Java 21 LTS** (or newer LTS) | HTTP API, database, calculation engine, file storage. |
| Build | **Maven** (multi-module: `engine`, `server`) | Gradle is fine if preferred; pick one in Phase 0 and keep it. |
| HTTP server | **Javalin** | Lightweight, starts in about a second. Serves the API and the built React app. No Spring. |
| JSON | Jackson (with `jsr310` module for dates) | |
| Database | **SQLite** via `sqlite-jdbc` (xerial) + **JDBI 3** (or plain JDBC) | `%APPDATA%\<AppName>\budget.db`. WAL mode, foreign keys on. |
| Migrations | **Flyway** (`flyway-core`) | `src/main/resources/db/migration/V1__init.sql`, `V2__…`. Run at startup. Never edit a committed migration. |
| Money/dates | `long` cents, `BigDecimal`, `java.time` | |
| PIN hashing | Argon2 (Password4j or Bouncy Castle) | |
| PDF text | **Apache PDFBox** | Pay stubs and receipts, read by text position. |
| OCR (optional, Phase 9) | Tess4J (Tesseract), bundled and run locally | Scanned stubs only. |
| Logging | SLF4J + Logback, **local log file only** | Rotating file in the app data folder. No remote appenders. |
| Frontend | **React + TypeScript + Vite** | |
| Styling | **Tailwind CSS** (v4) | Theme tokens (colors, spacing, radius, fonts) defined once as CSS variables and exposed to Tailwind, so light/dark and the user's accent color switch by changing variables. User-chosen colors (categories, tags, accounts, buckets) come from data and are applied with inline `style`/CSS variables, never generated class names. |
| Data fetching | TanStack Query | |
| Charts | Recharts (bundled) | |
| Dates (UI) | date-fns | |
| Tests | JUnit 5 + AssertJ (engine, API); Vitest + React Testing Library (UI) | |
| Packaging | **jlink** trimmed Java runtime + **jpackage** app image, bundled inside the Tauri installer | No separate Java install needed. |

The frontend talks to the backend **only through the HTTP API** (no Tauri `invoke` commands), so the desktop window and the phone's browser run the same React app against the same API.

### The installer (what the end user experiences)
- **One `.exe` installer** (Tauri's NSIS bundle). Double-click, Next, Finish. Nothing else to install or configure.
- Installs **per user, no admin rights needed**, into the user's app folder, with Start menu and optional desktop shortcuts.
- Bundles everything: the trimmed Java runtime, the backend, the React build, fonts and icons. **No separate Java, Node or database install.**
- **WebView2** (Windows' built-in browser engine, already on Windows 10/11): use Tauri's embedded bootstrapper so it's installed automatically if missing.
- First launch creates the app data folder and database, runs migrations, and opens the setup wizard.
- Upgrades: running a newer installer over an existing install keeps all data; migrations run on next launch. A database backup is taken automatically before migrations run.
- Uninstall removes the program but **keeps the data folder** unless the user ticks "also delete my data".
- No auto-update (no network). Updating means running a newer installer.

### Development environment
- **Builds of the installer happen on Windows** (Tauri and jpackage produce Windows output only when run on Windows). Install once: JDK 21, Maven, Node LTS, Rust toolchain (only so Tauri can compile its small wrapper), and the Visual Studio C++ Build Tools that Tauri requires.
- **No Docker.** Day-to-day work runs straight on Windows: the backend jar from the Maven wrapper and the Vite dev server (`npm run dev`, which forwards `/api` to the backend), so most development happens in the browser without Tauri. See `README.md`.
- One script (`build-installer.ps1`) runs on Windows and produces the installer: tests → backend jar → jlink/jpackage app image → frontend build → Tauri bundle. Before bundling it starts the packaged backend once on a throwaway data folder and requires it to serve the UI, because the tests run on the full JDK and cannot notice a module missing from the trimmed runtime.

### Project layout
```
budget/
  spec.md  proposals.md  CLAUDE.md  design/
  backend/
    pom.xml
    engine/   ← pure calculation code + JUnit tests (no I/O)
    server/   ← Javalin routes, JDBI repositories, Flyway migrations, files, PDF reading
  frontend/   ← React + TS + Vite
  desktop/    ← Tauri project (src-tauri/), bundles backend app image + frontend build
```

---

## 3. Architecture

```
┌──────────────── Windows PC ──────────────────────┐
│  Tauri app (window only)                         │
│  ┌──────────────┐  HTTP (127.0.0.1:port)         │
│  │ WebView      │ ─────────────┐                 │
│  │ (React app)  │              ▼                 │
│  └──────────────┘   ┌──────────────────────┐     │
│        starts/stops │ Java backend process │     │
│        ───────────► │ Javalin + engine     │     │
│                     │ SQLite + Flyway      │     │
│                     │ files/ (receipts…)   │     │
│                     └──────────┬───────────┘     │
└────────────────────────────────┼─────────────────┘
                                 ▼ (only when enabled)
                  iPhone Safari over home Wi-Fi or Tailscale
```

### Backend lifecycle (solve this in Phase 0 — it's the riskiest part)
1. Tauri starts the bundled backend launcher (from the jpackage app image in Tauri's resources), passing its own process ID and the app data folder.
2. The backend runs Flyway migrations, binds Javalin to `127.0.0.1` on the configured port (default 4873; if busy, the next free port), then prints one line to stdout: `READY port=NNNN`.
3. Tauri waits for that line (with a timeout and a friendly error screen if it never comes), then points the window at `http://127.0.0.1:NNNN/`. The backend serves the React build itself, so there is one origin and no CORS.
4. On window close, Tauri asks the backend to shut down (`POST /api/shutdown`, local only), then kills the process if it hasn't exited after a few seconds.
5. The backend also watches Tauri's process ID (`ProcessHandle.of(pid).onExit()`) and exits by itself if Tauri dies, so no stray server is ever left running.
6. Only one backend may run at a time (lock file in the app data folder).
- Attachments (receipts, invoices, pay stubs) are copied into `%APPDATA%\<AppName>\files\`, named by content hash, and referenced from the database. Originals are never modified.
- **Phone access (Phase 7):** when enabled, the server also listens on the chosen interface (LAN IP or Tailscale IP). Plain HTTP on home Wi-Fi is unencrypted, so Settings shows a warning and recommends Tailscale. The PIN is always required. The phone shows "Can't reach your PC" when the app isn't running.

---

## 4. Data model

Names are suggestions; keep the concepts. Money = integer cents, rates = basis points.

### 4.1 App and security
- **settings** — key/value: theme (`light` | `dark` | `system`), density, accent color, projection horizon (default 12 months), budget year start (month + day, default Jan 1), plan-next-year reminder date (default Oct 1), auto-lock minutes, promo warning days (default 60), unpaid-bill highlight days (default 3), start with amounts hidden, backup folder and count, phone access settings, primary income source.
- **auth** — `pin_hash`, `recovery_code_hash`, failed attempts, lockout-until.
- **change_log** — before/after of every edit, powering Undo. As built: `change_groups` holds one row per request that changed something and `change_log` one row per table row it added, altered or removed (`table_name, op, before, after`, the rows as JSON). The rows are written by database triggers that the server rebuilds from the live schema every time it opens the database, so every table and column is covered without each feature having to remember; `change_recording` says which change, if any, is being recorded. The newest 100 changes are kept. Undo puts back the newest change and forgets it (there is no redo). Erasing all data is not recorded.

### 4.2 Accounts, buckets, assets
- **accounts** — `id, name, institution, type, subtype_label, color, low_balance_threshold (nullable), sort_order, archived`.
  - `type`: `checking` | `savings` | `credit_card` | `investment` | `loan`. `subtype_label` is free text chosen by the user (e.g. "401(k)", "Brokerage", "HSA", "Mortgage").
- **balance_entries** — `account_id, date, amount, note`. A user-entered **actual** balance. Projections roll forward from the latest entry on or before a date. (Not reconciliation — just "the real number is X".) The balance stands for the **start** of its day: whatever is dated that day or later is still to come (§5.3).
- **rate_history** — `account_id, kind (apy | growth | apr | loan_rate), rate_bp, effective_date`. A change applies from its date forward.
- **buckets** — `id, account_id, name, color, goal_amount (nullable), goal_date (nullable), note, sort_order, archived, opened_on`. With a goal and a goal date, the bucket reports what it is projected to hold on that date from what is already scheduled, how far short that is, and what closing the gap takes per paycheck (shortfall ÷ paydays left, rounded up to the cent) or per month; one click adds that as an every-paycheck bucket move ending on the goal date. **Checking and savings only.** Buckets don't change the account balance; they reduce **available**. Schedules, save-ups and interest reach a bucket only from `opened_on`, shown as **Counts from**: a new bucket is never back-filled from older dates unless the user says so. It defaults to the current pay period's payday, so the paycheck in hand is included, and can be set to any day up to today. An archived bucket holds nothing.
- **bucket_changes** — `bucket_id, date, amount (+/−), source (manual | transfer | bucket_move | interest | income_split), note`. As built, only what the user moves by hand is stored (`manual`); recurring bucket moves, transfers into a bucket, splits, save-ups and interest shares are worked out on every request, like occurrences, so they always follow the current schedules.
- **interest_settings** — `account_id, credit_day (1–31; 31 means the last day), destination (unassigned | bucket | proportional), destination_bucket_id`. No row means the defaults: last day, unassigned.
- **assets** — `id, name, kind (home | vehicle | other), color, appreciation_bp (nullable), linked_loan_account_id`.
- **asset_values** — `asset_id, date, value`.

### 4.3 Income and pay stubs
- **income_sources** — `id, name, deposit_account_id, gross_amount (nullable), net_amount, net_is_estimate, schedule_rule_id, is_primary`. Multiple sources allowed; the **primary** source's schedule defines pay periods. The first source is always primary, and there is exactly one while any source exists.
- **paycheck_overrides** — `income_source_id, scheduled_date, net_amount, projected_amount`. One paycheck's amount typed over ("just this one"), keyed by the date the schedule names (which stays put when a holiday moves the payday). `projected_amount` keeps what was shown before the first edit.
- **paycheck_deductions** — `income_source_id, name, amount, to_account_id` (401(k), HSA…). These never pass through checking.
- **employer_match** — `income_source_id, to_account_id, mode (fixed | formula), fixed_amount, match_bp, cap_bp_of_gross`.
- **pay_stubs** — `id, income_source_id, pay_date, file_id, status (review | saved)`.
- **pay_stub_lines** — `pay_stub_id, section (earnings | pre_tax | tax | net | employer), label, amount, ytd_amount, maps_to (gross | contribution | benefit | tax | net | match), maps_to_account_id, confidence`.
- **stub_layouts** — per employer: remembered positions/labels of each line so later stubs fill automatically.

### 4.4 Schedules
- **schedule_rules** — `id, frequency, interval, day_1, day_2, month, start_date, end_date, weekend_rule`.
  - `frequency`: `every_pay_period` | `weekly` | `biweekly` | `semi_monthly` | `monthly` | `every_n_months` | `yearly` | `one_time` | `manual_dates`.
  - `monthly` accepts a day or `last`; days that don't exist in a month fall to its last day.
  - `weekend_rule`: `none` | `previous_business_day` | `next_business_day`.
- **manual_dates**, **holidays**. US bank holidays are worked out locally (no download) and added a year at a time; each can be switched off, changed or removed, and the user can add their own. `holiday_years_seeded` records which years were added, so a removed holiday never comes back.

### 4.5 Categories and tags
- **categories** — `id, name, color, parent_id (nullable), sort_order`. One per item. Includes income categories (e.g. Income › Bonus).
- **tags** — `id, name, color`. Many per item, via **item_tags** / **entry_tags** link tables.

### 4.6 Recurring items
- **items** — `id, name, kind, amount, amount_mode (per_occurrence | annual), is_estimate, category_id, pay_from_account_id, pay_to_account_id, bucket_id, autopay, placement, schedule_rule_id, save_up_bucket_id (nullable), note, archived`.
  - `kind`: `bill` | `transfer` | `budget` | `bucket_move` | `card_payment`.
  - `pay_from_account_id` can be **any** account, including a credit card (the charge then lands on the card).
  - `bucket_move` moves money from available into a bucket inside the same account (e.g. $50 every paycheck to Holiday gifts).
  - `amount_mode = annual`: the user enters a yearly total. With `save_up_bucket_id` set, the app creates a matching per-paycheck bucket move so the money is there by the due date. As built: any bill or budget can be saved up for, not only yearly totals; each occurrence is shared evenly over the paydays since the one before it (or since saving began), odd cents on the earliest, and leaves the bucket on the day the bill counts. Saving set up part-way through a pay period starts with that period's payday. A skipped occurrence is not saved for.
  - `bucket_id` and `save_up_bucket_id` live on the item's versions, like its other terms, so a change applies from a date on. A transfer can name a bucket in the account it pays into.
  - `placement`: `due_period` | `one_early` (§5.2).
  - `days_after_payday` (0–60, default 0): for an `every_pay_period` schedule, how many days into each period the item is dated. This is how **each budget carries its own date**.
- **item_versions** — "this and all future" edits start a new version effective from a date. As built, `items` holds only what never changes (name, kind, note, archived) and every other column above lives on the version, each with its own `schedule_rule_id`; the earliest version also covers every date before it.
- **occurrence_overrides** — `item_id, original_due_date, amount, due_date, period_override, paid, paid_date, confirmation_code, note, skipped, projected_amount`. "Just this one" edits live here. Occurrences are computed on the fly; only changes are stored.

### 4.7 One-off entries (money in and out)
- **entries** — `id, kind (money_out | money_in | transfer), date, amount, is_estimate, description, payee, category_id, account_id, to_account_id (transfers), note, reimburses_entry_id (nullable)`.
  - Used for one-off expenses (vet emergency, gift, donation), one-off income (bonus, refund, gift, side cash), and one-off transfers.
  - **entry_bucket_splits** — `entry_id, bucket_id, amount` (e.g. put $500 of a bonus into a bucket).
  - **Reimbursement** — `reimbursement_status (none | to_submit | submitted | reimbursed)` on money-out entries; a money-in entry can link to the expense it pays back.
- **card_charges** are entries whose account is a credit card.

### 4.8 Attachments
- **files** — `id, hash, original_name, mime, size, created_at`.
- **attachments** — `file_id, owner_type (entry | occurrence | pay_stub | statement), owner_id`.

### 4.9 Credit cards and loans
- **card_settings** — `account_id, statement_close_day, due_day, payment_mode (full_balance | statement_balance | minimum | fixed), fixed_payment_amount, autopay, pay_from_account_id`.
- **card_statements** — `account_id, close_date, statement_balance, minimum_due, due_date`.
- **card_promos** — `account_id, label, kind (balance_transfer | purchase | other), rate_bp, start_date, end_date, original_balance, rate_after_bp (nullable → regular APR), deferred_interest`. Several per card allowed.
- **loan_settings** — `account_id, original_principal, rate_bp, term_months, start_date, payment_amount, escrow_insurance, escrow_taxes`.

### 4.10 Yearly plans
- **plan_years** — `id, year, status (draft | active | closed), basis (last_12 | last_12_trend | prior_year), created_at`.
- **plan_year_amounts** — `plan_year_id, item_id (or income_source_id), suggested_amount, chosen_amount, chosen_by (suggestion | user)`.
- A **draft** plan can be deleted and redone any time **before its start date**. When the budget year starts, the draft becomes active and its amounts become new item versions effective that day. History is never rewritten.

### 4.11 Reports
- **saved_reports** — `id, name, definition_json` (date range, filters by tag/category/account/payee/kind, group by, chart type).

---

## 5. Calculation engine (`engine` module, pure Java, JUnit-tested)

### 5.1 Pay periods
- Periods come from the **primary** income source. A period runs from one payday up to the day before the next.
- Generated through the projection horizon. Weekend/holiday rules move paydays and therefore boundaries.
- **Tests:** semi-monthly 1st & 15th; semi-monthly 15th & last (Feb leap/non-leap); biweekly across a year boundary; weekly; monthly; manual dates; payday Sunday Nov 1, 2026 with `previous_business_day` → Fri Oct 30; with a user holiday on that Friday → Thu Oct 29.

### 5.2 Which period an item lands in
For an occurrence due on **D**, with **P** = the period containing D:
- It lands in **P − 1** if `placement = one_early`, **or** if D is a payday (first day of P) **and** the item is **not** autopay.
- Otherwise it lands in **P**.
- A user can move a single occurrence to another period (stored as an override).
- **Tests:** rent due Oct 1 (payday), manual → Sep 15–30 period; same rent on autopay → Oct 1–14; electric due Oct 16 `one_early` → Oct 1–14; `due_period` → Oct 15–31; an item that is both `one_early` and due on payday moves back **one** period, not two.

### 5.3 Balance projection (per account)
- Start from the latest `balance_entry` on or before the period start.
- Apply in date order: paychecks (net), one-off money in/out, bills, transfers, card payments, interest, growth, charges.
- **Each budget line has its own date** (decided 2026-10-06; there is no global budget-timing setting): the period's first day by default, changeable for one occurrence (`occurrence_overrides.due_date`) or for this and all future (`days_after_payday`).
- An occurrence always counts on a day **inside the period that pays for it** (its due date, brought inside that period: "due Oct 16, pay by Oct 14"), so a period's figures add up to what it lists.
- **Entered balances and unpaid items.** A balance entered for a day is what the account held at the start of that day. Anything dated earlier is taken as already in it — except an item the user pays by hand that is **not marked paid** and either is not due yet or belongs to the pay period the balance was entered in. Those still come out, on the day of the balance, and the period names them as carried over. Autopay items, paychecks and one-offs are assumed to happen on their date. Older unticked items are taken as paid, so nobody has to tick off months of history.
- For a credit card or loan the balance is what is owed: charges raise it, payments lower it.
- Per period and account: starting balance, total in, total out, projected ending, **lowest point**, **available after buckets**.
- Low-balance flag only if the user set a threshold for that account. A period is flagged when its lowest point (its starting balance included) is under the threshold.

### 5.4 Savings interest
- On the account's credit day: estimated interest = balance × ((1 + APY)^(1/12) − 1), credited to the chosen destination, shown as an estimate.
- A user-entered balance replaces the projection from that date.
- As built: the balance is the one at the start of the credit day; the rate is the one in force that day; an account earns interest once it has an APY in its rate history, from its first entered balance on. Interest counts as money in. `proportional` gives each bucket its share of the account (bucket ÷ the larger of the balance and all buckets, HALF_EVEN); the rest stays unassigned. The estimate cannot be typed over — entering the real balance is how it is corrected.

### 5.5 Investments
- Contributions: paycheck deductions, employer match, transfers.
- Growth: monthly from the user's assumed rate, always labeled as an assumption.
- **Gain/loss uses only user-entered balances**: ending − starting − net contributions. A projection being off is never a loss.
- **Match formula tests:** gross $3,600, employee $150 (4.17%), match 50% up to 6% → $75. Employee $216 (6%) → $108. Employee $300 → capped at 50% × 6% × $3,600 = $108.

### 5.6 Credit cards
- Balance rises with charges (actual or estimated), bills paid from the card, one-offs on the card, and estimated interest; falls with payments.
- Payment by mode: full balance, statement balance (user-entered or estimated), minimum, fixed.
  - **Statement balance, estimated** (asked for by Rachel on 2026-10-09): the card's projected balance as of its statement closing date is the estimated payment on the next due date, so a recurring card payment can be set to "pay the statement balance" and follow the card instead of a typed amount.
- Interest estimate when a balance is carried, using the promo rate while active, then `rate_after` / regular APR.
- **Promos:** warn within the user's lead time; stronger warning for deferred interest. **Payoff helper:** remaining promo balance ÷ number of primary paydays before the end date, rounded up to the cent; one-click "add as recurring payment". Test: $2,400 before Mar 15, 2027 with 10 paydays left → $240.00.

### 5.7 Loans, home, net worth
- Amortization: each payment splits into interest and principal; escrow is part of the payment but not principal.
- Home value from user entries, with optional appreciation between entries. Equity = value − linked loan balance.
- Net worth = cash + savings + investments + assets − card balances − loan balances.

### 5.8 Annual costs and suggestions
- **Annual totals** per item and category for calendar years and **rolling 12 months**, plus % change.
- **Suggestions for next year**, using the plan's basis:
  - `last_12`: total of the last 12 full months.
  - `last_12_trend`: last 12 months × (1 + year-over-year % change), rounded to the dollar.
  - `prior_year`: the most recent full calendar year.
- Because planning normally happens in **October**, the default basis is `last_12_trend` and the window is the 12 months ending last month (e.g. Oct 2025 – Sep 2026).
- Fixed-amount items (loan payments) are suggested unchanged.
- Annual items with a save-up show the per-paycheck amount (annual ÷ paydays before the due date).

### 5.9 Debt & decisions planner
Inputs: extra amount per month, comparison period, and the user's options. Rates for debts come from the accounts; **growth rates for investments are editable inputs** (prefilled from the account's assumed rate).
- **Pay down a debt:** amortize with the extra payment; report interest saved, new payoff date.
- **Invest:** future value of monthly contributions at the assumed rate; for a 401(k), include employer match on the portion under the match cap.
- **Payoff order:** highest rate first or smallest balance first; when a debt is paid off, its payment rolls into the next.
- Flags shown first: unclaimed employer match, promos ending.
- Always labeled as estimates for comparison, not advice.

### 5.10 Overrides and edits
- Inline edit asks **Just this one** (default) or **This and all future**.
- The value at first edit is saved as `projected_amount`.

---

## 6. UX rules (apply to all screens)

- **Pay period screen fits one screen with no scrolling** at 1440×900; must also fit 1366×768 and 1280×800 using a compact density. Panels scroll internally only as a last resort.
- Amounts: right-aligned, tabular digits, same box width whether estimate or not. Negatives use a minus sign.
- Status is never color alone (labels/icons too). Text contrast ≥ 4.5:1. Real buttons and inputs, keyboard reachable, visible focus.
- Inline editing: click an amount to edit; Tab to next; Enter saves; Esc cancels.
- **Anything that can be opened and edited shows a visible Edit button** (decided 2026-10-08): the word "Edit" where there is room, a pencil with a tooltip in tight rows. A clickable name or row is never the only way in. A row with an Edit button can also be clicked anywhere to open it (decided 2026-10-09; `components/rowEdit`), except on something in it with its own job, such as an amount or a checkbox.
- **Dialogs and slide-outs close on a click outside them** (decided 2026-10-08), as well as on Escape and the X. If anything in them was changed and not saved, all three ask first ("Keep editing" / "Close without saving"); the form's own Cancel button discards without asking.
- **Forms are easy to tell apart and to read** (decided 2026-10-09): every dialog has a tinted header strip with an icon for what it adds or changes, in the color of the account, bucket or label being edited (the title stays in the normal text color on a light tint, so contrast holds for any color). A long form is split into titled groups on faint panels (`FormSection`), puts its amount first and large, shows icons on its type switch, and ends with an "In short" sentence that says the whole thing back before Save. A bucket that has reached its goal says "Goal reached", in words and an icon.
- **Undo** toast after every change plus Ctrl+Z. No "are you sure?" except deleting whole items, accounts, or a draft plan year, and erasing all data (§7).
- Light, dark, or follow Windows. All colors via CSS variables; user colors from data.
- **Hide amounts** blurs every number. **Auto-lock** after idle; **Lock now** in the sidebar. Sidebar footer: "Data stored only on this PC."
- Confirmation codes display as plain monospace text once entered; a pencil button edits them; empty ones show "+ Add confirmation".
- **"+ Add item"** (header, Ctrl+N) opens a menu: **Money out**, **Money in**, **Transfer**, **Recurring item**, then "Upload a pay stub or receipt…". Every choice opens the **same form** with that kind preselected.

---

## 7. Security, backups, recovery

- **Session gate (built in Phase 0).** Every `/api` route except `GET /api/health`, `POST /api/session` and `POST /api/shutdown` requires a live session: an opaque random token in an HttpOnly, SameSite=Strict cookie, held in memory only and expiring after the auto-lock idle time. Until the PIN exists, `POST /api/session` issues a session without credentials **to requests from this PC only**; Phase 6 puts the PIN in front of it. `POST /api/shutdown` is loopback-only and needs the per-launch token the desktop shell generated.
- Requests whose `Host` header is a domain name the app was not told about are refused (DNS-rebinding protection); a strict Content-Security-Policy is sent on every response.
- **PIN** (4–8 digits) is an app lock, not encryption. Argon2 hash.
- **Recovery code** shown once at PIN setup; can reset the PIN.
- Increasing delays after wrong PINs.
- If both are lost: running the backend launcher with `--reset-pin` on the PC clears the PIN without touching data, then exits. Not available over HTTP.
- Remote sessions always require the PIN and expire with auto-lock.
- **Backups:** daily copy (SQLite backup API) plus the `files/` folder to a user-chosen folder; keep the last N. "Back up now" and "Restore…".
- **Erase all data** (Settings; built in Phase 0): permanently removes everything and leaves a fresh app, as if newly installed. The database files are deleted outright and rebuilt from the migrations (so nothing lingers in free pages or the write-ahead log), and the `files/` and in-app `backups/` folders are deleted. Logs stay; backups the user saved to another folder are not touched. It asks for confirmation with "Keep my data" as the focused default, cannot be undone (Undo does not cover it), ends every session, and from Phase 6 also removes the PIN. `DELETE /api/data` requires a session, an explicit confirmation phrase in the body, and a request from this PC — it is never available from the phone.
- Docs recommend Windows BitLocker for at-rest protection.

### Dependencies and the build pipeline
- **CI** (`.github/workflows/ci.yml`, GitHub Actions) runs on every push to a branch: backend tests (on Windows, where the app ships), frontend typecheck/tests/build, and the dependency checks below. On `main` it does not repeat those (work only reaches main through a pull request whose branch passed them): it builds the installer with `build-installer.ps1`, which runs the tests itself before packaging, and keeps it as a workflow artifact. That job keeps the Tauri command-line tool (one fixed version) and Cargo's package downloads (checksummed by `Cargo.lock`) between runs; the app's own compiled output is never cached, so the installer is always compiled from clean. Actions are pinned to commit hashes; the workflow token is read-only and needs no secrets. This is build-time only — the app itself still makes no outbound calls.
- **Known vulnerabilities:** OSV-Scanner checks every Maven (direct and transitive, from a CycloneDX list), npm and Cargo dependency on every branch push and weekly, and fails the build on a finding. A real, known vulnerability never passes. `desktop/src-tauri/osv-scanner.toml` lists, with reasons, the only findings the scan passes over: two Linux-only crates that are not in the Windows build, and — until 2026-10-15 only — five "unmaintained" notices (no known flaw) on `unic-*` crates that go away with Tauri 2.12 once that is 14 days old.
- **No dependency younger than 14 days.** A release that new has not been out long enough for a vulnerable or hijacked version to be noticed. `check-dependency-age.ps1` enforces it in CI for versions a branch adds or changes, and `min-release-age=14` in `frontend/.npmrc` at install time. The same applies to pinned Actions.
- **When the two rules collide** (the only fix for a vulnerability is younger than 14 days), in this order: (1) use an older version that never had the vulnerability; (2) drop or replace the package, if nothing the app needs is lost; (3) only then take the fixed version early, as a named entry in `dependency-age-exceptions.json` saying what it fixes and why 1 and 2 were not possible. An exception ends by itself: the check looks every entry up on every run (weekly included) and fails once the version is 14 days old, or no longer used, until the entry is deleted. Current exceptions: `source-map-js` 1.2.2 (npm, build tooling only), until 2026-10-14.

---

## 8. Screens

| Screen | Mockup | Key contents |
|---|---|---|
| Pay period (home) | `pay-period.png` | Header with period dates, prev/next, Current badge, estimate key, Hide amounts, **+ Add item ▾**. Period strip of ending balances (low-balance and promo flags). Five numbers: starting, coming in, going out, projected ending (with formula), available after buckets. Column 1: Income (incl. one-off money in), Auto-pay & transfers, Spending budgets. Column 2: **Pay manually & one-offs** in one list (manual bills with paid checkbox, pay-by date, account, confirmation code; then one-offs, each labeled "One-off"). Column 3: Accounts & buckets (tab per account), Credit cards. |
| Add menu / Add form | `add-menu.png`, `add-form.png` | Menu as in §6. Form: kind switch; amount with "Estimate, not final yet"; description; date; account; category picker; tags; set some aside in a bucket; "pays back an expense"; link to upload a stub; live preview of the period's new ending and available. |
| Dashboard | `dashboard.png` | Net worth, **lowest balance ahead**, saved & invested this year, total debt. Spending by category (donut, range switch), net worth over time (entries solid, projection dashed), "coming up & needs attention" list. |
| Recurring items | `recurring.png` | Filterable table (estimates in blue boxes, aligned). Edit drawer: type, category picker, paid-from dropdown, per-occurrence vs annual amount, estimate checkbox, schedule dropdowns (frequency/month/day), autopay, which paycheck pays it, save-up per paycheck into a bucket, price history. |
| Picker | `picker.png` | Type-to-filter with "Add … as new". |
| Accounts | `accounts.png` | Net worth, assets, liabilities; groups for checking & savings, cards, investments, loans, home & assets, each with "last entered" dates. |
| Account detail | `account-detail.png` | Balance, projection, change this year, rate; chart; balances you entered (growth = change − deposits); buckets with goals; interest settings; rate history. |
| Credit cards | `cards.png` | Card list; promo warning; balance, payment setting, statement/due days, APR; promos with progress; statements; payoff helper; "if it's not paid off" estimate. |
| Income | `income.png` | Sources (primary defines periods), schedule, weekend rule, gross/net, money to accounts, taxes & benefits from stubs, match (fixed or formula) with a hint when under the cap; second job with estimated pay; pay stub list and drop zone; upcoming paychecks with weekend shifts. |
| Pay stub review | `pay-stub.png` | Preview of the PDF; every line read, this stub and YTD, grouped (earnings, before tax, taxes, paid to you/from employer), what each tracks as; check that gross − deductions = net; hard-to-read lines flagged; layout remembered per employer. |
| Expenses & receipts | `expenses.png` | One-off spending list filtered by tag; detail panel with tags, reimbursement status, note, attachments with preview and drop zone. |
| Reports | `reports.png` | Saved reports; builder (dates, include, filters, group by, show); totals; chart; table; export CSV or PDF with receipts. |
| Categories & tags | `categories.png` | Categories (with sub-categories, colors, counts, totals) and tags. Color dots open the color picker. |
| Annual costs | `annual.png` | Calendar years / rolling 12 months switch; totals and change; per-item table; selected item's monthly history and whether its budget is too low; "Plan next year →". |
| Plan next year | `plan-year.png` | Plans list (active, draft), **Delete draft & start over**; six steps (Income & benefits, Bills, Annual & save-ups, Spending budgets, Saving & investing, Review); suggestion basis switch; table of last 12 months, trend, suggestion, your amount ("YOURS" when typed), per-month/per-paycheck. First-time setup reuses this wizard. |
| Debt & decisions | `planner.png` | Extra per month and period; flags first; option cards (debts with rates from accounts, investments with **editable growth**); payoff timeline with method switch; "estimates, not advice". |
| Settings | `settings.png` | All values are dropdowns or number inputs: appearance (theme, density, **accent color**), pay periods & projections (source, horizon, budget year start month/day, plan reminder; the mockup's budget timing option was dropped, §11), warnings, security, backups, phone access; **Erase all data** (§7; not in the mockup). |
| Phone: pay period | `phone-pay-period.png` | Current period's ending and available; mark bills paid (44px targets); auto-pay list; bottom tabs. |
| Phone: add expense | `phone-add-expense.png` | Amount, payee, date, account, category, tags, receipt photo straight to the PC, reimbursement. |

---

## 9. Mockups

- Screen images are in `design/`, named as in §8.
- Match layout, sections, hierarchy and behavior. Spacing and type should be close; exact pixels are not required.
- Mockup colors are **default** colors only (§1). Use CSS variables and user-chosen colors.
- Mockup numbers are sample data.

---

## 10. Phases

### Phase 0 — Scaffold and packaging
Project layout per §2; Maven multi-module backend (`engine`, `server`) on Java 21; Javalin with `/api/health`; SQLite + Flyway (`V1__init.sql` with `settings`); engine module with a placeholder JUnit test; React + TS + Vite served by the backend; Tauri project with the **backend lifecycle from §3** (start, `READY` line, window, shutdown, parent-PID watch, single instance); jlink/jpackage build bundled into the Tauri installer; CSP; bundled fonts/icons; Tailwind set up with theme tokens as CSS variables (light/dark/system and an accent-color variable) and base layout with the sidebar; one build script that produces the Windows installer; `CLAUDE.md` summarizing §1.
Also: per-user NSIS installer with WebView2 bootstrapper, shortcuts, uninstall that keeps data by default, pre-migration backup on upgrade, and `build-installer.ps1`.
Also: the **session gate** (§7) lands here rather than in Phase 6, so every endpoint is born behind it and has its 401 test from day one; and a minimal `GET /api/settings` / `PUT /api/settings/{key}` (theme, accent color) so the shell can apply the theme.
Also: **Erase all data** (§7) on the Settings screen, so a test install can be reset to a fresh app before real use.
**Done when** the installer runs on a clean Windows machine with **no Java, Node or admin rights**, the app launches and shows the shell, the health check passes, closing the window leaves no Java process running, installing a newer build keeps the data, and both test suites run.

### Phase 1 — Accounts, income, pay periods
Accounts (all types, user colors), balance entries; schedule rules and the pay period generator with §5.1 tests; income sources (multiple, one primary), per-paycheck amount edits, deductions; projection horizon setting. The type-to-filter picker and color picker components are built here and reused everywhere.
**Done when** the user can add accounts and paychecks and see generated pay periods; §5.1 tests pass.

### Phase 2 — Pay period screen, recurring items, one-offs
Categories and tags (basic); recurring items (bill, transfer, budget, card payment) with pay-from any account, placement, per-occurrence or annual amounts, estimate flag; period assignment (§5.2) with tests; balance projection (§5.3); one-off entries (money in, money out, transfer); **+ Add item** menu and form; the pay period screen per §8; inline editing, just-this-one / this-and-future, paid checkbox, confirmation codes, estimate boxes, hover projected value, undo.
**Done when** the user can replace their spreadsheet: every paycheck has a period view with the right bills, one-offs and an accurate projected ending, fitting one screen at 1440×900.

### Phase 3 — Buckets, savings, save-ups
Buckets, bucket moves (e.g. $50 every paycheck), bucket splits on money in; available after buckets; Accounts & buckets panel; annual items with save-up per paycheck; savings interest (§5.4) with credit day and destination; rate history; low-balance thresholds; account detail screen.
**Done when** checking plus savings at different banks show buckets, save-ups and projected interest, and balances can be updated.

### Phase 4 — Investments, home, net worth
Investment accounts (401(k), IRA, brokerage, HSA via subtype); contributions from deductions and transfers; employer match fixed or formula with §5.5 tests; assumed growth; gain/loss from actual balances only; home value and appreciation; net worth; Accounts screen.
**Done when** a 401(k) with a formula match and a brokerage project forward, and net worth is shown.

### Phase 5 — Credit cards and loans
Card settings, payment modes, statements, charges; bills and one-offs paid by card; interest estimates; promos with warnings and payoff helper; loans with amortization and escrow, linked to the home for equity; Credit cards screen and pay-period panel.
**Done when** a 0%-until-date promo shows a countdown and warning, and the payoff helper adds a per-paycheck payment in one click.

### Phase 6 — Lock, settings, backups, appearance
PIN, recovery code, lockout, `--reset-pin`, auto-lock, Lock now, hide amounts; Settings screen (all dropdowns); accent color; backups and restore (database + files).
**Done when** the app locks after idle, settings change behavior, and a backup restores.

### Phase 7 — Phone access
Remote access setting, interface choice, URL and QR code; HTTP warning and optional self-signed HTTPS; responsive phone layouts (pay period, add expense with receipt photo); "Add to Home Screen" manifest; PIN for remote sessions.
**Done when** the iPhone can open the app over Tailscale, unlock, mark a bill paid, add a confirmation code and add an expense with a photo.

### Phase 8 — Expenses, receipts, reimbursements, reports
Attachments (files on disk, previews); expenses screen; reimbursement status and "pays back an expense"; tags fully wired; report builder, saved reports, CSV and PDF export (PDF can include receipts).
**Done when** a "Medical out-of-pocket 2026" report shows totals by month, reimbursed vs waiting, and exports with receipts.

### Phase 9 — Pay stubs
Upload a stub PDF; read text locally; map lines to gross, contributions, benefits, taxes, net and match; check gross − deductions = net; flag low-confidence lines; remember the layout per employer; update the income source from the latest stub; YTD figures; optional local OCR for scanned stubs.
**Done when** uploading a second stub from the same employer fills in every line with no manual mapping.

### Phase 10 — Dashboard and annual costs
Dashboard per §8 (lowest balance ahead, category donut, net worth timeline, attention list); annual costs with calendar / rolling 12 months, price history per item, budget-too-low hints; Projected vs. Actual view.
**Done when** both screens show the user's real data.

### Phase 11 — Plan next year
Plan years (draft/active), six-step wizard, suggestion bases (§5.8), "YOURS" for typed amounts, delete draft & start over before it starts, activation on the budget year start date, plan reminder; first-time setup reuses the wizard.
**Done when** a 2027 draft can be built in October from the last 12 months, deleted, rebuilt, and becomes active on Jan 1 without changing 2026 history.

### Phase 12 — Debt & decisions
Planner per §5.9 with editable growth rates, option cards, payoff order (highest rate / smallest balance, with rollover), flags, and the "estimates, not advice" label.
**Done when** comparing an extra $300/month across car loan, mortgage, 401(k) and brokerage produces consistent, tested numbers.

---

## 11. Open questions to confirm during the build
- ~~Holidays: user-entered only, or also prefill common US bank holidays (stored locally)?~~ **Decided 2026-10-06:** prefill US bank holidays (calculated locally, Federal Reserve rules: a Sunday holiday is observed on Monday, a Saturday one is not moved); each can be switched off, changed or removed, and the user can add their own.
- ~~Budget timing: keep "start of period" only, or also offer "spread evenly"?~~ **Decided 2026-10-06:** neither as a setting. Each budget has its own date (§5.3): the period's first day by default, changeable per budget for one occurrence or for this and all future.
- Cards without itemized charges: estimate per pay period or per month by default?
- Pay stubs: which employers' formats to test against first (use the user's real stubs, kept local).

---

## 12. Build status

Where the build stands, kept up to date as work lands. `proposals.md` is the feature tracker and is maintained by Rachel only; this section is the in-repo record.

### Phase 0 — Scaffold and packaging (`milestone/0-scaffold`)

| Feature | Status | Notes |
|---|---|---|
| Create Maven multi-module backend with Javalin /api/health and SQLite + Flyway | Done | Backend tests pass. Also carries the session gate and `GET`/`PUT /api/settings` (theme, accent color). |
| Wire Tauri backend lifecycle: start, READY handshake, shutdown, parent-PID watch, single instance | Done | Verified on the built app: window opens on the READY port, a second launch focuses the first, closing the window or killing the shell leaves no backend process. |
| Create React + TypeScript + Vite + Tailwind frontend shell with CSS-variable theme tokens and sidebar | Done | 19 frontend tests. Recharts and date-fns are added with the first screen that needs them. Rachel confirmed on 2026-10-06 that the installed app loads and looks good; phone width (375px) is covered by tests only so far. |
| Gate all outbound network access with a strict CSP, bundled assets and local-only logging | Done | CSP on every response, local log file only, bundled font and icons, unrecognised `Host` headers refused. |
| Create per-user NSIS installer bundling a jlink/jpackage Java runtime, built by build-installer.ps1 | Done; uninstall check pending | `build-installer.ps1` produces `Budget_0.1.0_x64-setup.exe` (57 MB). Rachel installed it on 2026-10-06, then installed a newer build over it, and the app works. Not yet checked by hand: that settings survive an upgrade, and that uninstall keeps data unless "Delete the application data" is ticked (the uninstall hook has never been run). Built with JDK 26; switch `JAVA_HOME` to JDK 21 for an LTS runtime. |
| Erase all data (new; not in `proposals.md`) | Done | Settings → Erase all data, `DELETE /api/data`. 54 backend tests in total, including one that scans the raw database files to prove erased data is gone from disk. Rachel confirmed it works in the installed app on 2026-10-06. |

### Phase 1 — Accounts, income, pay periods (`milestone/1-accounts-income-periods`)

| Feature | Status | Notes |
|---|---|---|
| Create schedule rules and the pay period generator in the engine module | Done | `engine/schedule`: all eight §5.1 cases pass, plus US bank holidays. Projection horizon is a setting (6–36 months, default 12). `every_pay_period` is defined but resolved in Phase 2, when items need it. |
| Create the type-to-filter picker and color picker components reused across the app | Done | `Picker` / `TextPicker` (filter, arrow keys, Enter, `Add "…" as a new …`) and `ColorPicker` (recently used first, named presets, custom hex). Recently used colors come from the colors already on accounts. |
| Add accounts of all types with user colors and user-entered balance entries | Done | Accounts screen grouped by kind, account detail with "Balances you entered", archive and delete (asks first). Net worth, the bulk "Update balances" button, chart, buckets, interest and rates on these screens belong to Phases 3–5. `low_balance_threshold` is in the table but not used until Phase 3. |
| Add income sources with a primary source, paycheck deductions and per-paycheck amount edits | Done | Income screen with source cards, deductions and Upcoming paychecks (amounts editable in place; weekend/holiday moves explained). Pay periods are listed on the Pay periods screen until Phase 2 replaces it with the full view. Taxes & benefits, pay stubs and employer match on this screen belong to Phases 4 and 9. |
| Bank holidays (decided in §11; not a separate `proposals.md` feature) | Done | Settings → Bank holidays. |
| CI pipeline with dependency checks (new; not in `proposals.md`) | Written; first run pending | See §7. It runs on GitHub for the first time when this branch is pushed. |

Also in this branch: Javalin 6 → 7 (Jetty 11 → 12), Jackson 2.21.7, Logback 1.5.38 and AssertJ 3.27.7, because the new vulnerability scan found known issues in the old versions and Jetty 11 no longer gets fixes. 135 backend tests (34 engine, 101 API) and 91 frontend tests. Phone width checked at 375px with real screenshots. Every npm and Cargo package that was under 14 days old was rolled back (Tauri is pinned at 2.11.6 for that reason); the one named exception and the dated `unic-*` ignores are described in §7, and both run out in mid-October 2026, when Tauri 2.12 can be taken. The installer was rebuilt on 2026-10-06: the first build could not start, because Jetty 12 needs the `jdk.zipfs` module and the trimmed runtime lacked it; the module is now included and the build script starts the packaged backend once to catch this kind of fault. Not yet done: Undo for these screens, which arrives with the change log in Phase 2 — until then deleting an account or income source asks first.

### Phase 2a — Recurring items, categories, one-offs and the projection engine (`milestone/2a-recurring-items`)

Phase 2 is split into two pull requests. 2a is the data, engine, API and the two setup screens; 2b is the pay period home screen, the + Add item menu and form, inline editing and Undo.

| Feature | Status | Notes |
|---|---|---|
| Add basic categories and tags | Done | `/api/categories`, `/api/tags`; Categories & tags screen with sub-categories (two levels), item counts and yearly totals from recurring items, color picker on every row. A category or tag in use cannot be deleted. New ones can also be added from their picker inside the item form. |
| Add recurring items with item versions and occurrence overrides | Done except bucket moves and save-ups | `/api/items` and `/api/items/{id}/occurrences/{scheduledDate}`; Recurring items screen with filters, search and the edit drawer (a sheet on a phone). Bill, transfer, budget and card payment; pay from any account; per-occurrence or annual amounts; estimate flag; price history. `bucket_move`, `bucket_id` and `save_up_bucket_id` arrive with buckets in Phase 3. Hand-picked dates are not offered for items. |
| Create the period assignment rule in the engine | Done | `engine/items/PeriodAssigner`: all five §5.2 cases pass. "Every paycheck" items belong to the period they were made for. |
| Create per-account balance projection in the engine | Done except buckets and low-balance flag | `engine/projection/BalanceProjector` and the server's `Ledger`: starting, in, out, ending and lowest point per period and account, from the latest entered balance (§5.3). "Available after buckets" and the low-balance flag are Phase 3; interest, growth and card payment modes are Phases 3–5. |
| Add one-off entries for money in, money out and transfers | API done; screen in 2b | `/api/entries`, counted in the projection. They are entered through the + Add item form, which is 2b. `entry_bucket_splits` is Phase 3; the reimbursement columns exist but are unused until Phase 8. |

Also in this branch: `GET /api/pay-periods` now carries each period's projected ending and lowest point for the primary paycheck's account, shown on the Pay periods list; `GET /api/pay-periods/{start}` returns everything in one period, ready for the 2b screen. An account used by an item or entry cannot be deleted. 241 backend tests (68 engine, 173 API) and 117 frontend tests. Both new screens and the open item form were checked in a real browser at 375px (no sideways scrolling) and at 1440px. Not yet done: Undo — deleting an item, category or tag asks first until the change log arrives in 2b.

### Phase 2b — Pay period screen, + Add item, inline editing and Undo (`milestone/2b-pay-period-screen`)

| Feature | Status | Notes |
|---|---|---|
| Render the pay period home screen | Done except buckets, cards panel and flags | The home screen (`/`, and `/periods/{start}` for any other period): header with prev/next, Current badge, estimate key, Hide amounts and + Add item; the strip of ending balances; starting, coming in, going out, projected ending with its sum; Income, Auto-pay & transfers, Spending budgets, Pay manually & one-offs, and Accounts with a tab per account. ← / → move between periods. It replaces the list of periods from Phase 1. **Not yet:** "Available after buckets" and the buckets in the Accounts panel (Phase 3) — the fifth figure shows the period's **lowest point** until then; the Credit cards panel and promo flags (Phase 5); the low-balance flag on the strip (Phase 3, with thresholds); paycheck deductions under Income (Phase 9). |
| Expose the + Add item menu and shared add form | Done except buckets, pay-back and upload | Menu with Money out, Money in, Transfer and Recurring item (Ctrl+N; Ctrl+E and Ctrl+I go straight to money out and money in, from any screen). One form with a kind switch; Recurring shows the recurring item form inside it. It adds to the period on screen and previews that period's new ending for the chosen account. A one-off is opened from its name to change or delete it. **Not yet:** "Set some aside in a bucket" (Phase 3), "Pays back an expense" (Phase 8) and "Upload a pay stub or receipt…" (Phases 8–9), which is listed in the menu but cannot be chosen. |
| Wire inline editing, overrides, paid checkboxes, confirmation codes, estimate boxes and undo | Done except the report | Click an amount to type over it: Enter saves, Esc cancels, Tab saves and moves on. Recurring items ask "Just this one" (the default) or "This and all future"; items entered as a yearly total offer only "Just this one". Paychecks and one-offs are typed over the same way. The projected value shows on hover or keyboard focus, and on a long press on a phone. Paid checkbox (records today's date), confirmation codes ("+ Add confirmation", then monospace text with a pencil). Undo toast after every change on every screen, and Ctrl+Z; `POST /api/undo`. Deleting a category, tag or one-off no longer asks first; deleting a whole item, account or income source still does, and says it can be undone. **Not yet:** the Projected vs. Actual report (Phase 8). |
| Add one-off entries for money in, money out and transfers | Done except bucket splits and reimbursements | Finished here by the + Add item form. `entry_bucket_splits` is Phase 3; reimbursement is Phase 8. |

Also in this branch: **Hide amounts** (blurs every number on every screen; remembered on this device), built early because the home screen's header needs it. A compact density for short windows (1366×768, 1280×800). 193 server tests and 68 engine tests, 148 frontend tests. Checked in a real browser at 1440×900, 1366×768, 1280×800 and 375px wide: no sideways scrolling; on wide screens the page itself does not scroll, and a column scrolls inside itself when a period holds more than fits. Accent-colored text (links, small actions, the focus ring) was low-contrast on the dark theme with the default accent on every screen; it now uses `--accent-text`, the chosen accent moved just far enough toward white or black to reach 4.5:1 on that theme's backgrounds (`accentForText`). The accent as a fill is unchanged.

### Phase 3 — Buckets, savings, save-ups (`milestone/3-buckets-savings`)

| Feature | Status | Notes |
|---|---|---|
| Add buckets, recurring bucket moves and bucket splits on money in | Done | `/api/buckets` (add, change, archive, delete, move money by hand); `bucket_move` items; a transfer can land in a bucket; "Set some aside in a bucket" on money in and transfers in the + Add item form. The home screen's panel is now **Accounts & buckets**: in buckets, available after buckets, and each bucket with its change this period and goal bar. The fifth figure is **Available after buckets**. A bucket used by a recurring item cannot be archived or deleted. |
| Add annual-amount items with per-paycheck save-ups | Done | "Save up for it in a bucket" on bills and budgets in the item form; the per-paycheck amount shows on the item (`saveUpPerPaycheckCents`). See §4.6 for how each bill is shared out. |
| Schedule estimated savings interest with credit day, destination and rate history | Done | `PUT /api/accounts/{id}/interest`, `/api/accounts/{id}/rates`; Interest card on the account screen; interest listed under Income as an estimate. See §5.4. |
| Validate low-balance thresholds and flag low periods | Done | Threshold on checking and savings (account form). Flagged on the strip ("· Low"), the fifth figure and the account panel — in words, never color alone. |
| Display the account detail screen | Done except change this year and growth | Balance, projected, available after buckets and rate; "Where it's heading" chart (Recharts, described in words beneath); buckets with goals; interest and rate history; balances you entered. **Not yet:** "change this year" and the growth = change − deposits column, which arrive with investments in Phase 4. |

Also in this branch: migration `V5` (buckets, bucket changes, entry splits, interest settings, rate history, and the two bucket columns on item versions — they were not there before, whatever 2a said). Recharts 3.10.1 added (over 14 days old, no known vulnerabilities); it makes the script bundle 809 kB, loaded from disk. Undo covers every new table without new code. An account that holds buckets cannot be changed to a type that cannot. **Edit buttons** (after Rachel's first test, 2026-10-08): anything that opens for editing now shows a visible Edit control (`components/EditButton`) — a labelled button on each Recurring items row, a pencil beside each recurring item and one-off on the pay period screen, the word on each category and tag row — because a clickable name was not discoverable; this is a rule for every later screen (§6). The item form's date is now "Apply these changes from" and says it restarts at today each time. From the same test: every dialog closes on a click outside and warns about unsaved changes (§6); the Add an account form takes a checking or savings account's APY, saved as its first rate from today, and Edit account points to the Interest card. **Backdating:** every item now shows its own date in the form — **Starts** for "every paycheck" and day-of-month schedules, which had none on screen — and it is saved and shown again; it can be any date, past or future. Moving it makes the change reach back to the earlier of the old and new start, so nothing scheduled outside the new start is left behind (the item's price history before today is replaced by its current terms when that happens), and a bucket move's bucket then counts from that pay period's payday. The date a change applies from is no longer a field: an edit made in the form is dated today, and the form shows the item's **Date added** as plain text instead. An "every paycheck" start that is today or earlier counts for the whole pay period it falls in (the paycheck in hand); one still to come waits for the first payday on or after it, and is saved as that payday. **Buckets cannot hold more than the account:** a starting amount or a move by hand that would put more in an account's buckets than it holds is refused (409); schedules are not refused, since balances and schedules both change, but the Buckets card says so when the buckets hold more than the entered balance. A bucket's Edit form shows **In this bucket** and takes a new amount, recorded as an "Amount adjusted" move for the difference; it also lists what was changed by hand. There is no separate Move money button any more: Edit is the one way in. **Balance and buckets always add up** (decided 2026-10-09): the balance stays the number the user entered and is never adjusted to fit; the Buckets card shows In buckets + Not in a bucket = Balance you entered, and "Put the rest in a bucket" moves the remainder into a chosen bucket in one step. The Accounts list has an Edit button on every account, and a group left alone on its row spans the full width. Every bucket row has **Add a recurring move**, which opens the recurring item form as a bucket move into that bucket (at the amount its goal takes, when it has one), and lists the recurring items that already feed it. An item's "Price history" is now **Amount history**. On bills and budgets the save-up option is always shown, and a bucket that does not exist yet can be added from it in place, like a category; for something paid from a card it says buckets live in checking and savings. **Goal dates** (migration `V6`): see §4.2. 97 → 103 engine tests, 271 API tests, 188 frontend tests. The window no longer rubber-bands on a touchpad swipe past its edge (`overscroll-behavior: none`). The account screen no longer shows a second scroll bar (visually hidden text is now placed inside the scrolling area on every screen). A new bucket move starts today, not in January. **Interest** is one summary with one Edit (rate, the date it applies from, credit day, destination, one Save): a new rate on a new date leaves the old one in the history with its end date; the same rate on another date corrects when it began. 358 backend tests (97 engine, 261 API) and 180 frontend tests. Checked in a real browser at 1440×900, 1366×768, 1280×800 and at an emulated 375px: no sideways scrolling on any new screen or form, and the home screen still does not scroll as a page on wide windows.

Form redesign (2026-10-09, §6): the recurring item form is a centered dialog like every other form, no longer a slide-out (Rachel, 2026-10-09: "make it consistent"); header strip and icon on every dialog; the recurring item form is grouped into Amount, What it is, Where it's paid from, Schedule, Saving up, Tags and notes, with a large amount field, icons on the type switch and the "In short" sentence; "Goal reached" on buckets. **Not yet:** the grouped layout and large amount on the other forms (account, bucket, interest, one-off money in/out), waiting on Rachel's say on the recurring form.

Archived recurring items (decided 2026-10-09): hidden from the Recurring items list and left out of the All and per-kind counts; an **Archived** filter chip, shown only when something is archived, lists them.

Buckets card tidy-up (decided 2026-10-09): a recurring move is set up inside the bucket's own Add/Edit form ("Set aside every paycheck (optional)", which adds an every-paycheck bucket move from the paycheck in hand; the form also lists what already feeds the bucket) instead of from a button on its row; archived buckets sit in a closed "Archived buckets (n)" section like archived accounts; "Put the rest in a bucket" is closed until opened.

Pay period screen (decided 2026-10-09): hovering, focusing or tapping **Coming in** lists what it is made of for that account (each paycheck, one-off money in, transfers arriving, estimated interest); any difference from the backend's total is listed as its own line, so the list always adds up to the figure.

Goals on the pay period screen (decided 2026-10-09): each bucket in the Accounts & buckets panel says how its goal is going at the end of that period, on its existing row (no new panel): "Goal reached", or against a goal date "On track" / "Short $X a paycheck". A bucket that has reached its goal offers **Stop saving**, which ends the recurring bucket moves into it after that pay period (their end date is set; Undo brings them back); otherwise a move does whatever it was set up to do. The bucket form's recurring move has a "Stop on the goal date" tick (on by default) when the goal has a date still ahead.

Recurring items sort (decided 2026-10-09): pressing a column heading orders the list by that column, pressing it again reverses it (an arrow and `aria-sort` show which); items with no next date go last. On a phone, where there is no header row, a "Sort by" list and a direction button do the same.

### Carried forward into later phases

| Feature | Notes |
|---|---|
| Gate the app behind a PIN with recovery code, lockout, --reset-pin, auto-lock and hide amounts | The session cookie and 401 gate already exist (Phase 0), and Hide amounts was built in Phase 2b. This feature adds the PIN, Argon2, lockout, recovery and the lock screen on top. |
| Expose the Settings screen with dropdown values and the accent color | Theme and accent color endpoints exist; the screen has only a theme dropdown so far. |
