-- Interest typed over (Rachel, 2026-10-19: "edit an interest amount in an account if it's wrong").
-- Any estimated interest line in an account's transactions can be typed over, one at a time: a
-- savings or checking account's monthly interest, a card's interest at a statement close, a loan's
-- month of interest, or an investment's assumed growth for a month. The typed amount simply becomes
-- the amount (spec §2), and later months are worked out from the balance it leaves.
--
-- date is the day the interest is added; for an investment it is the first day of the month whose
-- growth was typed over, since growth is added daily and listed a month at a time. An account
-- earns one kind of interest only, so the account and the date say which line it is.
-- estimated_amount keeps what was estimated when it was first typed over, to show beside it.
CREATE TABLE interest_overrides (
    account_id       INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    date             TEXT    NOT NULL,
    amount           INTEGER NOT NULL CHECK (amount >= 0),
    estimated_amount INTEGER NOT NULL,
    PRIMARY KEY (account_id, date)
) STRICT, WITHOUT ROWID;
