-- Phase 3: buckets, what the user moves in and out of them, savings interest and rate history.
-- Money is whole cents (INTEGER); rates are basis points; dates are YYYY-MM-DD text.

-- Money set aside inside a checking or savings account. A bucket never changes the account's
-- balance; it lowers what is "available". The app only lets checking and savings have them.
CREATE TABLE buckets (
    id          INTEGER PRIMARY KEY,
    account_id  INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL,
    color       TEXT    NOT NULL,
    -- NULL = no goal; the bucket simply holds what is put in it.
    goal_amount INTEGER CHECK (goal_amount IS NULL OR goal_amount > 0),
    note        TEXT,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    archived    INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    -- The day the bucket was added. Recurring moves, save-ups and interest only reach a
    -- bucket from this day on, so adding one never back-fills it from old schedules.
    opened_on   TEXT    NOT NULL,
    created_at  TEXT    NOT NULL
) STRICT;

CREATE UNIQUE INDEX buckets_name ON buckets (account_id, name COLLATE NOCASE);

-- Money the user put into (+) or took out of (−) a bucket by hand. Everything else that
-- moves a bucket (recurring moves, save-ups, splits, interest) is worked out on the fly,
-- like occurrences are; the other sources are listed so they can be stored later without
-- rebuilding the table.
CREATE TABLE bucket_changes (
    id        INTEGER PRIMARY KEY,
    bucket_id INTEGER NOT NULL REFERENCES buckets (id) ON DELETE CASCADE,
    date      TEXT    NOT NULL,
    amount    INTEGER NOT NULL CHECK (amount <> 0),
    source    TEXT    NOT NULL DEFAULT 'manual'
              CHECK (source IN ('manual', 'transfer', 'bucket_move', 'interest', 'income_split')),
    note      TEXT
) STRICT;

CREATE INDEX bucket_changes_bucket ON bucket_changes (bucket_id, date);

-- Part of a one-off set aside in a bucket: "put $500 of this bonus into Holiday gifts".
-- For money in, the bucket is in the entry's account; for a transfer, in the account it goes to.
CREATE TABLE entry_bucket_splits (
    entry_id  INTEGER NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    bucket_id INTEGER NOT NULL REFERENCES buckets (id) ON DELETE CASCADE,
    amount    INTEGER NOT NULL CHECK (amount > 0),
    PRIMARY KEY (entry_id, bucket_id)
) STRICT, WITHOUT ROWID;

-- When an account's interest is credited and where it goes. No row = no interest is projected.
CREATE TABLE interest_settings (
    account_id            INTEGER PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    -- Day of the month, 1-31. 31 means "last day", as in schedule_rules.
    credit_day            INTEGER NOT NULL DEFAULT 31 CHECK (credit_day BETWEEN 1 AND 31),
    destination           TEXT    NOT NULL DEFAULT 'unassigned'
                          CHECK (destination IN ('unassigned', 'bucket', 'proportional')),
    destination_bucket_id INTEGER REFERENCES buckets (id),
    CHECK ((destination = 'bucket') = (destination_bucket_id IS NOT NULL))
) STRICT;

-- A rate applies from its date until the next one of the same kind.
CREATE TABLE rate_history (
    id             INTEGER PRIMARY KEY,
    account_id     INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    kind           TEXT    NOT NULL CHECK (kind IN ('apy', 'growth', 'apr', 'loan_rate')),
    rate_bp        INTEGER NOT NULL CHECK (rate_bp BETWEEN 0 AND 100000),
    effective_date TEXT    NOT NULL,
    UNIQUE (account_id, kind, effective_date)
) STRICT;

-- A bucket move, or a transfer, puts its money in this bucket.
ALTER TABLE item_versions ADD COLUMN bucket_id INTEGER REFERENCES buckets (id);

-- A bill saved up for: a share is set aside in this bucket every paycheck, so the money is
-- there by the due date, and it leaves the bucket when the bill is paid.
ALTER TABLE item_versions ADD COLUMN save_up_bucket_id INTEGER REFERENCES buckets (id);
