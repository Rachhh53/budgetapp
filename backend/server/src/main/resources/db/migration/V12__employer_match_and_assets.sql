-- Phase 4: employer match, and homes, vehicles and other assets with the values entered for them.
-- Money is whole cents (INTEGER); rates are basis points; dates are YYYY-MM-DD text.

-- What an employer adds to a retirement account with each paycheck. One per income source:
-- either the same amount every time, or a share of what the employee puts into that account,
-- up to a share of gross pay ("50% of what you contribute, up to 6% of your pay").
CREATE TABLE employer_match (
    income_source_id INTEGER PRIMARY KEY REFERENCES income_sources (id) ON DELETE CASCADE,
    to_account_id    INTEGER NOT NULL REFERENCES accounts (id),
    mode             TEXT    NOT NULL CHECK (mode IN ('fixed', 'formula')),
    fixed_amount     INTEGER CHECK (fixed_amount IS NULL OR fixed_amount >= 0),
    match_bp         INTEGER CHECK (match_bp IS NULL OR match_bp BETWEEN 0 AND 100000),
    cap_bp_of_gross  INTEGER CHECK (cap_bp_of_gross IS NULL OR cap_bp_of_gross BETWEEN 0 AND 10000),
    -- Each mode has its own terms, and only those.
    CHECK ((mode = 'fixed') = (fixed_amount IS NOT NULL)),
    CHECK ((mode = 'formula') = (match_bp IS NOT NULL AND cap_bp_of_gross IS NOT NULL))
) STRICT;

-- Something owned that is not an account: a home, a vehicle, anything else of value.
CREATE TABLE assets (
    id                     INTEGER PRIMARY KEY,
    name                   TEXT    NOT NULL,
    kind                   TEXT    NOT NULL CHECK (kind IN ('home', 'vehicle', 'other')),
    color                  TEXT    NOT NULL,
    -- The yearly rate the user assumes it grows by between the values they enter. NULL = it
    -- holds its value. Never negative: a value only goes down when a lower one is entered.
    appreciation_bp        INTEGER CHECK (appreciation_bp IS NULL OR appreciation_bp BETWEEN 0 AND 100000),
    -- The loan against it (a mortgage, a car loan), for equity. Removing that account only unlinks it.
    linked_loan_account_id INTEGER REFERENCES accounts (id) ON DELETE SET NULL,
    sort_order             INTEGER NOT NULL DEFAULT 0,
    archived               INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    created_at             TEXT    NOT NULL
) STRICT;

-- A value the user entered: "on this day it was worth X". One per asset per day, so
-- correcting a typo replaces the entry, as with account balances.
CREATE TABLE asset_values (
    id       INTEGER PRIMARY KEY,
    asset_id INTEGER NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    date     TEXT    NOT NULL,
    value    INTEGER NOT NULL CHECK (value >= 0),
    UNIQUE (asset_id, date)
) STRICT;
