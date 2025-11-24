-- Add missing updated_at column to transport_payouts to align with JPA entity
ALTER TABLE `transport_payouts`
    ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        AFTER `created_at`;
