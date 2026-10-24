-- A whole paycheck planned for next year, kept with the plan and nowhere else (Rachel, 2026-10-22: "plan next year
-- should be all theorical and not change anything real"). Before this the plan held only take-home pay, and the way
-- to change gross pay, deductions or a match for next year was the real income source, which changed this year too.
-- While the plan is a draft nothing here touches the income source; on the day the plan takes effect it becomes the
-- source's pay change from that day, as a typed take-home amount already did.

-- The amounts of one income source's paycheck in a plan's year. The match columns mirror income_pay_changes, and
-- are all NULL when the employer adds nothing. An account a draft's paycheck uses cannot be deleted, as with real
-- income; once the plan is closed its paycheck is only a record, so deleting an account it named lets go of the
-- match (the planned paycheck goes, the plan's take-home stays) or of a deduction's account.
CREATE TABLE plan_year_pay (
    id                    INTEGER PRIMARY KEY,
    plan_year_id          INTEGER NOT NULL REFERENCES plan_years (id) ON DELETE CASCADE,
    income_source_id      INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    gross_amount          INTEGER CHECK (gross_amount IS NULL OR gross_amount >= 0),
    net_amount            INTEGER NOT NULL CHECK (net_amount >= 0),
    net_is_estimate       INTEGER NOT NULL DEFAULT 1 CHECK (net_is_estimate IN (0, 1)),
    match_to_account_id   INTEGER REFERENCES accounts (id) ON DELETE CASCADE,
    match_mode            TEXT    CHECK (match_mode IS NULL OR match_mode IN ('fixed', 'formula')),
    match_fixed_amount    INTEGER CHECK (match_fixed_amount IS NULL OR match_fixed_amount >= 0),
    match_bp              INTEGER CHECK (match_bp IS NULL OR match_bp BETWEEN 0 AND 100000),
    match_cap_bp_of_gross INTEGER CHECK (match_cap_bp_of_gross IS NULL OR match_cap_bp_of_gross BETWEEN 0 AND 10000),
    match_within_cap      INTEGER NOT NULL DEFAULT 0 CHECK (match_within_cap IN (0, 1)),
    CHECK ((match_to_account_id IS NULL) = (match_mode IS NULL)),
    CHECK ((match_mode IS 'fixed') = (match_fixed_amount IS NOT NULL)),
    CHECK ((match_mode IS 'formula') = (match_bp IS NOT NULL AND match_cap_bp_of_gross IS NOT NULL)),
    -- One planned paycheck per source per plan; saving again replaces it.
    UNIQUE (plan_year_id, income_source_id)
) STRICT;

-- What comes out of a planned paycheck, in the order it was typed.
CREATE TABLE plan_year_pay_deductions (
    id               INTEGER PRIMARY KEY,
    plan_year_pay_id INTEGER NOT NULL REFERENCES plan_year_pay (id) ON DELETE CASCADE,
    name             TEXT    NOT NULL,
    amount           INTEGER NOT NULL CHECK (amount >= 0),
    to_account_id    INTEGER REFERENCES accounts (id) ON DELETE SET NULL,
    percent_bp       INTEGER CHECK (percent_bp IS NULL OR percent_bp BETWEEN 0 AND 10000),
    sort_order       INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE INDEX plan_year_pay_deductions_pay ON plan_year_pay_deductions (plan_year_pay_id, sort_order);
