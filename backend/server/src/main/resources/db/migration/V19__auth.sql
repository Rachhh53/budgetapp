-- The app lock (spec §4.1, §7): an optional PIN and the recovery code that can reset it.
--
-- One row at most. No row, or a row with no pin_hash, means no PIN is set and the app opens
-- as it always has. Both secrets are stored only as Argon2id hashes. The table is left out
-- of the change log (see ChangeLog), so Undo can never bring an old PIN back and no hash is
-- ever copied into another table.

CREATE TABLE auth (
    id                 INTEGER PRIMARY KEY CHECK (id = 1),
    pin_hash           TEXT,
    recovery_code_hash TEXT,
    -- Wrong PINs and recovery codes in a row; a right one puts it back to zero.
    failed_attempts    INTEGER NOT NULL DEFAULT 0,
    -- No try is looked at before this instant. Kept here so restarting the app does not skip the wait.
    locked_until       TEXT,
    -- 1 once the first-launch offer to set a PIN has been answered, so it is made only once.
    offer_dismissed    INTEGER NOT NULL DEFAULT 0 CHECK (offer_dismissed IN (0, 1)),
    CHECK ((pin_hash IS NULL) = (recovery_code_hash IS NULL))
) STRICT;
