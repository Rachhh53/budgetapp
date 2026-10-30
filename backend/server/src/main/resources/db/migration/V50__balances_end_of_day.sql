-- A balance entered from now on is what the account held at the end of its day, so everything dated that day
-- (a paycheck that landed that morning, an autopay bill) is already in it (Rachel, 2026-10-30: "balances should
-- always be end of day"). Balances entered before keep their meaning, the start of their day: they were typed that way.
ALTER TABLE balance_entries ADD COLUMN end_of_day INTEGER NOT NULL DEFAULT 0 CHECK (end_of_day IN (0, 1));
