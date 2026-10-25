-- Money paid back can come out of one of her own accounts, like an FSA, or from outside, like an insurer (Rachel,
-- 2026-10-25: "there is not option to take it out of the fsa. some will come out of there, some might just be reimbursed
-- from no account"). She chose it set on the item's way back, changeable on each payment, pack or expense. NULL is from
-- outside: money arriving, as before. An account: a transfer out of it into the account it comes back into.

ALTER TABLE item_reimbursement_settings ADD COLUMN from_account_id INTEGER REFERENCES accounts (id);

-- Each flag starts with its item's, and keeps its own once changed.
ALTER TABLE payment_reimbursements ADD COLUMN from_account_id INTEGER REFERENCES accounts (id);
ALTER TABLE pack_reimbursements ADD COLUMN from_account_id INTEGER REFERENCES accounts (id);

ALTER TABLE entries ADD COLUMN reimbursement_from_account_id INTEGER REFERENCES accounts (id);
