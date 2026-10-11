-- Phase 5: how each credit card bills and is paid, its statements and promotional rates, and
-- the terms of each loan. Money is whole cents (INTEGER); rates are basis points; dates are
-- YYYY-MM-DD text. A card's regular APR and a loan's rate live in rate_history ('apr', 'loan_rate').

-- One per credit card. No row = nothing is known about the card beyond its balance, so no
-- statement, interest or payment is worked out for it.
CREATE TABLE card_settings (
    account_id           INTEGER PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    -- Days of the month, 1-31. 31 means "last day", as in schedule_rules.
    statement_close_day  INTEGER NOT NULL CHECK (statement_close_day BETWEEN 1 AND 31),
    due_day              INTEGER NOT NULL CHECK (due_day BETWEEN 1 AND 31),
    payment_mode         TEXT    NOT NULL
                         CHECK (payment_mode IN ('full_balance', 'statement_balance', 'minimum', 'fixed')),
    fixed_payment_amount INTEGER CHECK (fixed_payment_amount IS NULL OR fixed_payment_amount >= 0),
    autopay              INTEGER NOT NULL DEFAULT 0 CHECK (autopay IN (0, 1)),
    -- The account the payment comes out of. NULL = no payment is scheduled from these settings.
    pay_from_account_id  INTEGER REFERENCES accounts (id) ON DELETE SET NULL,
    -- The recurring item that is this card's own payment, kept in step with these settings.
    -- Deleting that item leaves the card with no scheduled payment.
    payment_item_id      INTEGER REFERENCES items (id) ON DELETE SET NULL,
    -- For a card whose charges are not entered one by one: what a statement month's spending
    -- is expected to come to. NULL = charges are entered as they happen.
    monthly_estimate     INTEGER CHECK (monthly_estimate IS NULL OR monthly_estimate >= 0),
    CHECK ((payment_mode = 'fixed') = (fixed_payment_amount IS NOT NULL))
) STRICT;

-- A statement as it arrived: the real numbers, which take over from the estimate.
-- One per card per closing date, so correcting a typo replaces the entry.
CREATE TABLE card_statements (
    id                INTEGER PRIMARY KEY,
    account_id        INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    close_date        TEXT    NOT NULL,
    statement_balance INTEGER NOT NULL,
    minimum_due       INTEGER CHECK (minimum_due IS NULL OR minimum_due >= 0),
    due_date          TEXT    NOT NULL,
    UNIQUE (account_id, close_date)
) STRICT;

-- A promotional rate on part of a card's balance: 0% on a balance transfer until a date, say.
CREATE TABLE card_promos (
    id                INTEGER PRIMARY KEY,
    account_id        INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    label             TEXT    NOT NULL,
    kind              TEXT    NOT NULL CHECK (kind IN ('balance_transfer', 'purchase', 'other')),
    rate_bp           INTEGER NOT NULL CHECK (rate_bp BETWEEN 0 AND 100000),
    start_date        TEXT    NOT NULL,
    end_date          TEXT    NOT NULL,
    original_balance  INTEGER NOT NULL CHECK (original_balance > 0),
    -- The rate on what is left once the promotion ends. NULL = the card's regular APR.
    rate_after_bp     INTEGER CHECK (rate_after_bp IS NULL OR rate_after_bp BETWEEN 0 AND 100000),
    -- Deferred interest: unless it is cleared by the end date, interest for the whole
    -- promotion is charged at once.
    deferred_interest INTEGER NOT NULL DEFAULT 0 CHECK (deferred_interest IN (0, 1)),
    -- The recurring payment the payoff helper added for it. Deleting that item only unlinks it.
    payoff_item_id    INTEGER REFERENCES items (id) ON DELETE SET NULL,
    CHECK (end_date > start_date)
) STRICT;

CREATE INDEX card_promos_account ON card_promos (account_id, end_date);

-- One per loan. No row = the loan is only a balance the user enters.
CREATE TABLE loan_settings (
    account_id         INTEGER PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    -- What was borrowed, over how long, from when: kept for reference and the standard payment.
    original_principal INTEGER CHECK (original_principal IS NULL OR original_principal > 0),
    term_months        INTEGER CHECK (term_months IS NULL OR term_months BETWEEN 1 AND 600),
    start_date         TEXT,
    -- The whole monthly payment, escrow included.
    payment_amount     INTEGER NOT NULL CHECK (payment_amount >= 0),
    -- Day of the month the payment is due and a month's interest is added, 1-31 (31 = last day).
    payment_day        INTEGER NOT NULL CHECK (payment_day BETWEEN 1 AND 31),
    -- The parts of the monthly payment that go to insurance and taxes, not to the loan.
    escrow_insurance   INTEGER NOT NULL DEFAULT 0 CHECK (escrow_insurance >= 0),
    escrow_taxes       INTEGER NOT NULL DEFAULT 0 CHECK (escrow_taxes >= 0),
    CHECK (escrow_insurance + escrow_taxes <= payment_amount)
) STRICT;
