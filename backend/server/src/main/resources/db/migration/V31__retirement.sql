-- Retirement planning (Rachel, 2026-10-19: "enter how much I want to be able to have a salary in retirement
-- … tell me at what age I can retire"; "something I can rerun with different scenarios"; "I have a roth and
-- traditional ira which will have tax implications. cost of health insurance will come into play").
-- spec §4.12; the sums are §5.11. Amounts are cents in today's dollars; rates are basis points.

-- How an investment or savings account's money comes out in retirement; empty means it isn't counted.
ALTER TABLE accounts ADD COLUMN retirement_tax_type TEXT
    CHECK (retirement_tax_type IN ('traditional', 'roth', 'taxable'));

-- One row: the birth date that turns years into ages.
CREATE TABLE retirement_profile (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    birth_date TEXT NOT NULL
) STRICT;

-- One way retirement could go. Each is worked out afresh from the accounts as they stand.
CREATE TABLE retirement_scenarios (
    id                  INTEGER PRIMARY KEY,
    name                TEXT    NOT NULL,
    salary_cents        INTEGER NOT NULL CHECK (salary_cents >= 0),
    inflation_bp        INTEGER NOT NULL CHECK (inflation_bp BETWEEN 0 AND 2000),
    withdrawal_bp       INTEGER NOT NULL CHECK (withdrawal_bp BETWEEN 100 AND 1000),
    tax_bp              INTEGER NOT NULL CHECK (tax_bp BETWEEN 0 AND 6000),
    health_before_cents INTEGER NOT NULL DEFAULT 0 CHECK (health_before_cents >= 0),
    health_after_cents  INTEGER NOT NULL DEFAULT 0 CHECK (health_after_cents >= 0),
    health_inflation_bp INTEGER NOT NULL CHECK (health_inflation_bp BETWEEN 0 AND 2000),
    -- One growth rate for every account in place of each one's own; empty for their own.
    growth_bp           INTEGER CHECK (growth_bp BETWEEN 0 AND 10000),
    extra_monthly_cents INTEGER NOT NULL DEFAULT 0 CHECK (extra_monthly_cents >= 0),
    extra_into          TEXT    NOT NULL CHECK (extra_into IN ('traditional', 'roth', 'taxable')),
    retire_age          INTEGER CHECK (retire_age BETWEEN 18 AND 100),
    -- Shown side by side in the comparison; the app keeps it to three.
    compared            INTEGER NOT NULL DEFAULT 0 CHECK (compared IN (0, 1)),
    created_at          TEXT    NOT NULL
) STRICT;

-- Other money coming in during retirement: Social Security, a pension.
CREATE TABLE retirement_income (
    id           INTEGER PRIMARY KEY,
    scenario_id  INTEGER NOT NULL REFERENCES retirement_scenarios (id) ON DELETE CASCADE,
    name         TEXT    NOT NULL,
    yearly_cents INTEGER NOT NULL CHECK (yearly_cents > 0),
    start_age    INTEGER NOT NULL CHECK (start_age BETWEEN 18 AND 100)
) STRICT;

CREATE INDEX retirement_income_scenario ON retirement_income (scenario_id);

-- Money in (+) or out (−) once, at an age: a house sale, an inheritance, a big expense.
CREATE TABLE retirement_one_offs (
    id           INTEGER PRIMARY KEY,
    scenario_id  INTEGER NOT NULL REFERENCES retirement_scenarios (id) ON DELETE CASCADE,
    name         TEXT    NOT NULL,
    amount_cents INTEGER NOT NULL CHECK (amount_cents <> 0),
    age          INTEGER NOT NULL CHECK (age BETWEEN 18 AND 100)
) STRICT;

CREATE INDEX retirement_one_offs_scenario ON retirement_one_offs (scenario_id);
