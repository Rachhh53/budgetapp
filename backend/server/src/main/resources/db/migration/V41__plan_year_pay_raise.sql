-- A raise partway through a plan's year (Rachel, 2026-10-23: "plan my income for next year for jan-may and then I
-- expect to get a cost of living raise in june ... let me just do the raise as a percent"). The planned paycheck is
-- what it comes to from the plan's first day; from raise_from on, gross pay and take-home go up by raise_bp, and a
-- deduction set as a share of pay goes up with them. On the day the plan takes effect the raised pay becomes the
-- source's pay change from raise_from, beside the one from the plan's first day. Both NULL for no raise; SQLite
-- cannot add a table CHECK tying them together, so PlansRepository writes both or neither and reads a raise only
-- when both are there.
ALTER TABLE plan_year_pay ADD COLUMN raise_from TEXT;
ALTER TABLE plan_year_pay ADD COLUMN raise_bp INTEGER CHECK (raise_bp IS NULL OR raise_bp BETWEEN 1 AND 10000);
