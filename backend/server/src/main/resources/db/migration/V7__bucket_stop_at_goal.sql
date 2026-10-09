-- A bucket can stop taking its recurring moves once it holds its goal, so saving towards
-- something ends by itself instead of running on. 0 = keep going (how every bucket behaved
-- before this), 1 = stop when the goal is reached.
ALTER TABLE buckets ADD COLUMN stop_at_goal INTEGER NOT NULL DEFAULT 0;
