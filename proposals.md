## Feature: Create Maven multi-module backend with Javalin /api/health and SQLite + Flyway

**Description:**
Project layout per the plan: budget/ containing PLAN.md, design/, backend/ (pom.xml, engine/ for pure calculation code + JUnit tests with no I/O, server/ for Javalin routes, JDBI repositories, Flyway migrations, files, PDF reading), frontend/ (React + TS + Vite) and desktop/ (Tauri project, src-tauri/, bundles backend app image + frontend build).

Maven multi-module backend (engine, server) on Java 21 LTS (or newer LTS). Gradle is fine if preferred; pick one in Phase 0 and keep it. Javalin with /api/health — lightweight, starts in about a second, serves the API and the built React app, no Spring. JSON via Jackson (with jsr310 module for dates). Database: SQLite via sqlite-jdbc (xerial) + JDBI 3 (or plain JDBC), stored at %APPDATA%\<AppName>\budget.db with WAL mode and foreign keys on. Migrations: Flyway (flyway-core), src/main/resources/db/migration/V1__init.sql, V2__…, run at startup; never edit a committed migration. V1__init.sql creates settings. Engine module with a placeholder JUnit test. Tests: JUnit 5 + AssertJ (engine, API).

Calculation logic lives in a separate Java module (engine) with no database, HTTP or UI dependencies, covered by JUnit tests. Use Java records for data, java.time.LocalDate for dates, and constructor injection without a DI framework. Money is stored as integer cents (INTEGER in SQLite, long in Java), wrapped in a small Money value type; rates are stored as basis points (4.10% = 410); interest, growth and amortization use BigDecimal and round to cents with RoundingMode.HALF_EVEN; never use double for money. Dates are local calendar dates stored as YYYY-MM-DD text. API is REST; the frontend talks to the backend only through the HTTP API (no Tauri invoke commands).

**Priority:** high

**Ref:** backend-scaffold

---

## Feature: Wire Tauri backend lifecycle: start, READY handshake, shutdown, parent-PID watch, single instance

**Description:**
Tauri 2 is the desktop shell only: native Windows window and installer. Its own code is a small Rust file (~50–100 lines) that only starts/stops the Java backend and opens the window. No business logic in Rust. This is the riskiest part and is solved in Phase 0.

Backend lifecycle:
- Tauri starts the bundled backend launcher (from the jpackage app image in Tauri's resources), passing its own process ID and the app data folder.
- The backend runs Flyway migrations, binds Javalin to 127.0.0.1 on the configured port (default 4873; if busy, the next free port), then prints one line to stdout: READY port=NNNN.
- Tauri waits for that line (with a timeout and a friendly error screen if it never comes), then points the window at http://127.0.0.1:NNNN/. The backend serves the React build itself, so there is one origin and no CORS.
- On window close, Tauri asks the backend to shut down (POST /api/shutdown, local only), then kills the process if it hasn't exited after a few seconds.
- The backend also watches Tauri's process ID (ProcessHandle.of(pid).onExit()) and exits by itself if Tauri dies, so no stray server is ever left running.
- Only one backend may run at a time (lock file in the app data folder).

**Priority:** critical

**Ref:** lifecycle

---

## Feature: Create React + TypeScript + Vite + Tailwind frontend shell with CSS-variable theme tokens and sidebar

**Description:**
Frontend: React + TypeScript + Vite, served by the backend. Data fetching via TanStack Query, charts via Recharts (bundled), UI dates via date-fns. Tests: Vitest + React Testing Library.

Styling: Tailwind CSS (v4). Theme tokens (colors, spacing, radius, fonts) defined once as CSS variables and exposed to Tailwind, so light/dark and the user's accent color switch by changing variables. User-chosen colors (categories, tags, accounts, buckets) come from data and are applied with inline style/CSS variables, never generated class names. Tailwind set up with theme tokens as CSS variables (light/dark/system and an accent-color variable) and base layout with the sidebar. Sidebar footer: "Data stored only on this PC."

Bundled fonts/icons. Mockups in design/ show layout, hierarchy and behavior; all numbers in them are sample data and must never be hard-coded; mockup colors are defaults only.

**Priority:** high

**Ref:** frontend-shell

---

## Feature: Gate all outbound network access with a strict CSP, bundled assets and local-only logging

**Description:**
Privacy is non-negotiable. All data stays on the user's computer: no cloud services, no third-party APIs, no bank connections, no sync services. No telemetry, analytics, crash reporting, or update checks; do not add any dependency that phones home. No runtime internet requests: fonts, icons, chart libraries and all assets are bundled locally (no Google Fonts or CDN links, even though the mockups use them for preview). PDF reading (pay stubs, receipts) and any text recognition (OCR) run on the PC.

The only network listener is the app's own local HTTP server. It binds to 127.0.0.1 by default. Phone access is off by default. Strict Content Security Policy in Tauri that blocks external origins.

Logging: SLF4J + Logback, local log file only — rotating file in the app data folder, no remote appenders.

**Priority:** critical

**Ref:** privacy

---

## Feature: Create per-user NSIS installer bundling a jlink/jpackage Java runtime, built by build-installer.ps1

**Description:**
Packaging: jlink trimmed Java runtime + jpackage app image, bundled inside the Tauri installer. No separate Java install needed.

The installer:
- One .exe installer (Tauri's NSIS bundle). Double-click, Next, Finish. Nothing else to install or configure.
- Installs per user, no admin rights needed, into the user's app folder, with Start menu and optional desktop shortcuts.
- Bundles everything: the trimmed Java runtime, the backend, the React build, fonts and icons. No separate Java, Node or database install.
- WebView2: use Tauri's embedded bootstrapper so it's installed automatically if missing.
- First launch creates the app data folder and database, runs migrations, and opens the setup wizard.
- Upgrades: running a newer installer over an existing install keeps all data; migrations run on next launch. A database backup is taken automatically before migrations run.
- Uninstall removes the program but keeps the data folder unless the user ticks "also delete my data".
- No auto-update (no network). Updating means running a newer installer.

Builds of the installer happen on Windows (Tauri and jpackage produce Windows output only when run on Windows). Install once: JDK 21, Maven, Node LTS, Rust toolchain (only so Tauri can compile its small wrapper), and the Visual Studio C++ Build Tools that Tauri requires. One script (build-installer.ps1) runs on Windows and produces the installer: tests → backend jar → jlink/jpackage app image → frontend build → Tauri bundle.

**Priority:** high

**Ref:** installer

---

## Feature: Add accounts of all types with user colors and user-entered balance entries

**Description:**
Accounts (all types, user colors), balance entries.

- accounts — id, name, institution, type, subtype_label, color, low_balance_threshold (nullable), sort_order, archived.
- type: checking | savings | credit_card | investment | loan. subtype_label is free text chosen by the user (e.g. "401(k)", "Brokerage", "HSA", "Mortgage").
- balance_entries — account_id, date, amount, note. A user-entered actual balance. Projections roll forward from the latest entry on or before a date. (Not reconciliation — just "the real number is X".)

Categories, tags, accounts and buckets each have a color the user picks. Text drawn on a user color automatically switches between dark and light to keep at least 4.5:1 contrast; the user's color is never changed. Money stored as integer cents.

**Priority:** high

**Ref:** accounts

**Depends On:** backend-scaffold, lifecycle, frontend-shell, privacy, installer

---

## Feature: Create schedule rules and the pay period generator in the engine module

**Description:**
Schedule rules and the pay period generator with §5.1 tests; projection horizon setting.

- schedule_rules — id, frequency, interval, day_1, day_2, month, start_date, end_date, weekend_rule.
- frequency: every_pay_period | weekly | biweekly | semi_monthly | monthly | every_n_months | yearly | one_time | manual_dates.
- monthly accepts a day or last; days that don't exist in a month fall to its last day.
- weekend_rule: none | previous_business_day | next_business_day.
- manual_dates, holidays (user-entered, optional).

Pay periods: periods come from the primary income source. A period runs from one payday up to the day before the next. Generated through the projection horizon (settings: projection horizon, default 12 months). Weekend/holiday rules move paydays and therefore boundaries. Lives in the engine module (pure Java, JUnit-tested).

**Priority:** high

**Ref:** pay-periods

**Depends On:** backend-scaffold, lifecycle, frontend-shell, privacy, installer

**Acceptance Criteria:**
- [ ] semi-monthly 1st & 15th
- [ ] semi-monthly 15th & last (Feb leap/non-leap)
- [ ] biweekly across a year boundary
- [ ] weekly
- [ ] monthly
- [ ] manual dates
- [ ] payday Sunday Nov 1, 2026 with previous_business_day → Fri Oct 30
- [ ] with a user holiday on that Friday → Thu Oct 29

---

## Feature: Add income sources with a primary source, paycheck deductions and per-paycheck amount edits

**Description:**
Income sources (multiple, one primary), per-paycheck amount edits, deductions.

- income_sources — id, name, deposit_account_id, gross_amount, net_amount, schedule_rule_id, is_primary. Multiple sources allowed; the primary source's schedule defines pay periods.
- paycheck_deductions — income_source_id, name, amount, to_account_id (401(k), HSA…). These never pass through checking.
- settings: primary income source.

Other paychecks (e.g. a second job) land inside whichever pay period of the primary source they fall in.

**Priority:** high

**Ref:** income

**Depends On:** backend-scaffold, lifecycle, frontend-shell, privacy, installer

---

## Feature: Create the type-to-filter picker and color picker components reused across the app

**Description:**
The type-to-filter picker and color picker components are built here and reused everywhere.

Pickers, not free text: anything chosen from a list (category, tag, account, bucket, payee, frequency, month, day, settings options) uses a dropdown, never a free-text field. Lists the user builds (categories, tags, payees, accounts) use a type-to-filter picker: typing filters existing entries; if nothing matches, the last option is Add "…" as new. This prevents misspellings. See design/picker.png.

Colors are the user's choice: categories, tags, accounts and buckets each have a color the user picks. Clicking any color dot opens a color picker: a row of preset swatches, the user's recently used colors first, and a custom option that accepts any hex code. The app's accent color (buttons, highlights, current pay period) is a setting under Appearance, using the same picker. Readability: text drawn on a user color automatically switches between dark and light to keep at least 4.5:1 contrast. The user's color is never changed. Charts, pills, bucket bars and dots all read colors from the data, never from hard-coded values.

**Priority:** high

**Ref:** pickers

**Depends On:** backend-scaffold, lifecycle, frontend-shell, privacy, installer

---

## Feature: Add basic categories and tags

**Description:**
Categories and tags (basic).

- categories — id, name, color, parent_id (nullable), sort_order. One per item. Includes income categories (e.g. Income › Bonus).
- tags — id, name, color. Many per item, via item_tags / entry_tags link tables.

Categories and tags are chosen with the type-to-filter picker and colored with the color picker.

**Priority:** high

**Ref:** categories

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Add recurring items with item versions and occurrence overrides

**Description:**
Recurring items (bill, transfer, budget, card payment) with pay-from any account, placement, per-occurrence or annual amounts, estimate flag.

- items — id, name, kind, amount, amount_mode (per_occurrence | annual), is_estimate, category_id, pay_from_account_id, pay_to_account_id, bucket_id, autopay, placement, schedule_rule_id, save_up_bucket_id (nullable), note, archived.
- kind: bill | transfer | budget | bucket_move | card_payment.
- pay_from_account_id can be any account, including a credit card (the charge then lands on the card).
- placement: due_period | one_early.
- item_versions — "this and all future" edits start a new version effective from a date.
- occurrence_overrides — item_id, original_due_date, amount, due_date, period_override, paid, paid_date, confirmation_code, note, skipped, projected_amount. "Just this one" edits live here. Occurrences are computed on the fly; only changes are stored.

Recurring items screen (design/recurring.png): filterable table (estimates in blue boxes, aligned). Edit drawer: type, category picker, paid-from dropdown, per-occurrence vs annual amount, estimate checkbox, schedule dropdowns (frequency/month/day), autopay, which paycheck pays it, save-up per paycheck into a bucket, price history.

**Priority:** high

**Ref:** items

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Create the period assignment rule in the engine

**Description:**
Period assignment with tests. For an occurrence due on D, with P = the period containing D:
- It lands in P − 1 if placement = one_early, or if D is a payday (first day of P) and the item is not autopay.
- Otherwise it lands in P.
- A user can move a single occurrence to another period (stored as an override).

**Priority:** high

**Ref:** placement

**Depends On:** accounts, pay-periods, income, pickers

**Acceptance Criteria:**
- [ ] rent due Oct 1 (payday), manual → Sep 15–30 period
- [ ] same rent on autopay → Oct 1–14
- [ ] electric due Oct 16 one_early → Oct 1–14
- [ ] due_period → Oct 15–31
- [ ] an item that is both one_early and due on payday moves back one period, not two

---

## Feature: Create per-account balance projection in the engine

**Description:**
Balance projection (per account):
- Start from the latest balance_entry on or before the period start.
- Apply in date order: paychecks (net), one-off money in/out, bills, transfers, card payments, interest, growth, charges.
- Budget lines come out at the start of the period (setting: budget timing, start of period).
- Per period and account: starting balance, total in, total out, projected ending, lowest point, available after buckets.
- Low-balance flag only if the user set a threshold for that account.

Lives in the engine module, pure Java, JUnit-tested, written before wiring up the UI.

**Priority:** high

**Ref:** projection

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Add one-off entries for money in, money out and transfers

**Description:**
One-off entries (money in, money out, transfer).

- entries — id, kind (money_out | money_in | transfer), date, amount, is_estimate, description, payee, category_id, account_id, to_account_id (transfers), note, reimburses_entry_id (nullable).
- Used for one-off expenses (vet emergency, gift, donation), one-off income (bonus, refund, gift, side cash), and one-off transfers.
- entry_bucket_splits — entry_id, bucket_id, amount (e.g. put $500 of a bonus into a bucket).
- card_charges are entries whose account is a credit card.

**Priority:** high

**Ref:** entries

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Expose the + Add item menu and shared add form

**Description:**
"+ Add item" (header, Ctrl+N) opens a menu: Money out, Money in, Transfer, Recurring item, then "Upload a pay stub or receipt…". Every choice opens the same form with that kind preselected.

Add menu / Add form (design/add-menu.png, design/add-form.png). Form: kind switch; amount with "Estimate, not final yet"; description; date; account; category picker; tags; set some aside in a bucket; "pays back an expense"; link to upload a stub; live preview of the period's new ending and available.

**Priority:** high

**Ref:** add-item

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Render the pay period home screen

**Description:**
The pay period screen per design/pay-period.png. Header with period dates, prev/next, Current badge, estimate key, Hide amounts, + Add item ▾. Period strip of ending balances (low-balance and promo flags). Five numbers: starting, coming in, going out, projected ending (with formula), available after buckets. Column 1: Income (incl. one-off money in), Auto-pay & transfers, Spending budgets. Column 2: Pay manually & one-offs in one list (manual bills with paid checkbox, pay-by date, account, confirmation code; then one-offs, each labeled "One-off"). Column 3: Accounts & buckets (tab per account), Credit cards.

Pay period screen fits one screen with no scrolling at 1440×900; must also fit 1366×768 and 1280×800 using a compact density. Panels scroll internally only as a last resort. Amounts: right-aligned, tabular digits, same box width whether estimate or not. Negatives use a minus sign. Opens on the current pay period; ← / → move between periods.

**Priority:** high

**Ref:** pay-period-screen

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Wire inline editing, overrides, paid checkboxes, confirmation codes, estimate boxes and undo

**Description:**
Inline editing, just-this-one / this-and-future, paid checkbox, confirmation codes, estimate boxes, hover projected value, undo.

- Inline editing: click an amount to edit; Tab to next; Enter saves; Esc cancels.
- Inline edit asks Just this one (default) or This and all future. The value at first edit is saved as projected_amount.
- Any amount the app calculates or estimates can be typed over; the typed value simply becomes the value. There is no "revert to calculated" button. The original projected value is kept in the database and shown on hover (desktop) / long-press (phone), and in the Projected vs. Actual report.
- Estimated amounts are shown inside a light blue box (not an "est." tag). Final amounts use the same box size with no fill, so amount columns stay aligned. A small key ("$0.00 = estimate") appears where estimates are shown. An amount stops being an estimate when the user edits it, marks it paid, or unchecks "Estimate, not final yet".
- Confirmation codes display as plain monospace text once entered; a pencil button edits them; empty ones show "+ Add confirmation".
- Undo toast after every change plus Ctrl+Z. No "are you sure?" except deleting whole items, accounts, or a draft plan year.
- change_log — before/after of every edit, powering Undo.
- Status is never color alone (labels/icons too). Text contrast ≥ 4.5:1. Real buttons and inputs, keyboard reachable, visible focus.

**Priority:** high

**Ref:** inline-edit

**Depends On:** accounts, pay-periods, income, pickers

---

## Feature: Add buckets, recurring bucket moves and bucket splits on money in

**Description:**
Buckets, bucket moves (e.g. $50 every paycheck), bucket splits on money in; available after buckets; Accounts & buckets panel.

- buckets — id, account_id, name, color, goal_amount (nullable), note, sort_order, archived. Checking and savings only. Buckets don't change the account balance; they reduce available.
- bucket_changes — bucket_id, date, amount (+/−), source (manual | transfer | bucket_move | interest | income_split).
- bucket_move items move money from available into a bucket inside the same account (e.g. $50 every paycheck to Holiday gifts).
- entry_bucket_splits — entry_id, bucket_id, amount.

Accounts & buckets panel on the pay period screen shows a tab per account.

**Priority:** medium

**Ref:** buckets

**Depends On:** categories, items, placement, projection, entries, add-item, pay-period-screen, inline-edit

---

## Feature: Add annual-amount items with per-paycheck save-ups

**Description:**
Annual items with save-up per paycheck. amount_mode = annual: the user enters a yearly total. With save_up_bucket_id set, the app creates a matching per-paycheck bucket move so the money is there by the due date. Annual items with a save-up show the per-paycheck amount (annual ÷ paydays before the due date).

**Priority:** medium

**Ref:** save-ups

**Depends On:** categories, items, placement, projection, entries, add-item, pay-period-screen, inline-edit

---

## Feature: Schedule estimated savings interest with credit day, destination and rate history

**Description:**
Savings interest with credit day and destination; rate history.

- interest_settings — account_id, credit_day (number or 'last'), destination (unassigned | bucket | proportional), destination_bucket_id.
- rate_history — account_id, kind (apy | growth | apr | loan_rate), rate_bp, effective_date. A change applies from its date forward.
- On the account's credit day: estimated interest = balance × ((1 + APY)^(1/12) − 1), credited to the chosen destination, shown as an estimate.
- A user-entered balance replaces the projection from that date.

**Priority:** medium

**Ref:** interest

**Depends On:** categories, items, placement, projection, entries, add-item, pay-period-screen, inline-edit

---

## Feature: Validate low-balance thresholds and flag low periods

**Description:**
Low-balance thresholds. accounts.low_balance_threshold (nullable). Low-balance flag only if the user set a threshold for that account. The period strip on the pay period screen flags low-balance periods.

**Priority:** medium

**Ref:** low-balance

**Depends On:** categories, items, placement, projection, entries, add-item, pay-period-screen, inline-edit

---

## Feature: Display the account detail screen

**Description:**
Account detail screen (design/account-detail.png): balance, projection, change this year, rate; chart; balances you entered (growth = change − deposits); buckets with goals; interest settings; rate history.

**Priority:** medium

**Ref:** account-detail

**Depends On:** categories, items, placement, projection, entries, add-item, pay-period-screen, inline-edit

---

## Feature: Add investment accounts with contributions, assumed growth and gain/loss from actual balances

**Description:**
Investment accounts (401(k), IRA, brokerage, HSA via subtype); contributions from deductions and transfers; assumed growth; gain/loss from actual balances only.

- Contributions: paycheck deductions, employer match, transfers.
- Growth: monthly from the user's assumed rate, always labeled as an assumption (rate_history kind growth).
- Gain/loss uses only user-entered balances: ending − starting − net contributions. A projection being off is never a loss.

**Priority:** medium

**Ref:** investments

**Depends On:** buckets, save-ups, interest, low-balance, account-detail

---

## Feature: Add employer match as a fixed amount or formula

**Description:**
Employer match fixed or formula with §5.5 tests.

- employer_match — income_source_id, to_account_id, mode (fixed | formula), fixed_amount, match_bp, cap_bp_of_gross.

Income screen shows match (fixed or formula) with a hint when under the cap.

**Priority:** medium

**Ref:** match

**Depends On:** buckets, save-ups, interest, low-balance, account-detail

**Acceptance Criteria:**
- [ ] gross $3,600, employee $150 (4.17%), match 50% up to 6% → $75
- [ ] Employee $216 (6%) → $108
- [ ] Employee $300 → capped at 50% × 6% × $3,600 = $108

---

## Feature: Track home value and net worth, and render the Accounts screen

**Description:**
Home value and appreciation; net worth; Accounts screen.

- assets — id, name, kind (home | vehicle | other), color, appreciation_bp (nullable), linked_loan_account_id.
- asset_values — asset_id, date, value.
- Home value from user entries, with optional appreciation between entries. Equity = value − linked loan balance.
- Net worth = cash + savings + investments + assets − card balances − loan balances.

Accounts screen (design/accounts.png): net worth, assets, liabilities; groups for checking & savings, cards, investments, loans, home & assets, each with "last entered" dates.

**Priority:** medium

**Ref:** net-worth

**Depends On:** buckets, save-ups, interest, low-balance, account-detail

---

## Feature: Add credit card settings, payment modes, statements and charges

**Description:**
Card settings, payment modes, statements, charges; bills and one-offs paid by card.

- card_settings — account_id, statement_close_day, due_day, payment_mode (full_balance | statement_balance | minimum | fixed), fixed_payment_amount, autopay, pay_from_account_id.
- card_statements — account_id, close_date, statement_balance, minimum_due, due_date.
- card_charges are entries whose account is a credit card.
- Balance rises with charges (actual or estimated), bills paid from the card, one-offs on the card, and estimated interest; falls with payments.
- Payment by mode: full balance, statement balance (user-entered or estimated), minimum, fixed.

**Priority:** medium

**Ref:** cards

**Depends On:** investments, match, net-worth

---

## Feature: Add card interest estimates, promo rates with warnings, and the payoff helper

**Description:**
Interest estimates; promos with warnings and payoff helper.

- card_promos — account_id, label, kind (balance_transfer | purchase | other), rate_bp, start_date, end_date, original_balance, rate_after_bp (nullable → regular APR), deferred_interest. Several per card allowed.
- Interest estimate when a balance is carried, using the promo rate while active, then rate_after / regular APR.
- Promos: warn within the user's lead time (settings: promo warning days, default 60); stronger warning for deferred interest.
- Payoff helper: remaining promo balance ÷ number of primary paydays before the end date, rounded up to the cent; one-click "add as recurring payment".

**Priority:** medium

**Ref:** promos

**Depends On:** investments, match, net-worth

**Acceptance Criteria:**
- [ ] $2,400 before Mar 15, 2027 with 10 paydays left → $240.00

---

## Feature: Add loans with amortization and escrow linked to the home for equity

**Description:**
Loans with amortization and escrow, linked to the home for equity.

- loan_settings — account_id, original_principal, rate_bp, term_months, start_date, payment_amount, escrow_insurance, escrow_taxes.
- Amortization: each payment splits into interest and principal; escrow is part of the payment but not principal.
- assets.linked_loan_account_id links the home to its loan. Equity = value − linked loan balance.

**Priority:** medium

**Ref:** loans

**Depends On:** investments, match, net-worth

---

## Feature: Render the Credit cards screen and the pay-period cards panel

**Description:**
Credit cards screen and pay-period panel. Credit cards screen (design/cards.png): card list; promo warning; balance, payment setting, statement/due days, APR; promos with progress; statements; payoff helper; "if it's not paid off" estimate. The pay period screen's column 3 shows a Credit cards panel.

**Priority:** medium

**Ref:** cards-ui

**Depends On:** investments, match, net-worth

---

## Feature: Gate the app behind a PIN with recovery code, lockout, --reset-pin, auto-lock and hide amounts

**Description:**
PIN, recovery code, lockout, --reset-pin, auto-lock, Lock now, hide amounts.

- PIN (4–8 digits) is an app lock, not encryption. Argon2 hash (Password4j or Bouncy Castle).
- auth — pin_hash, recovery_code_hash, failed attempts, lockout-until.
- Recovery code shown once at PIN setup; can reset the PIN.
- Increasing delays after wrong PINs.
- If both are lost: running the backend launcher with --reset-pin on the PC clears the PIN without touching data, then exits. Not available over HTTP.
- Remote sessions always require the PIN and expire with auto-lock.
- Hide amounts blurs every number. Auto-lock after idle; Lock now in the sidebar.
- Settings: auto-lock minutes, start with amounts hidden.

**Priority:** high

**Ref:** pin

**Depends On:** cards, promos, loans, cards-ui

---

## Feature: Expose the Settings screen with dropdown values and the accent color

**Description:**
Settings screen (all dropdowns); accent color. Settings (design/settings.png): all values are dropdowns or number inputs: appearance (theme, density, accent color), pay periods & projections (source, horizon, budget timing, budget year start month/day, plan reminder), warnings, security, backups, phone access.

settings — key/value: theme (light | dark | system), density, accent color, projection horizon (default 12 months), budget timing (start of period), budget year start (month + day, default Jan 1), plan-next-year reminder date (default Oct 1), auto-lock minutes, promo warning days (default 60), unpaid-bill highlight days (default 3), start with amounts hidden, backup folder and count, phone access settings, primary income source.

Light, dark, or follow Windows. All colors via CSS variables; user colors from data.

**Priority:** medium

**Ref:** settings

**Depends On:** cards, promos, loans, cards-ui

---

## Feature: Schedule daily backups with restore of the database and files

**Description:**
Backups and restore (database + files). Backups: daily copy (SQLite backup API) plus the files/ folder to a user-chosen folder; keep the last N. "Back up now" and "Restore…". Settings: backup folder and count. Docs recommend Windows BitLocker for at-rest protection.

**Priority:** high

**Ref:** backups

**Depends On:** cards, promos, loans, cards-ui

---

## Feature: Expose remote phone access over LAN or Tailscale with PIN-protected sessions

**Description:**
Remote access setting, interface choice, URL and QR code; HTTP warning and optional self-signed HTTPS; PIN for remote sessions.

When enabled, the server also listens on the chosen interface (LAN IP or Tailscale IP). Plain HTTP on home Wi-Fi is unencrypted, so Settings shows a warning and recommends Tailscale. The PIN is always required. The phone shows "Can't reach your PC" when the app isn't running. Phone access is off by default; the backend binds to 127.0.0.1 only unless it's turned on.

**Priority:** medium

**Ref:** remote

**Depends On:** pin, settings, backups

---

## Feature: Render responsive phone layouts with an Add to Home Screen manifest

**Description:**
Responsive phone layouts (pay period, add expense with receipt photo); "Add to Home Screen" manifest.

- Phone: pay period (design/phone-pay-period.png) — current period's ending and available; mark bills paid (44px targets); auto-pay list; bottom tabs.
- Phone: add expense (design/phone-add-expense.png) — amount, payee, date, account, category, tags, receipt photo straight to the PC, reimbursement.

Same React app against the same API as the desktop window.

**Priority:** medium

**Ref:** phone-ui

**Depends On:** pin, settings, backups

---

## Feature: Persist attachments on disk with previews

**Description:**
Attachments (files on disk, previews).

- files — id, hash, original_name, mime, size, created_at.
- attachments — file_id, owner_type (entry | occurrence | pay_stub | statement), owner_id.
- Attachments (receipts, invoices, pay stubs) are copied into %APPDATA%\<AppName>\files\, named by content hash, and referenced from the database. Originals are never modified.

**Priority:** medium

**Ref:** attachments

**Depends On:** remote, phone-ui

---

## Feature: Display the expenses screen with reimbursement tracking and fully wired tags

**Description:**
Expenses screen; reimbursement status and "pays back an expense"; tags fully wired.

- Reimbursement — reimbursement_status (none | to_submit | submitted | reimbursed) on money-out entries; a money-in entry can link to the expense it pays back (entries.reimburses_entry_id).
- Expenses & receipts (design/expenses.png): one-off spending list filtered by tag; detail panel with tags, reimbursement status, note, attachments with preview and drop zone.

**Priority:** medium

**Ref:** expenses

**Depends On:** remote, phone-ui

---

## Feature: Add the report builder with saved reports and CSV/PDF export

**Description:**
Report builder, saved reports, CSV and PDF export (PDF can include receipts).

- saved_reports — id, name, definition_json (date range, filters by tag/category/account/payee/kind, group by, chart type).
- Reports (design/reports.png): saved reports; builder (dates, include, filters, group by, show); totals; chart; table; export CSV or PDF with receipts.

**Priority:** medium

**Ref:** reports

**Depends On:** remote, phone-ui

---

## Feature: Add pay stub upload with local PDFBox line extraction and a review screen

**Description:**
Upload a stub PDF; read text locally; map lines to gross, contributions, benefits, taxes, net and match; check gross − deductions = net; flag low-confidence lines; YTD figures.

- PDF text: Apache PDFBox — pay stubs and receipts, read by text position.
- pay_stubs — id, income_source_id, pay_date, file_id, status (review | saved).
- pay_stub_lines — pay_stub_id, section (earnings | pre_tax | tax | net | employer), label, amount, ytd_amount, maps_to (gross | contribution | benefit | tax | net | match), maps_to_account_id, confidence.
- Pay stub review (design/pay-stub.png): preview of the PDF; every line read, this stub and YTD, grouped (earnings, before tax, taxes, paid to you/from employer), what each tracks as; check that gross − deductions = net; hard-to-read lines flagged.

PDF reading runs on the PC.

**Priority:** medium

**Ref:** stubs

**Depends On:** attachments, expenses, reports

---

## Feature: Persist per-employer stub layouts and update the income source from the latest stub

**Description:**
Remember the layout per employer; update the income source from the latest stub.

- stub_layouts — per employer: remembered positions/labels of each line so later stubs fill automatically.
- Income screen (design/income.png) shows taxes & benefits from stubs, pay stub list and drop zone.

**Priority:** medium

**Ref:** stub-layouts

**Depends On:** attachments, expenses, reports

---

## Feature: Add optional local OCR for scanned pay stubs via Tess4J

**Description:**
Optional local OCR for scanned stubs. OCR (optional, Phase 9): Tess4J (Tesseract), bundled and run locally — scanned stubs only. Text recognition runs on the PC.

**Priority:** low

**Ref:** ocr

**Depends On:** attachments, expenses, reports

---

## Feature: Display the dashboard

**Description:**
Dashboard per design/dashboard.png (lowest balance ahead, category donut, net worth timeline, attention list). Net worth, lowest balance ahead, saved & invested this year, total debt. Spending by category (donut, range switch), net worth over time (entries solid, projection dashed), "coming up & needs attention" list. Charts via Recharts (bundled), reading colors from data.

**Priority:** medium

**Ref:** dashboard

**Depends On:** stubs, stub-layouts, ocr

---

## Feature: Display annual costs and per-item price history

**Description:**
Annual costs with calendar / rolling 12 months, price history per item, budget-too-low hints.

- Annual totals per item and category for calendar years and rolling 12 months, plus % change.
- Annual costs (design/annual.png): calendar years / rolling 12 months switch; totals and change; per-item table; selected item's monthly history and whether its budget is too low; "Plan next year →".

**Priority:** medium

**Ref:** annual

**Depends On:** stubs, stub-layouts, ocr

---

## Feature: Surface the Projected vs. Actual view

**Description:**
Projected vs. Actual view. The original projected value (occurrence_overrides.projected_amount, saved at first edit) is kept in the database and shown on hover (desktop) / long-press (phone), and in the Projected vs. Actual report.

**Priority:** medium

**Ref:** proj-actual

**Depends On:** stubs, stub-layouts, ocr

---

## Feature: Add yearly plans with the six-step Plan next year wizard and suggestion bases

**Description:**
Plan years (draft/active), six-step wizard, suggestion bases, "YOURS" for typed amounts; first-time setup reuses the wizard.

- plan_years — id, year, status (draft | active | closed), basis (last_12 | last_12_trend | prior_year), created_at.
- plan_year_amounts — plan_year_id, item_id (or income_source_id), suggested_amount, chosen_amount, chosen_by (suggestion | user).
- Suggestions for next year, using the plan's basis: last_12: total of the last 12 full months; last_12_trend: last 12 months × (1 + year-over-year % change), rounded to the dollar; prior_year: the most recent full calendar year.
- Because planning normally happens in October, the default basis is last_12_trend and the window is the 12 months ending last month (e.g. Oct 2025 – Sep 2026).
- Fixed-amount items (loan payments) are suggested unchanged.
- Plan next year (design/plan-year.png): plans list (active, draft), Delete draft & start over; six steps (Income & benefits, Bills, Annual & save-ups, Spending budgets, Saving & investing, Review); suggestion basis switch; table of last 12 months, trend, suggestion, your amount ("YOURS" when typed), per-month/per-paycheck.

**Priority:** medium

**Ref:** plan-year

**Depends On:** dashboard, annual, proj-actual

---

## Feature: Gate draft deletion and schedule plan activation and the plan reminder

**Description:**
Delete draft & start over before it starts, activation on the budget year start date, plan reminder.

A draft plan can be deleted and redone any time before its start date. When the budget year starts, the draft becomes active and its amounts become new item versions effective that day. History is never rewritten. Settings: budget year start (month + day, default Jan 1), plan-next-year reminder date (default Oct 1). Deleting a draft plan year is one of the few actions that asks "are you sure?".

**Priority:** medium

**Ref:** plan-activation

**Depends On:** dashboard, annual, proj-actual

---

## Feature: Add the Debt & decisions planner

**Description:**
Planner with editable growth rates, option cards, payoff order (highest rate / smallest balance, with rollover), flags, and the "estimates, not advice" label.

Inputs: extra amount per month, comparison period, and the user's options. Rates for debts come from the accounts; growth rates for investments are editable inputs (prefilled from the account's assumed rate).
- Pay down a debt: amortize with the extra payment; report interest saved, new payoff date.
- Invest: future value of monthly contributions at the assumed rate; for a 401(k), include employer match on the portion under the match cap.
- Payoff order: highest rate first or smallest balance first; when a debt is paid off, its payment rolls into the next.
- Flags shown first: unclaimed employer match, promos ending.
- Always labeled as estimates for comparison, not advice.

Debt & decisions (design/planner.png): extra per month and period; flags first; option cards (debts with rates from accounts, investments with editable growth); payoff timeline with method switch; "estimates, not advice".

**Priority:** medium

**Ref:** planner

**Depends On:** plan-year, plan-activation

---
