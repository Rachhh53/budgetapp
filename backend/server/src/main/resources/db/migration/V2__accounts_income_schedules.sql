-- Phase 1: accounts, balances the user entered, schedules, holidays and income.
-- Money is whole cents (INTEGER); dates are local calendar dates as YYYY-MM-DD text.

CREATE TABLE accounts (
    id                    INTEGER PRIMARY KEY,
    name                  TEXT    NOT NULL,
    institution           TEXT,
    type                  TEXT    NOT NULL
                          CHECK (type IN ('checking', 'savings', 'credit_card', 'investment', 'loan')),
    -- Free text the user chose, e.g. "401(k)", "Brokerage", "HSA", "Mortgage".
    subtype_label         TEXT,
    color                 TEXT    NOT NULL,
    -- NULL = the user has not asked for a low-balance warning on this account.
    low_balance_threshold INTEGER,
    sort_order            INTEGER NOT NULL DEFAULT 0,
    archived              INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    created_at            TEXT    NOT NULL
) STRICT;

-- A balance the user typed in: "the real number on this day is X". Projections roll forward
-- from the latest one. One per account per day, so correcting a typo replaces the entry.
CREATE TABLE balance_entries (
    id         INTEGER PRIMARY KEY,
    account_id INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    date       TEXT    NOT NULL,
    amount     INTEGER NOT NULL,
    note       TEXT,
    UNIQUE (account_id, date)
) STRICT;

CREATE TABLE schedule_rules (
    id           INTEGER PRIMARY KEY,
    frequency    TEXT    NOT NULL
                 CHECK (frequency IN ('every_pay_period', 'weekly', 'biweekly', 'semi_monthly', 'monthly',
                                      'every_n_months', 'yearly', 'one_time', 'manual_dates')),
    interval     INTEGER NOT NULL DEFAULT 1 CHECK (interval >= 1),
    -- Days of the month, 1-31. 31 means "last day": shorter months fall to their last day.
    day_1        INTEGER CHECK (day_1 BETWEEN 1 AND 31),
    day_2        INTEGER CHECK (day_2 BETWEEN 1 AND 31),
    month        INTEGER CHECK (month BETWEEN 1 AND 12),
    start_date   TEXT    NOT NULL,
    end_date     TEXT,
    weekend_rule TEXT    NOT NULL DEFAULT 'none'
                 CHECK (weekend_rule IN ('none', 'previous_business_day', 'next_business_day'))
) STRICT;

-- The hand-picked dates of a 'manual_dates' rule.
CREATE TABLE manual_dates (
    schedule_rule_id INTEGER NOT NULL REFERENCES schedule_rules (id) ON DELETE CASCADE,
    date             TEXT    NOT NULL,
    PRIMARY KEY (schedule_rule_id, date)
) STRICT, WITHOUT ROWID;

-- Days banks are closed. 'us_bank' rows are worked out locally and added a year at a time;
-- the user can switch any of them off, change them, or add their own.
CREATE TABLE holidays (
    id      INTEGER PRIMARY KEY,
    date    TEXT    NOT NULL UNIQUE,
    name    TEXT    NOT NULL,
    source  TEXT    NOT NULL CHECK (source IN ('us_bank', 'user')),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1))
) STRICT;

-- Years whose bank holidays have already been added, so one the user removed or moved
-- is never put back.
CREATE TABLE holiday_years_seeded (
    year INTEGER NOT NULL PRIMARY KEY
) STRICT, WITHOUT ROWID;

CREATE TABLE income_sources (
    id                 INTEGER PRIMARY KEY,
    name               TEXT    NOT NULL,
    deposit_account_id INTEGER NOT NULL REFERENCES accounts (id),
    -- NULL when the user only tracks take-home pay (e.g. a second job with varying hours).
    gross_amount       INTEGER,
    net_amount         INTEGER NOT NULL,
    net_is_estimate    INTEGER NOT NULL DEFAULT 0 CHECK (net_is_estimate IN (0, 1)),
    schedule_rule_id   INTEGER NOT NULL REFERENCES schedule_rules (id),
    is_primary         INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
    created_at         TEXT    NOT NULL
) STRICT;

-- The primary source's schedule defines the pay periods, so there can only be one.
CREATE UNIQUE INDEX income_sources_one_primary ON income_sources (is_primary) WHERE is_primary = 1;

-- Money taken out of a paycheck before it reaches the deposit account (401(k), HSA, …).
-- to_account_id is NULL when it is not tracked as one of the user's accounts.
CREATE TABLE paycheck_deductions (
    id               INTEGER PRIMARY KEY,
    income_source_id INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    name             TEXT    NOT NULL,
    amount           INTEGER NOT NULL,
    to_account_id    INTEGER REFERENCES accounts (id),
    sort_order       INTEGER NOT NULL DEFAULT 0
) STRICT;

-- A single paycheck whose amount the user typed over ("just this one"). Keyed by the date
-- the schedule names, which stays put when weekends or holidays move the actual payday.
-- projected_amount keeps what the app showed before the first edit.
CREATE TABLE paycheck_overrides (
    income_source_id INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    scheduled_date   TEXT    NOT NULL,
    net_amount       INTEGER NOT NULL,
    projected_amount INTEGER NOT NULL,
    PRIMARY KEY (income_source_id, scheduled_date)
) STRICT, WITHOUT ROWID;
