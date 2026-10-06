# CLAUDE.md

Senior engineer. No hand-holding — ship clean code.

## Hard Rules — No Exceptions
- **NEVER run destructive actions without confirming first** — this includes `docker compose down -v`, dropping databases, deleting volumes, force-pushing, `rm -rf`, or any action that destroys data or is hard to reverse
- No secrets in code/git/logs; `.env` in `.gitignore`
- No raw SQL; parameterized queries always
- No root user in containers; pin all Docker versions
- No wildcard CORS; no JavaScript; no PII in logs/URLs
- No hardcoded config — everything via env vars
- Security and data privacy are first-class, never deferred
- Never hardcode localhost - make code ready to deploy
- **Never edit a migration file in `db/migration/` outside the branch where it was created, and never after it has been merged to main.** Flyway checksums are immutable. Fix a migration only on its own branch before merge; after merge, always create a new versioned migration (next `V` number) instead.
- Never edit proposals.md. These are the features we are actually tracking. When one is implemented or needs a comment, tell Rachel which item (by `Ref`) and what to note — she updates the feature tracker herself

## Stack
* Frontend: React + TypeScript + Tailwind (Vite). All fonts, icons and libraries bundled locally — no CDNs.
* Backend: Java 21 + Javalin (lightweight; no Spring). Maven multi-module: `engine` (pure calculations, no I/O) and `server` (API, database, files).
* Database: SQLite (single local file in %APPDATA%) + Flyway migrations (`db/migration/V{n}__name.sql`). Never edit a committed migration.
* Money: integer cents (`long`, wrapped in a `Money` type); rates in basis points; `BigDecimal` with HALF_EVEN for interest/growth math. Never `double`.
* Auth: app PIN (Argon2 hash) unlocks a session; opaque session token in an HttpOnly cookie, expires with auto-lock. Failed PIN attempts are rate-limited with increasing delays. Recovery code + local-only `--reset-pin`.
* API: REST with `{ data, error, status }` envelope. Backend binds to 127.0.0.1 only, unless phone access is turned on in Settings.
* Desktop: Tauri 2 shell (tiny Rust wrapper that starts/stops the Java backend; no business logic in Rust). Shipped as one per-user Windows installer with a jlink-trimmed Java runtime bundled.
* Dev: no Docker. Run the backend with the Maven wrapper (`backend/mvnw.cmd`) and the frontend with npm (`npm run dev` forwards `/api` to the backend) directly on Windows. The installer is built on Windows with `build-installer.ps1`.
* Privacy: no telemetry, analytics, crash reporting, update checks, or outbound network calls of any kind.

## Communication Style
- Be concise — no verbose explanations or restating the task back
- Ask clarifying questions only if something is genuinely ambiguous and would cause rework; don't ask for information you can reasonably infer
- Plans and todo lists must fit on one screen

## Code & Design
- TypeScript strict mode; Python type hints; meaningful comments explaining *why*
- **Design is woman/minority first** — diverse names, she/they/them pronouns, warm palettes, WCAG AA
- **Mobile-first, always.** Every new UI (page, card, modal, table, matrix) must render legibly at 375px-wide before being considered done. Wide tables/matrices need a stacked-card fallback below `md`; modals must avoid fixed widths; buttons and form rows must reflow. Default to Tailwind responsive prefixes (`sm:` / `md:` / `lg:`) and verify by resizing the browser before declaring a feature complete. Mobile is not a follow-up task.
- Error messages human and kind; never blame user; all empty/loading/error states need copy
- Tests by default: pytest (Python), JUnit 5 (Java), Vitest (React)
- Run npm, TypeScript and Maven commands directly on the host — never start Docker
- At minimum, cover the happy path and common error cases for every endpoint
- Include at least one test per endpoint verifying auth is enforced (unauthenticated request returns 401)
- Tests live in a `tests/` directory mirroring the source structure
- Backend tests must use a test database or in-memory equivalent — never the dev DB
- Run verification checklist in `spec.md` before calling "done"

## Workflow
- Create `spec.md` before any code; read it at session start
- One branch per milestone: `milestone/1-auth`, etc. Squash merge to main
- Update spec.md, README.md, .env.example after every change
- Input validation on every endpoint — Pydantic (Python) or @Valid (Java)
- Todo lists are bullet points only, no explanations; fit on one screen
- Never return raw DB errors to client — log internally, return generic message
- Ask to confirm a milestone is complete before moving on

## Token Efficiency

- Do not re-read files that haven't changed since the last task
- Trust the current context window — don't re-summarize or restate what was just done
- Don't scan the entire codebase to answer a focused question; read only files directly relevant to the task

## What Matters Most
- Parameterized queries; no secrets; no root user; no PII in logs
- Woman/minority-first design
- Comprehensive comments
- Tests on every endpoint
- spec.md as source of truth
- Don't implement hacky solutions - professional code only