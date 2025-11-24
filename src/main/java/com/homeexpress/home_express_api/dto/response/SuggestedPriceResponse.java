package com.homeexpress.home_express_api.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SuggestedPriceResponse {

    private BigDecimal suggestedPrice;
    private PriceBreakdown priceBreakdown;
    private Long rateCardId;
    private Long vehiclePricingId;
    private Long categoryId;
    private LocalDateTime calculationTimestamp;

    public BigDecimal getSuggestedPrice() {
        return suggestedPrice;
    }

    public void setSuggestedPrice(BigDecimal suggestedPrice) {
        this.suggestedPrice = suggestedPrice;
    }

    public PriceBreakdown getPriceBreakdown() {
        return priceBreakdown;
    }

    public void setPriceBreakdown(PriceBreakdown priceBreakdown) {
        this.priceBreakdown = priceBreakdown;
    }

    public Long getRateCardId() {
        return rateCardId;
    }

    public void setRateCardId(Long rateCardId) {
        this.rateCardId = rateCardId;
    }

    public Long getVehiclePricingId() {
        return vehiclePricingId;
    }

    public void setVehiclePricingId(Long vehiclePricingId) {
        this.vehiclePricingId = vehiclePricingId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public LocalDateTime getCalculationTimestamp() {
        return calculationTimestamp;
    }

    public void setCalculationTimestamp(LocalDateTime calculationTimestamp) {
        this.calculationTimestamp = calculationTimestamp;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PriceBreakdown {

        private BigDecimal basePrice;
        private BigDecimal distancePrice;
        private BigDecimal timePrice;
        private Map<String, BigDecimal> multipliers;
        private boolean minimumChargeApplied;

        public BigDecimal getBasePrice() {
            return basePrice;
        }

        public void setBasePrice(BigDecimal basePrice) {
            this.basePrice = basePrice;
        }

        public BigDecimal getDistancePrice() {
            return distancePrice;
        }

        public void setDistancePrice(BigDecimal distancePrice) {
            this.distancePrice = distancePrice;
        }

        public BigDecimal getTimePrice() {
            return timePrice;
        }

        public void setTimePrice(BigDecimal timePrice) {
            this.timePrice = timePrice;
        }

        public Map<String, BigDecimal> getMultipliers() {
            return multipliers;
        }

        public void setMultipliers(Map<String, BigDecimal> multipliers) {
            this.multipliers = multipliers;
        }

        public boolean isMinimumChargeApplied() {
            return minimumChargeApplied;
        }

        public void setMinimumChargeApplied(boolean minimumChargeApplied) {
            this.minimumChargeApplied = minimumChargeApplied;
        }
    }
}

