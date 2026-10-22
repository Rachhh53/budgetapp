-- Seasonal schedules (Rachel, 2026-10-19: snowplowing, "several times per week in the winter and not
-- at all in the summer"): a schedule can run in only some months, and a date it names in any other
-- month does not happen (spec §4.6).

-- The months it runs in, one bit each: bit 0 is January, bit 11 December. 4095 (every bit) is every
-- month, which is what every schedule so far has been.
ALTER TABLE schedule_rules ADD COLUMN active_months INTEGER NOT NULL DEFAULT 4095
    CHECK (active_months BETWEEN 1 AND 4095);

-- A weekly or fortnightly schedule on several days of the week (Rachel, 2026-10-19: a class "twice per
-- week"), one bit each: bit 0 is Monday, bit 6 Sunday. 0 is just the start date's own day, which is
-- what every weekly schedule so far has been.
ALTER TABLE schedule_rules ADD COLUMN weekdays INTEGER NOT NULL DEFAULT 0
    CHECK (weekdays BETWEEN 0 AND 127);
