package com.homeexpress.home_express_api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Payment Configuration
 *
 * Manages payment-related settings including bank information and deposit percentages.
 * All values are loaded from application.yml under the 'payment' prefix.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "payment")
public class PaymentConfig {

    private BankInfo bank = new BankInfo();
    private Double depositPercentage;

    @Data
    public static class BankInfo {
        private String bank;
        private String accountNumber;
        private String accountName;
        private String branch;
    }
}
