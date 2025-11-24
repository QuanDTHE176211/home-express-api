package com.homeexpress.home_express_api.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking_status_history")
public class BookingStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 32)
    private BookingStatus oldStatus;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 32)
    private BookingStatus newStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_role", length = 20)
    private ActorRole changedByRole;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    @NotNull
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    public BookingStatusHistory() {
    }

    public BookingStatusHistory(Long bookingId, BookingStatus oldStatus, BookingStatus newStatus, Long changedBy, ActorRole changedByRole) {
        this.bookingId = bookingId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.changedByRole = changedByRole;
        this.changedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public BookingStatus getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(BookingStatus oldStatus) {
        this.oldStatus = oldStatus;
    }

    public BookingStatus getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(BookingStatus newStatus) {
        this.newStatus = newStatus;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(Long changedBy) {
        this.changedBy = changedBy;
    }

    public ActorRole getChangedByRole() {
        return changedByRole;
    }

    public void setChangedByRole(ActorRole changedByRole) {
        this.changedByRole = changedByRole;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
