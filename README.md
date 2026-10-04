# Budget

A private, local-only desktop budgeting app for Windows, organised around **pay periods**.
All data stays on your PC: no cloud, no telemetry, no update checks, no outbound network calls.

- `spec.md` — the build plan and source of truth
- `proposals.md` — the features being tracked
- `design/` — mockups for every screen (sample data only)

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

## Build the installer

Additionally needs the Rust toolchain and the Visual Studio C++ Build Tools.

```powershell
.\build-installer.ps1
```

Produces one per-user NSIS installer (no admin rights) under
`desktop\src-tauri\target\release\bundle\nsis\`, bundling a trimmed Java runtime, the backend
and the UI. Commit `desktop/src-tauri/Cargo.lock` after the first build.

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
| POST | `/api/shutdown` | open to the shell only: loopback + `X-Budget-Shutdown-Token` |

## Data and privacy

- Data folder: `%APPDATA%\Budget` — `budget.db`, `logs/`, `backups/`. Uninstalling keeps it unless
  you tick "Delete the application data".
- The session token lives in an HttpOnly, SameSite=Strict cookie and in memory only.
- A strict Content-Security-Policy blocks every external origin; requests with an unrecognised
  `Host` header are refused (protection against DNS rebinding).
- For protection at rest, turn on Windows BitLocker.
