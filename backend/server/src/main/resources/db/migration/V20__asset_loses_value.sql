-- An asset can lose value by a yearly rate, as a car does (decided 2026-10-11; before this a
-- rate could only grow it). `appreciation_bp` is checked to be zero or more, and SQLite cannot
-- change a check without rebuilding the table, which other tables refer to. So the loss has
-- a column of its own: at most one of the two is set, and 99% a year is the most (at 100% it
-- would be worth nothing after a month).
-- Only adds a column, so nothing recorded for Undo stops fitting.
ALTER TABLE assets ADD COLUMN depreciation_bp INTEGER CHECK (depreciation_bp IS NULL OR depreciation_bp BETWEEN 1 AND 9900);
