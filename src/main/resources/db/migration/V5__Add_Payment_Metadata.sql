-- Add gateway metadata storage for payments and optimize lookups by transaction id
-- Use TEXT for wider database compatibility (JSON functions can still parse string payloads in app layer)
ALTER TABLE `payments`
    ADD COLUMN `metadata` TEXT NULL AFTER `updated_at`;

CREATE INDEX `idx_payments_transaction_id` ON `payments`(`transaction_id`);
