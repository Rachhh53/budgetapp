-- A recurring item can be paused: nothing it would have done on the paused days happens, and
-- it picks up again afterwards on its usual schedule ("pause the emergency fund transfer for
-- a couple of months"). Each pause is kept, so resuming never brings back the dates that were
-- paused, and an item can be paused more than once over its life.
CREATE TABLE item_pauses (
    id         INTEGER PRIMARY KEY,
    item_id    INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    -- First paused day.
    from_date  TEXT    NOT NULL,
    -- First day it runs again. NULL while it is paused until the user turns it back on.
    until_date TEXT,
    CHECK (until_date IS NULL OR until_date > from_date)
) STRICT;

CREATE INDEX item_pauses_item ON item_pauses (item_id);
