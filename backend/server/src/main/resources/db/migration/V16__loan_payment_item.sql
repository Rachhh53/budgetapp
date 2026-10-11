-- The recurring item that pays a loan (spec §5.7). The money leaves an account through that
-- item; linking it here is what makes each of its payments lower the loan as well.
-- Only adds a column, so nothing recorded for Undo stops fitting.
ALTER TABLE loan_settings ADD COLUMN payment_item_id INTEGER REFERENCES items (id) ON DELETE SET NULL;
