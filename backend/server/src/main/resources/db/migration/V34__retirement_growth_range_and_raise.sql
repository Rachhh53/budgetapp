-- Retirement growth as an expected rate with a lower and a higher one beside it, and an optional raise
-- (Rachel, 2026-10-20: "we expect 6% growth and it would show 6%, 4% and 8% - but again those should be
-- configurable"; "adding an optional annual salary adjustment"; "salary adjustment is a %"). spec §4.12, §5.11.
-- Every account now grows at the scenario's rates; an account's own assumed growth is no longer used here.

-- A scenario that used each account's own rate starts from 6% instead.
UPDATE retirement_scenarios SET growth_bp = 600 WHERE growth_bp IS NULL;

ALTER TABLE retirement_scenarios ADD COLUMN growth_low_bp INTEGER NOT NULL DEFAULT 400 CHECK (growth_low_bp BETWEEN 0 AND 10000);
ALTER TABLE retirement_scenarios ADD COLUMN growth_high_bp INTEGER NOT NULL DEFAULT 800 CHECK (growth_high_bp BETWEEN 0 AND 10000);

-- 2 points either side of what each scenario already had, never below 0%.
UPDATE retirement_scenarios SET growth_low_bp = max(growth_bp - 200, 0), growth_high_bp = min(growth_bp + 200, 10000);

-- How much more goes into the accounts each year than the year before; 0 for none.
ALTER TABLE retirement_scenarios ADD COLUMN raise_bp INTEGER NOT NULL DEFAULT 0 CHECK (raise_bp BETWEEN 0 AND 2000);
