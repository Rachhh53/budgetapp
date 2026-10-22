-- When the next pack of something paid ahead as a pack is due (Rachel, 2026-10-20: "I want both options"):
-- when its sessions use the last one up, as before, or on a schedule of its own, such as every 10 weeks.
-- And any planned pack's date can be moved ("I also want the option to just change the date"). spec §4.6.

-- The pack's own payment schedule, from this version on; empty means a pack is due when the sessions use
-- up what was paid. The item's own schedule stays its sessions: how often it is used.
ALTER TABLE item_versions ADD COLUMN pack_schedule_rule_id INTEGER REFERENCES schedule_rules (id);

-- A planned pack moved to another day, keyed by the day Budget planned it on. Recording the payment is
-- still what pays it; a move only says when it is expected.
CREATE TABLE item_pack_moves (
    item_id      INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    planned_date TEXT    NOT NULL,
    moved_to     TEXT    NOT NULL,
    PRIMARY KEY (item_id, planned_date)
) STRICT;
