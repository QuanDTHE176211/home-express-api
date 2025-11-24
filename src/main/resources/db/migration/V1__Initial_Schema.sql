-- ============================================================================
-- HOME EXPRESS - INITIAL DATABASE SCHEMA (V1)
-- ============================================================================
-- Description: Consolidated Schema (Merged V1, V3, V4, V5, V6)
-- Includes: Auth, Users, Locations, Transport, Booking, Pricing, Reviews, Finance
-- ============================================================================

SET @OLD_UNIQUE_CHECKS = @@UNIQUE_CHECKS, UNIQUE_CHECKS = 0;
SET @OLD_FOREIGN_KEY_CHECKS = @@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS = 0;
SET @OLD_SQL_MODE = @@SQL_MODE, SQL_MODE = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- ============================================================================
-- 1. VIETNAMESE REFERENCE DATA (Locations & Banks)
-- ============================================================================

CREATE TABLE IF NOT EXISTS `vn_banks` (
    `bank_code` VARCHAR(10) NOT NULL,
    `bank_name` VARCHAR(255) NOT NULL,
    `bank_name_en` VARCHAR(255) DEFAULT NULL,
    `napas_bin` VARCHAR(8) DEFAULT NULL COMMENT 'NAPAS Bank Identification Number',
    `swift_code` VARCHAR(11) DEFAULT NULL,
    `is_active` BOOLEAN DEFAULT TRUE,
    `logo_url` TEXT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`bank_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `vn_provinces` (
    `province_code` VARCHAR(6) NOT NULL,
    `province_name` VARCHAR(100) NOT NULL,
    `province_name_en` VARCHAR(100) DEFAULT NULL,
    `region` ENUM('NORTH', 'CENTRAL', 'SOUTH') NOT NULL,
    `display_order` INT DEFAULT 0,
    PRIMARY KEY (`province_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `vn_districts` (
    `district_code` VARCHAR(6) NOT NULL,
    `district_name` VARCHAR(100) NOT NULL,
    `province_code` VARCHAR(6) NOT NULL,
    PRIMARY KEY (`district_code`),
    KEY `idx_districts_province` (`province_code`),
    CONSTRAINT `fk_districts_province` FOREIGN KEY (`province_code`) REFERENCES `vn_provinces` (`province_code`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `vn_wards` (
    `ward_code` VARCHAR(6) NOT NULL,
    `ward_name` VARCHAR(100) NOT NULL,
    `district_code` VARCHAR(6) NOT NULL,
    PRIMARY KEY (`ward_code`),
    KEY `idx_wards_district` (`district_code`),
    CONSTRAINT `fk_wards_district` FOREIGN KEY (`district_code`) REFERENCES `vn_districts` (`district_code`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Seed Data: Top Banks
INSERT IGNORE INTO `vn_banks` (`bank_code`, `bank_name`, `napas_bin`, `is_active`) VALUES 
('VCB', 'Vietcombank', '970436', TRUE),
('TCB', 'Techcombank', '970407', TRUE),
('BIDV', 'BIDV', '970418', TRUE),
('MBB', 'MB Bank', '970422', TRUE),
('ACB', 'ACB', '970416', TRUE),
('VPB', 'VPBank', '970432', TRUE),
('TPB', 'TPBank', '970423', TRUE);

-- ============================================================================
-- 2. AUTHENTICATION & USERS
-- ============================================================================

CREATE TABLE IF NOT EXISTS `users` (
    `user_id` BIGINT NOT NULL AUTO_INCREMENT,
    `email` VARCHAR(255) NOT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `avatar_url` TEXT DEFAULT NULL,
    `password_hash` TEXT NOT NULL,
    `role` ENUM('CUSTOMER', 'TRANSPORT', 'MANAGER') NOT NULL,
    `is_active` BOOLEAN DEFAULT TRUE,
    `is_verified` BOOLEAN DEFAULT FALSE,
    `email_verified_at` DATETIME DEFAULT NULL,
    `last_password_change` DATETIME DEFAULT NULL,
    `locked_until` DATETIME DEFAULT NULL,
    `last_login` DATETIME DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_users_email_lower` ((LOWER(`email`))),
    UNIQUE KEY `uk_users_phone` (`phone`),
    KEY `idx_users_role` (`role`),
    KEY `idx_users_active` (`is_active`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `customers` (
    `customer_id` BIGINT NOT NULL,
    `full_name` VARCHAR(255) NOT NULL,
    `phone` VARCHAR(20) NOT NULL,
    `address` TEXT DEFAULT NULL,
    `date_of_birth` DATE DEFAULT NULL,
    `preferred_language` VARCHAR(10) DEFAULT 'vi',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`customer_id`),
    KEY `idx_customers_phone` (`phone`),
    CONSTRAINT `fk_customers_users` FOREIGN KEY (`customer_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE,
    CONSTRAINT `chk_customers_phone` CHECK (phone REGEXP '^0[1-9][0-9]{8}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `managers` (
    `manager_id` BIGINT NOT NULL,
    `full_name` VARCHAR(255) NOT NULL,
    `phone` VARCHAR(20) NOT NULL,
    `employee_id` VARCHAR(50) DEFAULT NULL,
    `department` VARCHAR(100) DEFAULT NULL,
    `permissions` JSON DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`manager_id`),
    UNIQUE KEY `uk_managers_employee` (`employee_id`),
    CONSTRAINT `fk_managers_users` FOREIGN KEY (`manager_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user_tokens` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `token_type` ENUM('VERIFY_EMAIL', 'RESET_PASSWORD', 'INVITE', 'MFA_RECOVERY') NOT NULL,
    `token_hash` VARCHAR(64) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at` DATETIME NOT NULL,
    `consumed_at` DATETIME DEFAULT NULL,
    `metadata` JSON DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tokens` (`user_id`, `token_type`, `token_hash`),
    KEY `idx_user_tokens_lookup` (`user_id`, `token_type`, `expires_at`),
    CONSTRAINT `fk_user_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user_sessions` (
    `session_id` CHAR(36) NOT NULL DEFAULT(UUID()),
    `user_id` BIGINT NOT NULL,
    `refresh_token_hash` VARCHAR(64) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at` DATETIME NOT NULL,
    `last_seen_at` DATETIME DEFAULT NULL,
    `revoked_at` DATETIME DEFAULT NULL,
    `revoked_reason` TEXT DEFAULT NULL,
    `ip_address` VARCHAR(45) DEFAULT NULL,
    `user_agent` TEXT DEFAULT NULL,
    `device_id` VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (`session_id`),
    KEY `idx_user_sessions_refresh` (`refresh_token_hash`),
    CONSTRAINT `fk_user_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `login_attempts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT DEFAULT NULL,
    `email` VARCHAR(255) NOT NULL,
    `ip_address` VARCHAR(45) DEFAULT NULL,
    `success` BOOLEAN NOT NULL,
    `failure_reason` TEXT DEFAULT NULL,
    `attempted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_login_attempts_email` (`email`, `attempted_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `table_name` VARCHAR(100) NOT NULL,
    `action` VARCHAR(50) NOT NULL,
    `row_pk` VARCHAR(100) DEFAULT NULL,
    `actor_id` BIGINT DEFAULT NULL,
    `new_data` JSON DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_log_table` (`table_name`),
    KEY `idx_audit_log_actor` (`actor_id`),
    KEY `idx_audit_log_row` (`row_pk`),
    KEY `idx_audit_log_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 3. TRANSPORT & VEHICLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS `transports` (
  `transport_id` BIGINT NOT NULL,
  `company_name` VARCHAR(255) NOT NULL,
  `business_license_number` VARCHAR(50) NOT NULL,
  `tax_code` VARCHAR(50) DEFAULT NULL,
  `phone` VARCHAR(20) NOT NULL,
  `address` TEXT NOT NULL,
  `city` VARCHAR(100) NOT NULL,
  `district` VARCHAR(100) DEFAULT NULL,
  `ward` VARCHAR(100) DEFAULT NULL,
  `license_photo_url` TEXT DEFAULT NULL,
  `insurance_photo_url` TEXT DEFAULT NULL,
  `verification_status` ENUM('PENDING', 'APPROVED', 'READY_TO_QUOTE', 'REJECTED') DEFAULT 'PENDING',
  `verified_at` DATETIME DEFAULT NULL,
  `verified_by` BIGINT DEFAULT NULL,
  `verification_notes` TEXT DEFAULT NULL,
  `total_bookings` INT DEFAULT 0,
  `completed_bookings` INT DEFAULT 0,
  `cancelled_bookings` INT DEFAULT 0,
  `average_rating` DECIMAL(3, 2) DEFAULT 0.00,
  `ready_to_quote` BOOLEAN NOT NULL DEFAULT FALSE,
  `rate_card_expires_at` DATETIME DEFAULT NULL,
  `national_id_number` VARCHAR(12) DEFAULT NULL,
  `national_id_type` ENUM('CMND', 'CCCD') DEFAULT NULL,
  `national_id_issue_date` DATE DEFAULT NULL,
  `national_id_issuer` VARCHAR(100) DEFAULT NULL,
  `national_id_photo_front_url` TEXT DEFAULT NULL,
  `national_id_photo_back_url` TEXT DEFAULT NULL,
  `bank_name` VARCHAR(100) DEFAULT NULL,
  `bank_code` VARCHAR(10) DEFAULT NULL,
  `bank_account_number` VARCHAR(19) DEFAULT NULL,
  `bank_account_holder` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`transport_id`),
  UNIQUE KEY `uk_transports_license` (`business_license_number`),
  UNIQUE KEY `uk_transports_tax_code` (`tax_code`),
  UNIQUE KEY `uk_transports_national_id` (`national_id_number`),
  KEY `idx_transports_city` (`city`),
  KEY `idx_transports_rating` (`average_rating` DESC),
  CONSTRAINT `fk_transports_users` FOREIGN KEY (`transport_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_transports_verifier` FOREIGN KEY (`verified_by`) REFERENCES `users` (`user_id`) ON DELETE SET NULL,
  CONSTRAINT `fk_transports_bank` FOREIGN KEY (`bank_code`) REFERENCES `vn_banks` (`bank_code`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `transport_settings` (
    `transport_id` BIGINT NOT NULL,
    `search_radius_km` DECIMAL(5, 2) NOT NULL DEFAULT 10.00,
    `min_job_value_vnd` DECIMAL(12, 0) NOT NULL DEFAULT 0,
    `auto_accept_jobs` TINYINT(1) NOT NULL DEFAULT 0,
    `response_time_hours` DECIMAL(4, 1) DEFAULT 2.0,
    `new_job_alerts` TINYINT(1) NOT NULL DEFAULT 1,
    `quotation_updates` TINYINT(1) NOT NULL DEFAULT 1,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`transport_id`),
    CONSTRAINT `fk_settings_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `categories` (
    `category_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `name_en` VARCHAR(100) NULL,
    `description` TEXT DEFAULT NULL,
    `icon` VARCHAR(50) NULL,
    `default_weight_kg` DECIMAL(10,2) NULL,
    `default_volume_m3` DECIMAL(10,4) NULL,
    `default_length_cm` DECIMAL(10,2) NULL,
    `default_width_cm` DECIMAL(10,2) NULL,
    `default_height_cm` DECIMAL(10,2) NULL,
    `is_fragile_default` BOOLEAN NOT NULL DEFAULT FALSE,
    `requires_disassembly_default` BOOLEAN NOT NULL DEFAULT FALSE,
    `display_order` INT NOT NULL DEFAULT 0,
    `is_active` BOOLEAN NOT NULL DEFAULT TRUE,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`category_id`),
    UNIQUE KEY `uk_categories_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `sizes` (
    `size_id` BIGINT NOT NULL AUTO_INCREMENT,
    `category_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `weight_kg` DECIMAL(10, 2),
    `height_cm` DECIMAL(10, 2),
    `width_cm` DECIMAL(10, 2),
    `depth_cm` DECIMAL(10, 2),
    `price_multiplier` DECIMAL(5, 2) NOT NULL DEFAULT 1.00,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`size_id`),
    KEY `idx_sizes_category` (`category_id`),
    CONSTRAINT `fk_sizes_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `category_pricing` (
    `category_pricing_id` BIGINT NOT NULL AUTO_INCREMENT,
    `transport_id` BIGINT NOT NULL,
    `category_id` BIGINT NOT NULL,
    `size_id` BIGINT,
    `price_per_unit_vnd` DECIMAL(12, 0) NOT NULL,
    `fragile_multiplier` DECIMAL(5, 2) NOT NULL DEFAULT 1.20,
    `disassembly_multiplier` DECIMAL(5, 2) NOT NULL DEFAULT 1.30,
    `heavy_multiplier` DECIMAL(5, 2) NOT NULL DEFAULT 1.50,
    `heavy_threshold_kg` DECIMAL(10, 2) NOT NULL DEFAULT 100.00,
    `is_active` BOOLEAN DEFAULT TRUE,
    `valid_from` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `valid_to` DATETIME DEFAULT NULL,
    `created_by` BIGINT DEFAULT NULL,
    `updated_by` BIGINT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`category_pricing_id`),
    KEY `idx_cp_transport` (`transport_id`),
    KEY `idx_cp_category` (`category_id`),
    KEY `idx_cp_size` (`size_id`),
    CONSTRAINT `fk_cp_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cp_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cp_size` FOREIGN KEY (`size_id`) REFERENCES `sizes` (`size_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_cp_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`),
    CONSTRAINT `fk_cp_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `users` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ============================================================================
-- 4. BOOKING & QUOTATION
-- ============================================================================

CREATE TABLE IF NOT EXISTS `bookings` (
  `booking_id` BIGINT NOT NULL AUTO_INCREMENT,
  `customer_id` BIGINT NOT NULL,
  `transport_id` BIGINT DEFAULT NULL,
  `pickup_address` TEXT NOT NULL,
  `pickup_latitude` DECIMAL(10, 8) DEFAULT NULL,
  `pickup_longitude` DECIMAL(11, 8) DEFAULT NULL,
  `pickup_floor` INT DEFAULT NULL,
  `pickup_has_elevator` BOOLEAN DEFAULT FALSE,
  `pickup_province_code` VARCHAR(6) DEFAULT NULL,
  `pickup_district_code` VARCHAR(6) DEFAULT NULL,
  `pickup_ward_code` VARCHAR(6) DEFAULT NULL,
  `delivery_address` TEXT NOT NULL,
  `delivery_latitude` DECIMAL(10, 8) DEFAULT NULL,
  `delivery_longitude` DECIMAL(11, 8) DEFAULT NULL,
  `delivery_floor` INT DEFAULT NULL,
  `delivery_has_elevator` BOOLEAN DEFAULT FALSE,
  `delivery_province_code` VARCHAR(6) DEFAULT NULL,
  `delivery_district_code` VARCHAR(6) DEFAULT NULL,
  `delivery_ward_code` VARCHAR(6) DEFAULT NULL,
  `preferred_date` DATE NOT NULL,
  `preferred_time_slot` VARCHAR(20) DEFAULT NULL,
  `actual_start_time` DATETIME DEFAULT NULL,
  `actual_end_time` DATETIME DEFAULT NULL,
  `distance_km` DECIMAL(8, 2) DEFAULT NULL,
  `distance_source` VARCHAR(20) DEFAULT NULL,
  `distance_calculated_at` DATETIME DEFAULT NULL,
  `estimated_price` DECIMAL(12, 0) DEFAULT NULL,
  `final_price` DECIMAL(12, 0) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `notes` TEXT DEFAULT NULL,
  `special_requirements` TEXT DEFAULT NULL,
  `cancelled_by` BIGINT DEFAULT NULL,
  `cancellation_reason` TEXT DEFAULT NULL,
  `cancelled_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`booking_id`),
  KEY `idx_bookings_customer` (`customer_id`),
  KEY `idx_bookings_transport` (`transport_id`),
  KEY `idx_bookings_status` (`status`),
  CONSTRAINT `fk_bookings_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`customer_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bookings_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `booking_items` (
    `item_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `category_id` BIGINT NOT NULL,
    `size_id` BIGINT NULL,
    `quantity` INT NOT NULL DEFAULT 1,
    `name` VARCHAR(255) NULL,
    `description` TEXT NULL,
    `brand` VARCHAR(100) NULL,
    `model` VARCHAR(100) NULL,
    `weight_kg` DECIMAL(10,2) NULL,
    `width_cm` DECIMAL(10,2) NULL,
    `height_cm` DECIMAL(10,2) NULL,
    `depth_cm` DECIMAL(10,2) NULL,
    `is_fragile` BOOLEAN DEFAULT FALSE,
    `requires_disassembly` BOOLEAN DEFAULT FALSE,
    `estimated_disassembly_time` INT DEFAULT 0,
    `unit_price` DECIMAL(12,0) DEFAULT 0,
    `total_price` DECIMAL(12,0) DEFAULT 0,
    `declared_value_vnd` DECIMAL(12,0) DEFAULT 0,
    `ai_metadata` JSON NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`item_id`),
    KEY `idx_booking_items` (`booking_id`),
    CONSTRAINT `fk_booking_items_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_booking_items_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `booking_status_history` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `booking_id` BIGINT NOT NULL,
    `old_status` VARCHAR(20),
    `new_status` VARCHAR(20) NOT NULL,
    `changed_by` BIGINT,
    `changed_by_role` VARCHAR(20),
    `reason` TEXT,
    `metadata` JSON,
    `changed_at` DATETIME NOT NULL,
    CONSTRAINT `fk_booking_status_history_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings`(`booking_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `vehicles` (
    `vehicle_id` BIGINT NOT NULL AUTO_INCREMENT,
    `transport_id` BIGINT NOT NULL,
    `type` VARCHAR(255) NOT NULL,
    `model` VARCHAR(100) NOT NULL,
    `license_plate` VARCHAR(20) NOT NULL,
    `license_plate_norm` VARCHAR(20),
    `license_plate_compact` VARCHAR(20),
    `capacity_kg` DECIMAL(19, 2) NOT NULL,
    `capacity_m3` DECIMAL(19, 2),
    `length_cm` DECIMAL(19, 2),
    `width_cm` DECIMAL(19, 2),
    `height_cm` DECIMAL(19, 2),
    `status` VARCHAR(255) NOT NULL,
    `year` SMALLINT,
    `color` VARCHAR(50),
    `has_tail_lift` TINYINT(1) NOT NULL,
    `has_tools` TINYINT(1) NOT NULL,
    `image_url` VARCHAR(255),
    `description` TEXT,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `created_at` DATETIME,
    `updated_at` DATETIME,
    PRIMARY KEY (`vehicle_id`),
    CONSTRAINT `fk_vehicles_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_vehicles_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`),
    CONSTRAINT `fk_vehicles_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `users` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `quotations` (
    `quotation_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `transport_id` BIGINT NOT NULL,
    `vehicle_id` BIGINT DEFAULT NULL,
    `quoted_price` DECIMAL(12, 0) NOT NULL,
    `base_price` DECIMAL(12, 0) DEFAULT NULL,
    `distance_price` DECIMAL(12, 0) DEFAULT NULL,
    `items_price` DECIMAL(12, 0) DEFAULT NULL,
    `additional_fees` DECIMAL(12, 0) DEFAULT NULL,
    `discount` DECIMAL(12,0) DEFAULT NULL,
    `price_breakdown` JSON DEFAULT NULL,
    `notes` TEXT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `validity_period` INT DEFAULT 7,
    `expires_at` DATETIME DEFAULT NULL,
    `responded_at` DATETIME DEFAULT NULL,
    `accepted_at` DATETIME DEFAULT NULL,
    `accepted_by` BIGINT DEFAULT NULL,
    `accepted_ip` VARCHAR(45) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`quotation_id`),
    KEY `idx_quotations_booking` (`booking_id`),
    KEY `idx_quotations_transport` (`transport_id`),
    CONSTRAINT `fk_quotations_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_quotations_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_quotations_vehicle` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicles` (`vehicle_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_quotations_accepted_by` FOREIGN KEY (`accepted_by`) REFERENCES `users` (`user_id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `transport_lists` (
    `list_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `transport_id` BIGINT NOT NULL,
    `notified_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `viewed_at` DATETIME DEFAULT NULL,
    PRIMARY KEY (`list_id`),
    UNIQUE KEY `uk_transport_lists` (`booking_id`, `transport_id`),
    CONSTRAINT `fk_transport_lists_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_transport_lists_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ============================================================================
-- 5. SETTLEMENT & PAYMENTS
-- ============================================================================

CREATE TABLE IF NOT EXISTS `commission_rules` (
    `rule_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `percentage` DECIMAL(5, 2) NOT NULL,
    `min_fee` DECIMAL(12, 0) DEFAULT 0,
    `max_fee` DECIMAL(12, 0) DEFAULT NULL,
    `apply_to_transport_type` VARCHAR(50) DEFAULT NULL,
    `is_active` BOOLEAN DEFAULT TRUE,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`rule_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `booking_settlements` (
    `settlement_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `transport_id` BIGINT NOT NULL,
    `agreed_price_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `deposit_paid_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `remaining_paid_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `tip_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `total_collected_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `gateway_fee_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `commission_rate_bps` INT NOT NULL DEFAULT 0,
    `platform_fee_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `adjustment_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `net_to_transport_vnd` DECIMAL(12,0) GENERATED ALWAYS AS (`total_collected_vnd` - `gateway_fee_vnd` - `platform_fee_vnd` + `adjustment_vnd`) STORED,
    `collection_mode` VARCHAR(20) NOT NULL DEFAULT 'ALL_ONLINE',
    `status` VARCHAR(20) DEFAULT 'PENDING',
    `on_hold_reason` TEXT,
    `payout_id` BIGINT DEFAULT NULL,
    `ready_at` DATETIME,
    `paid_at` DATETIME,
    `notes` TEXT,
    `metadata` JSON,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`settlement_id`),
    UNIQUE KEY `uk_settlements_booking` (`booking_id`),
    KEY `idx_settlements_transport` (`transport_id`),
    CONSTRAINT `fk_settlements_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `transport_wallets` (
    `wallet_id` BIGINT NOT NULL AUTO_INCREMENT,
    `transport_id` BIGINT NOT NULL,
    `current_balance_vnd` BIGINT NOT NULL DEFAULT 0,
    `total_earned_vnd` BIGINT NOT NULL DEFAULT 0,
    `total_withdrawn_vnd` BIGINT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `last_transaction_at` DATETIME,
    `created_at` DATETIME,
    `updated_at` DATETIME,
    PRIMARY KEY (`wallet_id`),
    UNIQUE KEY `uk_transport_wallets_transport_id` (`transport_id`),
    CONSTRAINT `fk_transport_wallets_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `transport_wallet_transactions` (
    `transaction_id` BIGINT NOT NULL AUTO_INCREMENT,
    `wallet_id` BIGINT NOT NULL,
    `transaction_type` VARCHAR(50) NOT NULL,
    `amount` DECIMAL(12,0) NOT NULL,
    `running_balance_vnd` DECIMAL(12,0) DEFAULT NULL,
    `reference_type` VARCHAR(50) DEFAULT NULL,
    `reference_id` BIGINT DEFAULT NULL,
    `description` TEXT,
    `created_by` BIGINT DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`transaction_id`),
    KEY `idx_wallet_tx_wallet` (`wallet_id`),
    KEY `idx_wallet_tx_ref` (`reference_type`, `reference_id`),
    CONSTRAINT `fk_wallet_tx_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `transport_wallets` (`wallet_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `transport_payouts` (
    `payout_id` BIGINT NOT NULL AUTO_INCREMENT,
    `transport_id` BIGINT NOT NULL,
    `payout_number` VARCHAR(50) NOT NULL DEFAULT 'TEMP',
    `total_amount_vnd` DECIMAL(12,0) NOT NULL DEFAULT 0,
    `item_count` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `bank_code` VARCHAR(10),
    `bank_account_number` VARCHAR(19),
    `bank_account_holder` VARCHAR(255),
    `transaction_reference` VARCHAR(255),
    `processed_at` DATETIME,
    `completed_at` DATETIME,
    `failure_reason` TEXT,
    `notes` TEXT,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`payout_id`),
    UNIQUE KEY `uk_payouts_number` (`payout_number`),
    KEY `idx_payouts_transport` (`transport_id`),
    CONSTRAINT `fk_payouts_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `transport_payout_items` (
    `payout_item_id` BIGINT NOT NULL AUTO_INCREMENT,
    `payout_id` BIGINT NOT NULL,
    `settlement_id` BIGINT NOT NULL,
    `booking_id` BIGINT NOT NULL,
    `amount_vnd` DECIMAL(12,0) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`payout_item_id`),
    KEY `idx_payout_items_payout` (`payout_id`),
    KEY `idx_payout_items_settlement` (`settlement_id`),
    CONSTRAINT `fk_payout_items_payout` FOREIGN KEY (`payout_id`) REFERENCES `transport_payouts` (`payout_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_payout_items_settlement` FOREIGN KEY (`settlement_id`) REFERENCES `booking_settlements` (`settlement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 6. REVIEWS & INCIDENTS
-- ============================================================================

CREATE TABLE IF NOT EXISTS `reviews` (
    `review_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `customer_id` BIGINT NOT NULL,
    `transport_id` BIGINT NOT NULL,
    `rating` INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    `comment` TEXT DEFAULT NULL,
    `is_public` BOOLEAN DEFAULT TRUE,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`review_id`),
    UNIQUE KEY `uk_reviews_booking` (`booking_id`),
    KEY `idx_reviews_transport` (`transport_id`),
    CONSTRAINT `fk_reviews_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_reviews_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`customer_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_reviews_transport` FOREIGN KEY (`transport_id`) REFERENCES `transports` (`transport_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notifications` (
    `notification_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `type` VARCHAR(50) NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `message` TEXT NOT NULL,
    `reference_type` VARCHAR(50) DEFAULT NULL,
    `reference_id` BIGINT DEFAULT NULL,
    `is_read` BOOLEAN NOT NULL DEFAULT FALSE,
    `read_at` DATETIME DEFAULT NULL,
    `priority` VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`notification_id`),
    KEY `idx_notifications_user` (`user_id`),
    CONSTRAINT `fk_notifications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `incidents` (
    `incident_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `reported_by` BIGINT NOT NULL,
    `type` VARCHAR(50) NOT NULL,
    `description` TEXT NOT NULL,
    `status` ENUM('OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED') DEFAULT 'OPEN',
    `resolution_notes` TEXT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`incident_id`),
    KEY `idx_incidents_booking` (`booking_id`),
    CONSTRAINT `fk_incidents_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_incidents_reporter` FOREIGN KEY (`reported_by`) REFERENCES `users` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ============================================================================
-- 7. ADDITIONAL MODULES
-- ============================================================================

-- OTP Codes
CREATE TABLE IF NOT EXISTS `otp_codes` (
    `otp_id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `email` VARCHAR(45) NOT NULL,
    `code` VARCHAR(6) NOT NULL,
    `expires_at` DATETIME NOT NULL,
    `is_used` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Saved Items
CREATE TABLE IF NOT EXISTS `saved_items` (
    `saved_item_id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `customer_id` BIGINT NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `brand` VARCHAR(100) DEFAULT NULL,
    `model` VARCHAR(200) DEFAULT NULL,
    `category_id` BIGINT DEFAULT NULL,
    `size` VARCHAR(50) DEFAULT NULL,
    `weight_kg` DECIMAL(8, 2) DEFAULT NULL,
    `dimensions` JSON DEFAULT NULL,
    `declared_value_vnd` DECIMAL(15, 2) DEFAULT NULL,
    `quantity` INT NOT NULL DEFAULT 1,
    `is_fragile` BOOLEAN DEFAULT FALSE,
    `requires_disassembly` BOOLEAN DEFAULT FALSE,
    `requires_packaging` BOOLEAN DEFAULT FALSE,
    `notes` TEXT DEFAULT NULL,
    `metadata` JSON DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_saved_items_customer_ref` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`customer_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_saved_items_category_ref` FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`) ON DELETE SET NULL,
    INDEX `idx_saved_items_customer_id` (`customer_id`),
    INDEX `idx_saved_items_category_id` (`category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Product Models
CREATE TABLE IF NOT EXISTS `product_models` (
    `model_id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `brand` VARCHAR(100) NOT NULL,
    `model` VARCHAR(200) NOT NULL,
    `product_name` VARCHAR(255) DEFAULT NULL,
    `category_id` BIGINT DEFAULT NULL,
    `weight_kg` DECIMAL(10, 2) DEFAULT NULL,
    `dimensions_mm` JSON DEFAULT NULL,
    `source` VARCHAR(50) DEFAULT 'system',
    `source_url` TEXT DEFAULT NULL,
    `usage_count` INT NOT NULL DEFAULT 1,
    `last_used_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `uk_brand_model` UNIQUE (`brand`, `model`),
    INDEX `idx_pm_brand` (`brand`),
    INDEX `idx_pm_model` (`model`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Payments
CREATE TABLE IF NOT EXISTS `payments` (
    `payment_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `amount` DECIMAL(12, 0) NOT NULL,
    `payment_method` VARCHAR(20) NOT NULL,
    `payment_type` VARCHAR(20) NOT NULL,
    `bank_code` VARCHAR(10),
    `status` VARCHAR(20) NOT NULL,
    `parent_payment_id` BIGINT,
    `refund_reason` VARCHAR(255),
    `failure_code` VARCHAR(50),
    `failure_message` TEXT,
    `transaction_id` VARCHAR(255),
    `idempotency_key` VARCHAR(64),
    `confirmed_by` BIGINT,
    `confirmed_at` DATETIME,
    `paid_at` DATETIME,
    `refunded_at` DATETIME,
    `created_at` DATETIME,
    `updated_at` DATETIME,
    PRIMARY KEY (`payment_id`),
    CONSTRAINT `fk_payments_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Disputes
CREATE TABLE IF NOT EXISTS `disputes` (
    `dispute_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `filed_by_user_id` BIGINT NOT NULL,
    `dispute_type` VARCHAR(50) NOT NULL,
    `status` VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    `title` VARCHAR(200) NOT NULL,
    `description` TEXT NOT NULL,
    `requested_resolution` TEXT,
    `resolution_notes` TEXT,
    `resolved_by_user_id` BIGINT,
    `resolved_at` DATETIME,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`dispute_id`),
    CONSTRAINT `fk_disputes_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_disputes_filed_by_user` FOREIGN KEY (`filed_by_user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `fk_disputes_resolved_by_user` FOREIGN KEY (`resolved_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `dispute_messages` (
    `message_id` BIGINT NOT NULL AUTO_INCREMENT,
    `dispute_id` BIGINT NOT NULL,
    `sender_user_id` BIGINT NOT NULL,
    `message_text` TEXT NOT NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`message_id`),
    CONSTRAINT `fk_dispute_messages_dispute` FOREIGN KEY (`dispute_id`) REFERENCES `disputes` (`dispute_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dispute_messages_sender` FOREIGN KEY (`sender_user_id`) REFERENCES `users` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Evidence
CREATE TABLE IF NOT EXISTS `evidence` (
    `evidence_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT,
    `incident_id` BIGINT,
    `uploaded_by_user_id` BIGINT NOT NULL,
    `evidence_type` VARCHAR(20) NOT NULL,
    `file_type` VARCHAR(20) NOT NULL,
    `file_url` TEXT NOT NULL,
    `file_name` VARCHAR(500) NOT NULL,
    `mime_type` VARCHAR(100),
    `file_size_bytes` BIGINT,
    `description` TEXT,
    `uploaded_at` DATETIME NOT NULL,
    PRIMARY KEY (`evidence_id`),
    CONSTRAINT `fk_evidence_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_evidence_incident` FOREIGN KEY (`incident_id`) REFERENCES `incidents` (`incident_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `dispute_evidence` (
    `dispute_id` BIGINT NOT NULL,
    `evidence_id` BIGINT NOT NULL,
    PRIMARY KEY (`dispute_id`, `evidence_id`),
    CONSTRAINT `fk_dispute_evidence_dispute` FOREIGN KEY (`dispute_id`) REFERENCES `disputes` (`dispute_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dispute_evidence_evidence` FOREIGN KEY (`evidence_id`) REFERENCES `evidence` (`evidence_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Contracts
CREATE TABLE IF NOT EXISTS `contracts` (
    `contract_id` BIGINT NOT NULL AUTO_INCREMENT,
    `booking_id` BIGINT NOT NULL,
    `quotation_id` BIGINT NOT NULL,
    `contract_number` VARCHAR(50) NOT NULL,
    `terms_and_conditions` TEXT NOT NULL,
    `total_amount` DECIMAL(12, 0) NOT NULL,
    `agreed_price_vnd` BIGINT NOT NULL,
    `deposit_required_vnd` BIGINT NOT NULL,
    `deposit_due_at` DATETIME,
    `balance_due_at` DATETIME,
    `customer_signed` TINYINT(1) DEFAULT 0,
    `customer_signed_at` DATETIME,
    `customer_signature_url` TEXT,
    `customer_signed_ip` VARCHAR(45),
    `transport_signed` TINYINT(1) DEFAULT 0,
    `transport_signed_at` DATETIME,
    `transport_signature_url` TEXT,
    `transport_signed_ip` VARCHAR(45),
    `status` VARCHAR(255) NOT NULL DEFAULT 'DRAFT',
    `created_at` DATETIME,
    `updated_at` DATETIME,
    PRIMARY KEY (`contract_id`),
    UNIQUE KEY `uk_contracts_booking_id` (`booking_id`),
    UNIQUE KEY `uk_contracts_contract_number` (`contract_number`),
    CONSTRAINT `fk_contracts_booking` FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`booking_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_contracts_quotation` FOREIGN KEY (`quotation_id`) REFERENCES `quotations` (`quotation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Settings & Preferences
CREATE TABLE IF NOT EXISTS `customer_settings` (
    `customer_id` BIGINT NOT NULL,
    `language` VARCHAR(10) DEFAULT 'vi',
    `email_notifications` TINYINT(1) DEFAULT 1,
    `booking_updates` TINYINT(1) DEFAULT 1,
    `quotation_alerts` TINYINT(1) DEFAULT 1,
    `promotions` TINYINT(1) DEFAULT 0,
    `newsletter` TINYINT(1) DEFAULT 0,
    `profile_visibility` VARCHAR(20) DEFAULT 'public',
    `show_phone` TINYINT(1) DEFAULT 1,
    `show_email` TINYINT(1) DEFAULT 0,
    `updated_at` DATETIME,
    PRIMARY KEY (`customer_id`),
    CONSTRAINT `fk_customer_settings_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`customer_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Admin Settings
CREATE TABLE IF NOT EXISTS `admin_settings` (
    `manager_id` BIGINT NOT NULL,
    `full_name` VARCHAR(255),
    `phone` VARCHAR(20),
    `email_notifications` TINYINT(1) DEFAULT 1,
    `system_alerts` TINYINT(1) DEFAULT 1,
    `user_registrations` TINYINT(1) DEFAULT 1,
    `transport_verifications` TINYINT(1) DEFAULT 1,
    `booking_alerts` TINYINT(1) DEFAULT 0,
    `review_moderation` TINYINT(1) DEFAULT 1,
    `two_factor_enabled` TINYINT(1) DEFAULT 0,
    `session_timeout_minutes` INT DEFAULT 30,
    `login_notifications` TINYINT(1) DEFAULT 1,
    `theme` VARCHAR(10) DEFAULT 'light',
    `date_format` VARCHAR(20) DEFAULT 'DD/MM/YYYY',
    `timezone` VARCHAR(100) DEFAULT 'Asia/Ho_Chi_Minh',
    `maintenance_mode` TINYINT(1) DEFAULT 0,
    `auto_backup` TINYINT(1) DEFAULT 1,
    `backup_frequency` VARCHAR(10) DEFAULT 'daily',
    `email_provider` VARCHAR(20) DEFAULT 'smtp',
    `smtp_host` VARCHAR(255),
    `smtp_port` VARCHAR(10),
    `smtp_username` VARCHAR(255),
    `smtp_password` TEXT,
    `updated_at` DATETIME,
    PRIMARY KEY (`manager_id`),
    CONSTRAINT `fk_admin_settings_manager` FOREIGN KEY (`manager_id`) REFERENCES `managers` (`manager_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notification_preferences` (
    `preference_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `email_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `sms_enabled` TINYINT(1) NOT NULL DEFAULT 0,
    `push_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `in_app_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `quiet_hours_start` TIME,
    `quiet_hours_end` TIME,
    `booking_updates_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `quotation_alerts_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `payment_reminders_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `system_alerts_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `promotions_enabled` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`preference_id`),
    UNIQUE KEY `uk_notification_preferences_user_id` (`user_id`),
    CONSTRAINT `fk_notification_preferences_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Intake Sessions
CREATE TABLE IF NOT EXISTS `intake_sessions` (
    `session_id` VARCHAR(100) NOT NULL,
    `user_id` BIGINT,
    `status` VARCHAR(20) NOT NULL DEFAULT 'active',
    `total_items` INT DEFAULT 0,
    `estimated_volume` DECIMAL(10, 2),
    `ai_service_used` VARCHAR(50),
    `average_confidence` DECIMAL(5, 4),
    `metadata` JSON,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME,
    `expires_at` DATETIME,
    PRIMARY KEY (`session_id`),
    CONSTRAINT `fk_intake_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `intake_session_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(100) NOT NULL,
    `item_id` VARCHAR(100),
    `name` VARCHAR(255) NOT NULL,
    `category` VARCHAR(100),
    `description` TEXT,
    `quantity` INT NOT NULL DEFAULT 1,
    `length_cm` DECIMAL(10, 2),
    `width_cm` DECIMAL(10, 2),
    `height_cm` DECIMAL(10, 2),
    `weight_kg` DECIMAL(10, 2),
    `volume_m3` DECIMAL(10, 4),
    `is_fragile` TINYINT(1) DEFAULT 0,
    `is_high_value` TINYINT(1) DEFAULT 0,
    `requires_disassembly` TINYINT(1) DEFAULT 0,
    `image_url` TEXT,
    `confidence` DECIMAL(5, 4),
    `ai_detected` TINYINT(1) DEFAULT 0,
    `source` VARCHAR(50),
    `notes` TEXT,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_intake_session_items_session` FOREIGN KEY (`session_id`) REFERENCES `intake_sessions` (`session_id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ============================================================================
-- 8. SEED DATA (ADMIN USER)
-- ============================================================================

-- Default Admin: quandotri2@gmail.com / password
INSERT IGNORE INTO `users` (`email`, `password_hash`, `role`, `is_active`, `is_verified`, `created_at`) VALUES 
('quandotri2@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'MANAGER', TRUE, TRUE, NOW());

SET @admin_id = (SELECT `user_id` FROM `users` WHERE `email` = 'quandotri2@gmail.com');

INSERT IGNORE INTO `managers` (`manager_id`, `full_name`, `phone`, `department`, `employee_id`, `permissions`) VALUES 
(@admin_id, 'System Administrator', '0900000000', 'IT', 'ADMIN001', '["ALL"]');

SET SQL_MODE = @OLD_SQL_MODE;
SET FOREIGN_KEY_CHECKS = @OLD_FOREIGN_KEY_CHECKS;
SET UNIQUE_CHECKS = @OLD_UNIQUE_CHECKS;
