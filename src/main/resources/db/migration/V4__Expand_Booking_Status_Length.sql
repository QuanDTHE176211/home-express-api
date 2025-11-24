-- Ensure booking status columns can store longer enum values like CONFIRMED_BY_CUSTOMER
ALTER TABLE bookings
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

ALTER TABLE booking_status_history
    MODIFY COLUMN old_status VARCHAR(32),
    MODIFY COLUMN new_status VARCHAR(32) NOT NULL;
