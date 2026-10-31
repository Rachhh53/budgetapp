-- The statement's own totals, typed off it beside its two balances (Rachel, 2026-10-30: "spent should be from the
-- statement - total charges - payments and money back should also be from statement"), to set against the rows Budget
-- read. On a card: total charges, and payments and credits; on a bank account: withdrawals, and deposits and credits.
-- Positive amounts; null when not typed.
ALTER TABLE statement_uploads ADD COLUMN statement_charges INTEGER CHECK (statement_charges >= 0);
ALTER TABLE statement_uploads ADD COLUMN statement_credits INTEGER CHECK (statement_credits >= 0);
