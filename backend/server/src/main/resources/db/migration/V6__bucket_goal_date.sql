-- A bucket's goal can have a date to reach it by, so the app can say whether what is scheduled
-- gets there in time and what it would take if not. NULL = no date. Dates are YYYY-MM-DD text.
ALTER TABLE buckets ADD COLUMN goal_date TEXT;
