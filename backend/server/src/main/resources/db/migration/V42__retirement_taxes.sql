-- Taxes and Medicare in retirement, worked out by the rules (Rachel, 2026-10-23: "can you do some kind of calculator
-- for medicare premiums in retirement? is there an algorithm we can use - make it transparent and editable?"; "some of
-- my income is not taxable so that needs to be factored in"; "make sure you decide which account my income comes from
-- based on what will minimize my tax burden"). spec §4.12, §5.12. Amounts are cents in today's dollars; rates basis points.

-- How a scenario taxes retirement: 'flat' is the one tax rate on traditional money as before; 'worked_out' takes money
-- out year by year in the order that costs least, with federal brackets, Social Security, gains, required
-- distributions, a state rate and Medicare's premiums, and puts what that comes to in place of the flat rate and of
-- the health cost from 65.
ALTER TABLE retirement_scenarios ADD COLUMN tax_mode TEXT NOT NULL DEFAULT 'flat' CHECK (tax_mode IN ('flat', 'worked_out'));
ALTER TABLE retirement_scenarios ADD COLUMN filing TEXT NOT NULL DEFAULT 'single' CHECK (filing IN ('single', 'joint'));
-- How many pay Medicare premiums: one, or two with a spouse.
ALTER TABLE retirement_scenarios ADD COLUMN medicare_people INTEGER NOT NULL DEFAULT 1 CHECK (medicare_people IN (1, 2));
ALTER TABLE retirement_scenarios ADD COLUMN state_tax_bp INTEGER NOT NULL DEFAULT 0 CHECK (state_tax_bp BETWEEN 0 AND 2000);
-- How much of each dollar taken from taxable accounts is growth, taxed as a long-term gain.
ALTER TABLE retirement_scenarios ADD COLUMN gains_share_bp INTEGER NOT NULL DEFAULT 5000 CHECK (gains_share_bp BETWEEN 0 AND 10000);
-- A month a person: a Part D drug plan, and Medigap or a Medicare Advantage plan.
ALTER TABLE retirement_scenarios ADD COLUMN part_d_plan_cents INTEGER NOT NULL DEFAULT 0 CHECK (part_d_plan_cents >= 0);
ALTER TABLE retirement_scenarios ADD COLUMN supplement_cents INTEGER NOT NULL DEFAULT 0 CHECK (supplement_cents >= 0);
-- Income a year while still working, which Medicare looks back to in the first two years; empty for the pay Budget knows.
ALTER TABLE retirement_scenarios ADD COLUMN working_magi_cents INTEGER CHECK (working_magi_cents IS NULL OR working_magi_cents >= 0);
-- An income typed for every year in retirement in place of the one worked out; empty to work it out.
ALTER TABLE retirement_scenarios ADD COLUMN magi_override_cents INTEGER CHECK (magi_override_cents IS NULL OR magi_override_cents >= 0);
-- A strategy chosen in place of the cheapest: 'none', 'bracket:N' or 'medicare:N'; empty for the cheapest.
ALTER TABLE retirement_scenarios ADD COLUMN strategy TEXT;

-- Whether other income is Social Security, taxed by its own formula, and how much of any other is taxed income (a pension
-- all of it, a disability benefit none).
ALTER TABLE retirement_income ADD COLUMN social_security INTEGER NOT NULL DEFAULT 0 CHECK (social_security IN (0, 1));
ALTER TABLE retirement_income ADD COLUMN counted_bp INTEGER NOT NULL DEFAULT 10000 CHECK (counted_bp BETWEEN 0 AND 10000);
-- Income named for Social Security already is it.
UPDATE retirement_income SET social_security = 1 WHERE lower(name) LIKE '%social security%';

-- A spouse's birth date, for when their Medicare starts and their deduction at 65; empty for none.
ALTER TABLE retirement_profile ADD COLUMN spouse_birth_date TEXT;

-- The tax and Medicare figures, as changed from the built-in ones; no row means the built-in ones. Kept whole, as the
-- figures the app shows and edits together.
CREATE TABLE retirement_tax_figures (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    figures    TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
) STRICT;
