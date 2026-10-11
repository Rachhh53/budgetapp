-- How each card's minimum payment is estimated until a statement with the real one is entered
-- (spec §5.6). Issuers differ, so the rule is the card's own: a share of the statement balance,
-- with or without that month's interest on top, and never less than a floor.
-- The defaults are what the app assumed before this: 1% plus interest, at least $25.
-- Only adds columns, so nothing recorded for Undo stops fitting.
ALTER TABLE card_settings ADD COLUMN minimum_share_bp INTEGER NOT NULL DEFAULT 100
    CHECK (minimum_share_bp BETWEEN 0 AND 10000);
ALTER TABLE card_settings ADD COLUMN minimum_floor INTEGER NOT NULL DEFAULT 2500
    CHECK (minimum_floor >= 0);
ALTER TABLE card_settings ADD COLUMN minimum_plus_interest INTEGER NOT NULL DEFAULT 1
    CHECK (minimum_plus_interest IN (0, 1));
