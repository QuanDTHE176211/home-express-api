-- Ensure each external transaction/order code maps to a single payment
ALTER TABLE `payments`
    ADD UNIQUE KEY `uk_payments_transaction_id` (`transaction_id`);
