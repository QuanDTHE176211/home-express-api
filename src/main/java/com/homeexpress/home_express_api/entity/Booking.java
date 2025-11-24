package com.homeexpress.home_express_api.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id")
    private Long bookingId;

    @NotNull
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "transport_id")
    private Long transportId;

    @NotBlank
    @Column(name = "pickup_address", nullable = false, columnDefinition = "TEXT")
    private String pickupAddress;

    @Column(name = "pickup_latitude")
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude")
    private BigDecimal pickupLongitude;

    @Column(name = "pickup_floor")
    private Integer pickupFloor;

    @Column(name = "pickup_has_elevator")
    private Boolean pickupHasElevator = false;

    @Column(name = "pickup_province_code", length = 6)
    private String pickupProvinceCode;

    @Column(name = "pickup_district_code", length = 6)
    private String pickupDistrictCode;

    @Column(name = "pickup_ward_code", length = 6)
    private String pickupWardCode;

    @NotBlank
    @Column(name = "delivery_address", nullable = false, columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(name = "delivery_latitude")
    private BigDecimal deliveryLatitude;

    @Column(name = "delivery_longitude")
    private BigDecimal deliveryLongitude;

    @Column(name = "delivery_floor")
    private Integer deliveryFloor;

    @Column(name = "delivery_has_elevator")
    private Boolean deliveryHasElevator = false;

    @Column(name = "delivery_province_code", length = 6)
    private String deliveryProvinceCode;

    @Column(name = "delivery_district_code", length = 6)
    private String deliveryDistrictCode;

    @Column(name = "delivery_ward_code", length = 6)
    private String deliveryWardCode;

    @NotNull
    @Column(name = "preferred_date", nullable = false)
    private LocalDate preferredDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_time_slot", length = 20)
    private TimeSlot preferredTimeSlot;

    @Column(name = "actual_start_time")
    private LocalDateTime actualStartTime;

    @Column(name = "actual_end_time")
    private LocalDateTime actualEndTime;

    @Column(name = "distance_km")
    private BigDecimal distanceKm;

    @Enumerated(EnumType.STRING)
    @Column(name = "distance_source", length = 20)
    private DistanceSource distanceSource;

    @Column(name = "distance_calculated_at")
    private LocalDateTime distanceCalculatedAt;

    @Column(name = "estimated_price", precision = 12)
    private BigDecimal estimatedPrice;

    @Column(name = "final_price", precision = 12)
    private BigDecimal finalPrice;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "special_requirements", columnDefinition = "TEXT")
    private String specialRequirements;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Booking() {
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getTransportId() {
        return transportId;
    }

    public void setTransportId(Long transportId) {
        this.transportId = transportId;
    }

    public String getPickupAddress() {
        return pickupAddress;
    }

    public void setPickupAddress(String pickupAddress) {
        this.pickupAddress = pickupAddress;
    }

    public BigDecimal getPickupLatitude() {
        return pickupLatitude;
    }

    public void setPickupLatitude(BigDecimal pickupLatitude) {
        this.pickupLatitude = pickupLatitude;
    }

    public BigDecimal getPickupLongitude() {
        return pickupLongitude;
    }

    public void setPickupLongitude(BigDecimal pickupLongitude) {
        this.pickupLongitude = pickupLongitude;
    }

    public Integer getPickupFloor() {
        return pickupFloor;
    }

    public void setPickupFloor(Integer pickupFloor) {
        this.pickupFloor = pickupFloor;
    }

    public Boolean getPickupHasElevator() {
        return pickupHasElevator;
    }

    public void setPickupHasElevator(Boolean pickupHasElevator) {
        this.pickupHasElevator = pickupHasElevator;
    }

    public String getPickupProvinceCode() {
        return pickupProvinceCode;
    }

    public void setPickupProvinceCode(String pickupProvinceCode) {
        this.pickupProvinceCode = pickupProvinceCode;
    }

    public String getPickupDistrictCode() {
        return pickupDistrictCode;
    }

    public void setPickupDistrictCode(String pickupDistrictCode) {
        this.pickupDistrictCode = pickupDistrictCode;
    }

    public String getPickupWardCode() {
        return pickupWardCode;
    }

    public void setPickupWardCode(String pickupWardCode) {
        this.pickupWardCode = pickupWardCode;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public BigDecimal getDeliveryLatitude() {
        return deliveryLatitude;
    }

    public void setDeliveryLatitude(BigDecimal deliveryLatitude) {
        this.deliveryLatitude = deliveryLatitude;
    }

    public BigDecimal getDeliveryLongitude() {
        return deliveryLongitude;
    }

    public void setDeliveryLongitude(BigDecimal deliveryLongitude) {
        this.deliveryLongitude = deliveryLongitude;
    }

    public Integer getDeliveryFloor() {
        return deliveryFloor;
    }

    public void setDeliveryFloor(Integer deliveryFloor) {
        this.deliveryFloor = deliveryFloor;
    }

    public Boolean getDeliveryHasElevator() {
        return deliveryHasElevator;
    }

    public void setDeliveryHasElevator(Boolean deliveryHasElevator) {
        this.deliveryHasElevator = deliveryHasElevator;
    }

    public String getDeliveryProvinceCode() {
        return deliveryProvinceCode;
    }

    public void setDeliveryProvinceCode(String deliveryProvinceCode) {
        this.deliveryProvinceCode = deliveryProvinceCode;
    }

    public String getDeliveryDistrictCode() {
        return deliveryDistrictCode;
    }

    public void setDeliveryDistrictCode(String deliveryDistrictCode) {
        this.deliveryDistrictCode = deliveryDistrictCode;
    }

    public String getDeliveryWardCode() {
        return deliveryWardCode;
    }

    public void setDeliveryWardCode(String deliveryWardCode) {
        this.deliveryWardCode = deliveryWardCode;
    }

    public LocalDate getPreferredDate() {
        return preferredDate;
    }

    public void setPreferredDate(LocalDate preferredDate) {
        this.preferredDate = preferredDate;
    }

    public TimeSlot getPreferredTimeSlot() {
        return preferredTimeSlot;
    }

    public void setPreferredTimeSlot(TimeSlot preferredTimeSlot) {
        this.preferredTimeSlot = preferredTimeSlot;
    }

    public LocalDateTime getActualStartTime() {
        return actualStartTime;
    }

    public void setActualStartTime(LocalDateTime actualStartTime) {
        this.actualStartTime = actualStartTime;
    }

    public LocalDateTime getActualEndTime() {
        return actualEndTime;
    }

    public void setActualEndTime(LocalDateTime actualEndTime) {
        this.actualEndTime = actualEndTime;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public DistanceSource getDistanceSource() {
        return distanceSource;
    }

    public void setDistanceSource(DistanceSource distanceSource) {
        this.distanceSource = distanceSource;
    }

    public LocalDateTime getDistanceCalculatedAt() {
        return distanceCalculatedAt;
    }

    public void setDistanceCalculatedAt(LocalDateTime distanceCalculatedAt) {
        this.distanceCalculatedAt = distanceCalculatedAt;
    }

    public BigDecimal getEstimatedPrice() {
        return estimatedPrice;
    }

    public void setEstimatedPrice(BigDecimal estimatedPrice) {
        this.estimatedPrice = estimatedPrice;
    }

    public BigDecimal getFinalPrice() {
        return finalPrice;
    }

    public void setFinalPrice(BigDecimal finalPrice) {
        this.finalPrice = finalPrice;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getSpecialRequirements() {
        return specialRequirements;
    }

    public void setSpecialRequirements(String specialRequirements) {
        this.specialRequirements = specialRequirements;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(Long cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
