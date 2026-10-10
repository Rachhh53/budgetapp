-- Phase 4 feedback (2026-10-10): a raise. An income source keeps the amounts it started with on
-- its own row; each later change of pay is a row here that takes over from its date on. Only
-- new tables and columns are added, so nothing already recorded for Undo stops fitting.

-- The amounts of an income source from a date on: take-home, gross, and the employer's match
-- (the match columns mirror employer_match, and are all NULL when the employer adds nothing).
-- A paycheck uses the latest change dated on or before the day its schedule names, or the
-- source's own amounts when there is none.
CREATE TABLE income_pay_changes (
    id                    INTEGER PRIMARY KEY,
    income_source_id      INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    effective_date        TEXT    NOT NULL,
    gross_amount          INTEGER,
    net_amount            INTEGER NOT NULL,
    net_is_estimate       INTEGER NOT NULL DEFAULT 0 CHECK (net_is_estimate IN (0, 1)),
    match_to_account_id   INTEGER REFERENCES accounts (id),
    match_mode            TEXT    CHECK (match_mode IS NULL OR match_mode IN ('fixed', 'formula')),
    match_fixed_amount    INTEGER CHECK (match_fixed_amount IS NULL OR match_fixed_amount >= 0),
    match_bp              INTEGER CHECK (match_bp IS NULL OR match_bp BETWEEN 0 AND 100000),
    match_cap_bp_of_gross INTEGER CHECK (match_cap_bp_of_gross IS NULL OR match_cap_bp_of_gross BETWEEN 0 AND 10000),
    match_within_cap      INTEGER NOT NULL DEFAULT 0 CHECK (match_within_cap IN (0, 1)),
    -- A match has an account and a mode together, or neither; each mode has its own terms, and only those.
    CHECK ((match_to_account_id IS NULL) = (match_mode IS NULL)),
    CHECK ((match_mode IS 'fixed') = (match_fixed_amount IS NOT NULL)),
    CHECK ((match_mode IS 'formula') = (match_bp IS NOT NULL AND match_cap_bp_of_gross IS NOT NULL)),
    -- One change of pay per source per day; the same day again replaces it.
    UNIQUE (income_source_id, effective_date)
) STRICT;

-- The deductions that go with a change of pay. NULL = the amounts the source started with.
ALTER TABLE paycheck_deductions
    ADD COLUMN pay_change_id INTEGER REFERENCES income_pay_changes (id) ON DELETE CASCADE;

CREATE INDEX paycheck_deductions_by_pay_change ON paycheck_deductions (pay_change_id);
