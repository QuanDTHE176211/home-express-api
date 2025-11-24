package com.homeexpress.home_express_api.dto.transport;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TransportEarningsStatsResponse {

    private Long totalEarnings;
    private Long currentBalance;
    private Long thisMonthEarnings;
    private Integer thisMonthBookings;
    private Long pendingAmount;
    private Integer pendingTransactions;
    private Long averagePerBooking;
    private Long totalBookings;
    private String growthRate;
    private List<MonthlyBreakdown> monthlyBreakdown = new ArrayList<>();

    public Long getTotalEarnings() {
        return totalEarnings;
    }

    public void setTotalEarnings(Long totalEarnings) {
        this.totalEarnings = totalEarnings;
    }

    public Long getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(Long currentBalance) {
        this.currentBalance = currentBalance;
    }

    public Long getThisMonthEarnings() {
        return thisMonthEarnings;
    }

    public void setThisMonthEarnings(Long thisMonthEarnings) {
        this.thisMonthEarnings = thisMonthEarnings;
    }

    public Integer getThisMonthBookings() {
        return thisMonthBookings;
    }

    public void setThisMonthBookings(Integer thisMonthBookings) {
        this.thisMonthBookings = thisMonthBookings;
    }

    public Long getPendingAmount() {
        return pendingAmount;
    }

    public void setPendingAmount(Long pendingAmount) {
        this.pendingAmount = pendingAmount;
    }

    public Integer getPendingTransactions() {
        return pendingTransactions;
    }

    public void setPendingTransactions(Integer pendingTransactions) {
        this.pendingTransactions = pendingTransactions;
    }

    public Long getAveragePerBooking() {
        return averagePerBooking;
    }

    public void setAveragePerBooking(Long averagePerBooking) {
        this.averagePerBooking = averagePerBooking;
    }

    public Long getTotalBookings() {
        return totalBookings;
    }

    public void setTotalBookings(Long totalBookings) {
        this.totalBookings = totalBookings;
    }

    public String getGrowthRate() {
        return growthRate;
    }

    public void setGrowthRate(String growthRate) {
        this.growthRate = growthRate;
    }

    public List<MonthlyBreakdown> getMonthlyBreakdown() {
        return monthlyBreakdown;
    }

    public void setMonthlyBreakdown(List<MonthlyBreakdown> monthlyBreakdown) {
        this.monthlyBreakdown = monthlyBreakdown;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class MonthlyBreakdown {
        private String month;
        private Long revenue;
        private Integer bookings;

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public Long getRevenue() {
            return revenue;
        }

        public void setRevenue(Long revenue) {
            this.revenue = revenue;
        }

        public Integer getBookings() {
            return bookings;
        }

        public void setBookings(Integer bookings) {
            this.bookings = bookings;
        }
    }
}
