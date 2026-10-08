# Budget

A private, local-only desktop budgeting app for Windows, organised around **pay periods**.
All data stays on your PC: no cloud, no telemetry, no update checks, no outbound network calls.

- `spec.md` — the build plan and source of truth
- `proposals.md` — the features being tracked
- `design/` — mockups for every screen (sample data only)
- `.github/workflows/ci.yml` — tests and dependency checks on every branch push; the installer on `main`

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
| Ctrl+E / Ctrl+I | add money out / money in |
| Ctrl+Z | undo the last change (left alone while typing in a field) |
| ← / → | previous / next pay period, on the home screen |
| Enter / Esc / Tab | while typing over an amount: save / cancel / save and move on |

## Build the installer

Additionally needs the Rust toolchain and the Visual Studio C++ Build Tools.

```powershell
.\build-installer.ps1
```

Produces one per-user NSIS installer (no admin rights) under
`desktop\src-tauri\target\release\bundle\nsis\`, bundling a trimmed Java runtime, the backend
and the UI. Before bundling, the script starts the packaged backend once and stops the build if
it does not come up and serve the UI.

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

1. The Tauri shell starts the bundled backend, passing its own process id, the data folder and
   a one-time shutdown token.
2. The backend takes a lock on the data folder (one instance only), backs up the database if an
   upgrade is pending, runs Flyway migrations, binds `127.0.0.1` on port 4873 (or the next free
   one) and prints `READY port=NNNN`.
3. The shell opens its window on that address. The backend serves both the API and the UI, so
   there is a single origin and no CORS.
4. Closing the window sends `POST /api/shutdown`; the backend also exits by itself if the shell dies.

## API

Every response is `{ data, error, status }`. Every route needs a session cookie except the three marked open.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | open |
| POST | `/api/session` | open; unlock. From this PC only until the PIN arrives (Phase 6) |
| GET | `/api/session` | session status |
| DELETE | `/api/session` | lock |
| GET | `/api/settings` | all settings with effective values |
| PUT | `/api/settings/{key}` | body `{ "value": "…" }`; validated against fixed choices |
| GET, POST | `/api/accounts` | list; add `{ name, type, color, institution?, subtypeLabel? }` |
| GET, PUT, DELETE | `/api/accounts/{id}` | one account with its balances; change; delete (refused while income, an item or an entry uses it) |
| POST | `/api/accounts/{id}/balances` | enter a balance `{ date, amountCents, note? }`; the same day again replaces it |
| DELETE | `/api/accounts/{id}/balances/{entryId}` | remove one entry |
| GET, POST | `/api/income-sources` | list; add `{ name, depositAccountId, netCents, grossCents?, netIsEstimate?, primary?, schedule, deductions? }` |
| PUT, DELETE | `/api/income-sources/{id}` | change; delete (the primary one only when it is the last) |
| PUT | `/api/income-sources/{id}/paychecks/{scheduledDate}` | type over one paycheck `{ netCents }` |
| GET | `/api/paychecks` | paychecks, today to the horizon; optional `from`, `to` |
| GET | `/api/pay-periods` | pay periods from the primary source, each with its projected ending balance; optional `from`, `to` |
| GET | `/api/pay-periods/{start}` | everything in the period that opens on `start`: paychecks, recurring items, one-offs, and each account's starting, in, out, ending and lowest balance |
| GET, POST | `/api/categories` | list with item counts and yearly totals; add `{ name, color, parentId? }` |
| PUT, DELETE | `/api/categories/{id}` | change; delete (refused while in use or while it has sub-categories) |
| GET, POST | `/api/tags` | list; add `{ name, color }` |
| PUT, DELETE | `/api/tags/{id}` | change; delete (refused while on an item) |
| GET, POST | `/api/items` | recurring items; add `{ name, kind, amountCents, payFromAccountId, schedule, payToAccountId?, amountMode?, estimate?, categoryId?, autopay?, placement?, daysAfterPayday?, tagIds?, note? }` |
| GET, PUT, DELETE | `/api/items/{id}` | one item; change from `effectiveFrom` on (default today; earlier dates keep what they had); delete |
| PUT | `/api/items/{id}/occurrences/{scheduledDate}` | "just this one": any of `{ amountCents, dueDate, periodStart, paid, paidDate, confirmationCode, note, skipped }` |
| GET, POST | `/api/entries` | one-off money in, money out and transfers; optional `from`, `to`; add `{ kind, date, amountCents, description, accountId, toAccountId?, estimate?, payee?, categoryId?, tagIds?, note? }` |
| PUT, DELETE | `/api/entries/{id}` | change; delete |
| GET, POST | `/api/holidays` | bank holidays (US ones filled in locally); add your own `{ date, name }` |
| PUT, DELETE | `/api/holidays/{id}` | change or switch off `{ date, name, enabled }`; remove |
| GET | `/api/undo` | `{ available }`: whether there is a change to undo |
| POST | `/api/undo` | undo the most recent change; `{ undone, available }` |
| DELETE | `/api/data` | erase all data; this PC only; body `{ "confirm": "erase-all-data" }` |
| POST | `/api/shutdown` | open to the shell only: loopback + `X-Budget-Shutdown-Token` |

Every request that changes something is recorded so it can be undone, and its answer carries the header
`X-Budget-Changed: true` (the UI shows its Undo toast when it sees it). Such requests are handled one at a time.

Amounts are whole cents (`amountCents`, `netCents`); dates are `YYYY-MM-DD`. Item kinds are `bill`, `transfer`,
`budget` and `card_payment`; item schedules are `every_pay_period`, `weekly`, `biweekly`, `semi_monthly`, `monthly`,
`every_n_months`, `yearly` and `one_time`. A balance you enter stands for the start of that day (see `spec.md` §5.3). Settings keys: `theme`,
`accent_color`, `projection_horizon_months` (6, 12, 18, 24 or 36).

## CI and dependency checks

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to a branch:

- **Backend tests** on Windows, and the **frontend** typecheck, tests and build.
- **Dependency checks:** OSV-Scanner fails the build on any known vulnerability in a Maven, npm or
  Cargo dependency, and `check-dependency-age.ps1` fails it if a dependency version is less than
  14 days old. Both also run weekly.
- On `main` only the **installer** is built (kept as a workflow artifact for 30 days). The checks above
  are not repeated there, since the branch already passed them; `build-installer.ps1` runs the
  tests itself before packaging, so the merged code is still tested once. To save time, the Tauri
  command-line tool and Cargo's package downloads are kept between runs; the app's own compiled
  output is not, so what ships is always compiled from clean.

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

## Data and privacy

- Data folder: `%APPDATA%\Budget` — `budget.db`, `logs/`, `backups/`, `files/`. Uninstalling keeps
  it unless you tick "Delete the application data".
- **Settings → Erase all data** deletes the database files outright and rebuilds an empty one, and
  deletes `files/` and `backups/`, so nothing is left behind on disk. Logs stay (they hold no
  financial data). It cannot be undone.
- The session token lives in an HttpOnly, SameSite=Strict cookie and in memory only.
- A strict Content-Security-Policy blocks every external origin; requests with an unrecognised
  `Host` header are refused (protection against DNS rebinding).
- For protection at rest, turn on Windows BitLocker.
