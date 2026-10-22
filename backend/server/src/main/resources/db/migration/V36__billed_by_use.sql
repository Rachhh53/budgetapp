-- Billed by use (Rachel, 2026-10-20): a bill for something used an uncertain number of times, such as
-- snowplowing, billed once a month at most for every use since the last bill. The item's amount is
-- then the cost of each use; these say about how often it is used. Both NULL for every other item.
ALTER TABLE item_versions ADD COLUMN uses_hundredths INTEGER CHECK (uses_hundredths IS NULL OR uses_hundredths > 0);
ALTER TABLE item_versions ADD COLUMN uses_per TEXT CHECK (uses_per IS NULL OR uses_per IN ('week', 'month'));
