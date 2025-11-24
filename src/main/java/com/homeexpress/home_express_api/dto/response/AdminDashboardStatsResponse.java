package com.homeexpress.home_express_api.dto.response;

import java.util.List;

public class AdminDashboardStatsResponse {
    private long totalUsers;
    private long totalCustomers;
    private long totalTransports;
    private long totalManagers;
    private long activeUsers;
    private long inactiveUsers;
    private long verifiedUsers;
    private long verifiedTransports;
    private long newUsersToday;
    private long newUsersThisWeek;
    private long newUsersThisMonth;
    private String userGrowthRate;
    private long pendingTransportVerifications;
    private List<TopTransportSummary> topTransports;

    public long getTotalUsers() {
        return totalUsers;
    }

    public void setTotalUsers(long totalUsers) {
        this.totalUsers = totalUsers;
    }

    public long getTotalCustomers() {
        return totalCustomers;
    }

    public void setTotalCustomers(long totalCustomers) {
        this.totalCustomers = totalCustomers;
    }

    public long getTotalTransports() {
        return totalTransports;
    }

    public void setTotalTransports(long totalTransports) {
        this.totalTransports = totalTransports;
    }

    public long getTotalManagers() {
        return totalManagers;
    }

    public void setTotalManagers(long totalManagers) {
        this.totalManagers = totalManagers;
    }

    public long getActiveUsers() {
        return activeUsers;
    }

    public void setActiveUsers(long activeUsers) {
        this.activeUsers = activeUsers;
    }

    public long getInactiveUsers() {
        return inactiveUsers;
    }

    public void setInactiveUsers(long inactiveUsers) {
        this.inactiveUsers = inactiveUsers;
    }

    public long getVerifiedUsers() {
        return verifiedUsers;
    }

    public void setVerifiedUsers(long verifiedUsers) {
        this.verifiedUsers = verifiedUsers;
    }

    public long getVerifiedTransports() {
        return verifiedTransports;
    }

    public void setVerifiedTransports(long verifiedTransports) {
        this.verifiedTransports = verifiedTransports;
    }

    public long getNewUsersToday() {
        return newUsersToday;
    }

    public void setNewUsersToday(long newUsersToday) {
        this.newUsersToday = newUsersToday;
    }

    public long getNewUsersThisWeek() {
        return newUsersThisWeek;
    }

    public void setNewUsersThisWeek(long newUsersThisWeek) {
        this.newUsersThisWeek = newUsersThisWeek;
    }

    public long getNewUsersThisMonth() {
        return newUsersThisMonth;
    }

    public void setNewUsersThisMonth(long newUsersThisMonth) {
        this.newUsersThisMonth = newUsersThisMonth;
    }

    public String getUserGrowthRate() {
        return userGrowthRate;
    }

    public void setUserGrowthRate(String userGrowthRate) {
        this.userGrowthRate = userGrowthRate;
    }

    public long getPendingTransportVerifications() {
        return pendingTransportVerifications;
    }

    public void setPendingTransportVerifications(long pendingTransportVerifications) {
        this.pendingTransportVerifications = pendingTransportVerifications;
    }

    public List<TopTransportSummary> getTopTransports() {
        return topTransports;
    }

    public void setTopTransports(List<TopTransportSummary> topTransports) {
        this.topTransports = topTransports;
    }

    public static class TopTransportSummary {
        private Long transportId;
        private String companyName;
        private Double averageRating;
        private Integer completedBookings;

        public Long getTransportId() {
            return transportId;
        }

        public void setTransportId(Long transportId) {
            this.transportId = transportId;
        }

        public String getCompanyName() {
            return companyName;
        }

        public void setCompanyName(String companyName) {
            this.companyName = companyName;
        }

        public Double getAverageRating() {
            return averageRating;
        }

        public void setAverageRating(Double averageRating) {
            this.averageRating = averageRating;
        }

        public Integer getCompletedBookings() {
            return completedBookings;
        }

        public void setCompletedBookings(Integer completedBookings) {
            this.completedBookings = completedBookings;
        }
    }
}

