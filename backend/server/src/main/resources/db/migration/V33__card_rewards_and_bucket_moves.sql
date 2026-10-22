-- Card rewards, and money moved out of a bucket (Rachel, 2026-10-19: "option to apply rewards balance automatically each month to a
-- card balance"; spec §5.6). A card's cash-back rate, and a month's rewards typed over.
--
-- rewards_bp: what a card gives back on what is charged, in basis points (1.5% is 150). NULL = no
-- rewards. Kept beside the card's dates like the minimum rule: changed on its own, and saving the
-- dates leaves it as it is.
ALTER TABLE card_settings ADD COLUMN rewards_bp INTEGER CHECK (rewards_bp IS NULL OR rewards_bp BETWEEN 1 AND 2000);

-- One statement's rewards as they really came, by the projected close date they are added on.
-- estimated_amount keeps what was estimated when it was first typed over, to show beside it.
CREATE TABLE card_reward_overrides (
    account_id       INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    close_date       TEXT    NOT NULL,
    amount           INTEGER NOT NULL CHECK (amount >= 0),
    estimated_amount INTEGER NOT NULL,
    PRIMARY KEY (account_id, close_date)
) STRICT, WITHOUT ROWID;

-- Money moved out of a bucket to money not in a bucket, entered as its own thing (Rachel, 2026-10-20:
-- "I can't transfer within an account from a bucket to general fund as income in pay period"). It is a
-- bucket change like any other (a negative amount), listed under Income on the pay period, with a
-- category and tags like a one-off. to_available marks it; an "Amount adjusted" change is not one.
ALTER TABLE bucket_changes ADD COLUMN to_available INTEGER NOT NULL DEFAULT 0 CHECK (to_available IN (0, 1));
ALTER TABLE bucket_changes ADD COLUMN category_id INTEGER REFERENCES categories (id) ON DELETE SET NULL;

CREATE TABLE bucket_change_tags (
    change_id INTEGER NOT NULL REFERENCES bucket_changes (id) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (change_id, tag_id)
) STRICT, WITHOUT ROWID;
