-- One-off paychecks: a bonus (or any extra pay) from an income source that arrives once, on its
-- own day, with the same figures as a paycheck — gross pay, what is taken out, take-home, and the
-- employer's match — but no place in the source's schedule (spec §4.3).

-- A pay stub can now be saved as one of these (`applied = 'one_off'`). SQLite cannot widen a
-- CHECK in place, so pay_stubs is rebuilt with the same columns, in the same order, and its rows
-- copied across unchanged.
--
-- pay_stub_lines refers to pay_stubs with ON DELETE CASCADE, and foreign keys are enforced here:
-- dropping pay_stubs while the lines still point at it would delete every line. So the lines are
-- rebuilt alongside, pointing at the new table, and the old lines table goes first. Renaming the
-- new tables to the old names then carries the reference along (SQLite rewrites REFERENCES
-- clauses on a rename). The column names are unchanged, so everything recorded for Undo still
-- fits; the Undo triggers are rebuilt from the schema when the database is next opened.
CREATE TABLE pay_stubs_rebuilt (
    id               INTEGER PRIMARY KEY,
    income_source_id INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    file_id          INTEGER NOT NULL REFERENCES files (id),
    name             TEXT    NOT NULL,
    pay_date         TEXT,
    status           TEXT    NOT NULL CHECK (status IN ('review', 'saved')),
    applied          TEXT    CHECK (applied IN ('correct', 'pay_change', 'paycheck', 'one_off', 'none')),
    created_at       TEXT    NOT NULL,
    CHECK ((status = 'saved') = (applied IS NOT NULL)),
    CHECK (status = 'review' OR pay_date IS NOT NULL)
) STRICT;

INSERT INTO pay_stubs_rebuilt (id, income_source_id, file_id, name, pay_date, status, applied, created_at)
SELECT id, income_source_id, file_id, name, pay_date, status, applied, created_at FROM pay_stubs;

CREATE TABLE pay_stub_lines_rebuilt (
    id                 INTEGER PRIMARY KEY,
    pay_stub_id        INTEGER NOT NULL REFERENCES pay_stubs_rebuilt (id) ON DELETE CASCADE,
    position           INTEGER NOT NULL,
    section            TEXT    NOT NULL CHECK (section IN ('earnings', 'pre_tax', 'tax', 'after_tax', 'net', 'employer')),
    label              TEXT    NOT NULL,
    amount             INTEGER NOT NULL,
    ytd_amount         INTEGER,
    maps_to            TEXT    NOT NULL CHECK (maps_to IN ('gross', 'contribution', 'benefit', 'tax', 'net', 'match', 'ignore')),
    maps_to_account_id INTEGER REFERENCES accounts (id) ON DELETE SET NULL,
    sure               INTEGER NOT NULL DEFAULT 1 CHECK (sure IN (0, 1))
) STRICT;

INSERT INTO pay_stub_lines_rebuilt
    (id, pay_stub_id, position, section, label, amount, ytd_amount, maps_to, maps_to_account_id, sure)
SELECT id, pay_stub_id, position, section, label, amount, ytd_amount, maps_to, maps_to_account_id, sure FROM pay_stub_lines;

-- The lines first: nothing then points at pay_stubs when it goes.
DROP TABLE pay_stub_lines;
DROP TABLE pay_stubs;
ALTER TABLE pay_stubs_rebuilt RENAME TO pay_stubs;
ALTER TABLE pay_stub_lines_rebuilt RENAME TO pay_stub_lines;

CREATE INDEX pay_stubs_source ON pay_stubs (income_source_id, pay_date);
CREATE INDEX pay_stubs_file ON pay_stubs (file_id);
CREATE INDEX pay_stub_lines_stub ON pay_stub_lines (pay_stub_id, position);

-- One extra paycheck. It lands in the source's deposit account on pay_date, which is the day it
-- is really paid (no weekend move: it is not on a schedule). Its take-home is what was paid, so it
-- is never an estimate. `matched` = 1 adds the employer's match by the source's own rule as it
-- stands on that day, worked out on this paycheck's gross and what it puts in; 0 leaves it out.
-- pay_stub_id is the stub it was saved from, so saving that stub again changes this paycheck
-- rather than adding a second; removing the stub leaves the paycheck.
CREATE TABLE one_off_paychecks (
    id               INTEGER PRIMARY KEY,
    income_source_id INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    pay_date         TEXT    NOT NULL,
    name             TEXT    NOT NULL,
    gross_amount     INTEGER CHECK (gross_amount IS NULL OR gross_amount >= 0),
    net_amount       INTEGER NOT NULL CHECK (net_amount >= 0),
    matched          INTEGER NOT NULL DEFAULT 1 CHECK (matched IN (0, 1)),
    pay_stub_id      INTEGER UNIQUE REFERENCES pay_stubs (id) ON DELETE SET NULL,
    created_at       TEXT    NOT NULL
) STRICT;

CREATE INDEX one_off_paychecks_source ON one_off_paychecks (income_source_id, pay_date);

-- What a one-off paycheck takes out, as paycheck_deductions are for the regular ones: a dollar
-- amount, or a share of its gross pay (percent_bp, with amount worked out from it).
CREATE TABLE one_off_paycheck_deductions (
    id                  INTEGER PRIMARY KEY,
    one_off_paycheck_id INTEGER NOT NULL REFERENCES one_off_paychecks (id) ON DELETE CASCADE,
    name                TEXT    NOT NULL,
    amount              INTEGER NOT NULL CHECK (amount >= 0),
    to_account_id       INTEGER REFERENCES accounts (id),
    percent_bp          INTEGER CHECK (percent_bp IS NULL OR percent_bp BETWEEN 0 AND 10000),
    sort_order          INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE INDEX one_off_paycheck_deductions_paycheck ON one_off_paycheck_deductions (one_off_paycheck_id, sort_order);
