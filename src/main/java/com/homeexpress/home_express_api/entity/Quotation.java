package com.homeexpress.home_express_api.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureParameter;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Entity
@Table(name = "quotations")
@NamedStoredProcedureQuery(
        name = "sp_accept_quotation",
        procedureName = "sp_accept_quotation",
        parameters = {
            @StoredProcedureParameter(mode = ParameterMode.IN, name = "p_quotation_id", type = Long.class),
            @StoredProcedureParameter(mode = ParameterMode.IN, name = "p_customer_id", type = Long.class),
            @StoredProcedureParameter(mode = ParameterMode.IN, name = "p_ip_address", type = String.class)
        }
)
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quotation_id")
    private Long quotationId;

    @NotNull
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @NotNull
    @Column(name = "transport_id", nullable = false)
    private Long transportId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @NotNull
    @Positive
    @Column(name = "quoted_price", nullable = false, precision = 12, scale = 0)
    private BigDecimal quotedPrice;

    @Column(name = "base_price", precision = 12, scale = 0)
    private BigDecimal basePrice;

    @Column(name = "distance_price", precision = 12, scale = 0)
    private BigDecimal distancePrice;

    @Column(name = "items_price", precision = 12, scale = 0)
    private BigDecimal itemsPrice;

    @Column(name = "additional_fees", precision = 12, scale = 0)
    private BigDecimal additionalFees;

    @Column(name = "discount", precision = 12, scale = 0)
    private BigDecimal discount;

    @Column(name = "price_breakdown", columnDefinition = "JSON")
    private String priceBreakdown;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "validity_period")
    private Integer validityPeriod = 7;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QuotationStatus status = QuotationStatus.PENDING;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_ip", length = 45)
    private String acceptedIp;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;

    // Constructors
    public Quotation() {
    }

    // Getters and Setters
    public Long getQuotationId() {
        return quotationId;
    }

    public void setQuotationId(Long quotationId) {
        this.quotationId = quotationId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getTransportId() {
        return transportId;
    }

    public void setTransportId(Long transportId) {
        this.transportId = transportId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public BigDecimal getQuotedPrice() {
        return quotedPrice;
    }

    public void setQuotedPrice(BigDecimal quotedPrice) {
        this.quotedPrice = quotedPrice;
    }

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

    public BigDecimal getItemsPrice() {
        return itemsPrice;
    }

    public void setItemsPrice(BigDecimal itemsPrice) {
        this.itemsPrice = itemsPrice;
    }

    public BigDecimal getAdditionalFees() {
        return additionalFees;
    }

    public void setAdditionalFees(BigDecimal additionalFees) {
        this.additionalFees = additionalFees;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public String getPriceBreakdown() {
        return priceBreakdown;
    }

    public void setPriceBreakdown(String priceBreakdown) {
        this.priceBreakdown = priceBreakdown;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getValidityPeriod() {
        return validityPeriod;
    }

    public void setValidityPeriod(Integer validityPeriod) {
        this.validityPeriod = validityPeriod;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public QuotationStatus getStatus() {
        return status;
    }

    public void setStatus(QuotationStatus status) {
        this.status = status;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime respondedAt) {
        this.respondedAt = respondedAt;
    }

    public Long getAcceptedBy() {
        return acceptedBy;
    }

    public void setAcceptedBy(Long acceptedBy) {
        this.acceptedBy = acceptedBy;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public String getAcceptedIp() {
        return acceptedIp;
    }

    public void setAcceptedIp(String acceptedIp) {
        this.acceptedIp = acceptedIp;
    }


    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
