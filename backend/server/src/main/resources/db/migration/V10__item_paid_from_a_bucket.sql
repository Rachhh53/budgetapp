-- A recurring bill, budget, transfer or card payment can be paid out of a bucket of the account
-- it leaves, so the bucket and the account go down together each time. NULL (every item before
-- this) means the money is not in a bucket.
ALTER TABLE item_versions ADD COLUMN from_bucket_id INTEGER REFERENCES buckets (id);
