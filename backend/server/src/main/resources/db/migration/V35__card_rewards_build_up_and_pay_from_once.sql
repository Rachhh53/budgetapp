-- Two changes in one: card rewards that build up, and one occurrence paid from another account.
-- Card rewards left to build up, and used when chosen (Rachel, 2026-10-20: "rewards are only set up as a statement credit
-- that automatically applies. I think that should be an option. you should also be able to select don't apply and they
-- just accumulate and budget tracks that. I want to say when they apply in some cases"). spec §5.6.

-- rewards_build_up: 1 when rewards build up instead of coming off each statement.
-- rewards_yearly_month: every year, all built up goes on at the statement closing in this month; NULL for no such rule.
-- rewards_threshold_cents: all built up goes on at the first close where at least this has built up; NULL for no such rule.
-- rewards_start_cents / rewards_start_date: what had built up on a day, as the card says; earnings before it are in it.
ALTER TABLE card_settings ADD COLUMN rewards_build_up INTEGER NOT NULL DEFAULT 0 CHECK (rewards_build_up IN (0, 1));
ALTER TABLE card_settings ADD COLUMN rewards_yearly_month INTEGER CHECK (rewards_yearly_month IS NULL OR rewards_yearly_month BETWEEN 1 AND 12);
ALTER TABLE card_settings ADD COLUMN rewards_threshold_cents INTEGER CHECK (rewards_threshold_cents IS NULL OR rewards_threshold_cents > 0);
ALTER TABLE card_settings ADD COLUMN rewards_start_cents INTEGER CHECK (rewards_start_cents IS NULL OR rewards_start_cents >= 0);
ALTER TABLE card_settings ADD COLUMN rewards_start_date TEXT;

-- A statement credit asked for on a day, from what has built up. amount_cents NULL = everything built up by then.
CREATE TABLE card_reward_redemptions (
    id           INTEGER PRIMARY KEY,
    account_id   INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    date         TEXT    NOT NULL,
    amount_cents INTEGER CHECK (amount_cents IS NULL OR amount_cents > 0),
    note         TEXT
) STRICT;

CREATE INDEX card_reward_redemptions_account ON card_reward_redemptions (account_id, date);

-- One occurrence paid from another account than its item's own, just this once (Rachel, 2026-10-20: "sometimes I need to
-- just change the account its pay from as a one off"). NULL = the item's own. It comes out of money not in a bucket there.
ALTER TABLE occurrence_overrides ADD COLUMN pay_from_account_id INTEGER REFERENCES accounts (id) ON DELETE SET NULL;
