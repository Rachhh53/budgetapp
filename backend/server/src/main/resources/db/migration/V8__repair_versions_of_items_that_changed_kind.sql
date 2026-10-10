-- A repair of data, not a change of shape.
--
-- Until 2026-10-09, changing what kind of item something is (a bucket move into a transfer,
-- say) only applied from that day on, while the kind itself applies to the item's whole life.
-- Its earlier versions kept the accounts of the old kind: a transfer "from" the savings account
-- to no account at all. Those dates then filled the bucket without the money ever arriving in
-- the account, so a bucket could show more than its account was ever given.
--
-- The app no longer saves that (earlier versions follow a change of kind). This gives the
-- versions already saved that way the accounts and buckets of the item's newest version,
-- leaving every version's date, amount and schedule as it was.
--
-- Only a transfer or card payment can be told apart for certain: it must name where the money
-- goes. A version of one that names nowhere, beside a newest version that does, is repaired.
UPDATE item_versions
SET pay_from_account_id = (SELECT newest.pay_from_account_id FROM item_versions newest
                           WHERE newest.item_id = item_versions.item_id
                           ORDER BY newest.effective_from DESC, newest.id DESC LIMIT 1),
    bucket_id           = (SELECT newest.bucket_id FROM item_versions newest
                           WHERE newest.item_id = item_versions.item_id
                           ORDER BY newest.effective_from DESC, newest.id DESC LIMIT 1),
    save_up_bucket_id   = (SELECT newest.save_up_bucket_id FROM item_versions newest
                           WHERE newest.item_id = item_versions.item_id
                           ORDER BY newest.effective_from DESC, newest.id DESC LIMIT 1),
    -- Last, so the rows this statement has already repaired no longer match its WHERE.
    pay_to_account_id   = (SELECT newest.pay_to_account_id FROM item_versions newest
                           WHERE newest.item_id = item_versions.item_id
                           ORDER BY newest.effective_from DESC, newest.id DESC LIMIT 1)
WHERE pay_to_account_id IS NULL
  AND item_id IN (SELECT id FROM items WHERE kind IN ('transfer', 'card_payment'))
  AND (SELECT newest.pay_to_account_id FROM item_versions newest
       WHERE newest.item_id = item_versions.item_id
       ORDER BY newest.effective_from DESC, newest.id DESC LIMIT 1) IS NOT NULL;
