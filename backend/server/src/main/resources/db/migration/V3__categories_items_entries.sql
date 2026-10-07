-- Phase 2: categories, tags, recurring items and one-off entries.
-- Money is whole cents (INTEGER); dates are local calendar dates as YYYY-MM-DD text.

-- One per item. A category with a parent is a sub-category; nesting stops at two levels.
CREATE TABLE categories (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    color      TEXT    NOT NULL,
    parent_id  INTEGER REFERENCES categories (id),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
) STRICT;

-- "Food" and "food" are the same category; the same name may repeat under different parents.
CREATE UNIQUE INDEX categories_name ON categories (COALESCE(parent_id, 0), name COLLATE NOCASE);

-- Any number per item, for building reports.
CREATE TABLE tags (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    color      TEXT    NOT NULL,
    created_at TEXT    NOT NULL
) STRICT;

CREATE UNIQUE INDEX tags_name ON tags (name COLLATE NOCASE);

-- A recurring item: what never changes over its life. Everything a "this and all future"
-- edit can change lives in item_versions.
CREATE TABLE items (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    -- bucket_move arrives with buckets (Phase 3); it is listed now so the table needs no rebuild.
    kind       TEXT    NOT NULL
               CHECK (kind IN ('bill', 'transfer', 'budget', 'bucket_move', 'card_payment')),
    note       TEXT,
    archived   INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    created_at TEXT    NOT NULL
) STRICT;

-- One row per "this and all future" edit, in force from effective_from until the next row's.
-- The earliest row also covers every date before it. Past occurrences keep the version they had.
CREATE TABLE item_versions (
    id                  INTEGER PRIMARY KEY,
    item_id             INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    effective_from      TEXT    NOT NULL,
    amount              INTEGER NOT NULL,
    -- 'annual': amount is a yearly total, shared out over the schedule's occurrences.
    amount_mode         TEXT    NOT NULL DEFAULT 'per_occurrence'
                        CHECK (amount_mode IN ('per_occurrence', 'annual')),
    is_estimate         INTEGER NOT NULL DEFAULT 0 CHECK (is_estimate IN (0, 1)),
    category_id         INTEGER REFERENCES categories (id),
    -- Any account, including a credit card (the charge then lands on the card).
    pay_from_account_id INTEGER NOT NULL REFERENCES accounts (id),
    -- Transfers and card payments only.
    pay_to_account_id   INTEGER REFERENCES accounts (id),
    autopay             INTEGER NOT NULL DEFAULT 0 CHECK (autopay IN (0, 1)),
    placement           TEXT    NOT NULL DEFAULT 'due_period'
                        CHECK (placement IN ('due_period', 'one_early')),
    schedule_rule_id    INTEGER NOT NULL REFERENCES schedule_rules (id),
    -- For an every_pay_period schedule: how many days into the period it is dated.
    -- 0 is payday itself. This is how each budget carries its own date.
    days_after_payday   INTEGER NOT NULL DEFAULT 0 CHECK (days_after_payday BETWEEN 0 AND 60),
    UNIQUE (item_id, effective_from)
) STRICT;

CREATE TABLE item_tags (
    item_id INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tags (id),
    PRIMARY KEY (item_id, tag_id)
) STRICT, WITHOUT ROWID;

-- "Just this one" edits. Occurrences are worked out on the fly; only what the user changed
-- is stored. Keyed by the date the schedule names, which stays put when a weekend or
-- holiday moves the actual day. A NULL column means "not changed".
CREATE TABLE occurrence_overrides (
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    original_due_date TEXT    NOT NULL,
    amount            INTEGER,
    due_date          TEXT,
    -- First day of the pay period the user moved this occurrence into.
    period_override   TEXT,
    paid              INTEGER NOT NULL DEFAULT 0 CHECK (paid IN (0, 1)),
    paid_date         TEXT,
    confirmation_code TEXT,
    note              TEXT,
    skipped           INTEGER NOT NULL DEFAULT 0 CHECK (skipped IN (0, 1)),
    -- What the app showed before the amount was first typed over.
    projected_amount  INTEGER,
    PRIMARY KEY (item_id, original_due_date)
) STRICT, WITHOUT ROWID;

-- One-off money in, money out and transfers. A charge on a card is an entry whose account
-- is that card.
CREATE TABLE entries (
    id                   INTEGER PRIMARY KEY,
    kind                 TEXT    NOT NULL CHECK (kind IN ('money_out', 'money_in', 'transfer')),
    date                 TEXT    NOT NULL,
    amount               INTEGER NOT NULL,
    is_estimate          INTEGER NOT NULL DEFAULT 0 CHECK (is_estimate IN (0, 1)),
    description          TEXT    NOT NULL,
    payee                TEXT,
    category_id          INTEGER REFERENCES categories (id),
    account_id           INTEGER NOT NULL REFERENCES accounts (id),
    -- Transfers only.
    to_account_id        INTEGER REFERENCES accounts (id),
    note                 TEXT,
    -- Used from Phase 8 (expenses and reimbursements).
    reimbursement_status TEXT    NOT NULL DEFAULT 'none'
                         CHECK (reimbursement_status IN ('none', 'to_submit', 'submitted', 'reimbursed')),
    reimburses_entry_id  INTEGER REFERENCES entries (id),
    created_at           TEXT    NOT NULL
) STRICT;

CREATE INDEX entries_date ON entries (date);

CREATE TABLE entry_tags (
    entry_id INTEGER NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    tag_id   INTEGER NOT NULL REFERENCES tags (id),
    PRIMARY KEY (entry_id, tag_id)
) STRICT, WITHOUT ROWID;
