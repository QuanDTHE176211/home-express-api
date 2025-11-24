package com.homeexpress.home_express_api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.query.Procedure;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.homeexpress.home_express_api.entity.Quotation;
import com.homeexpress.home_express_api.entity.QuotationStatus;

@Repository
public interface QuotationRepository extends JpaRepository<Quotation, Long> {

    List<Quotation> findByBookingId(Long bookingId);

    List<Quotation> findByBookingIdIn(Set<Long> bookingIds);

    Page<Quotation> findByBookingId(Long bookingId, Pageable pageable);

    Page<Quotation> findByTransportId(Long transportId, Pageable pageable);

    Page<Quotation> findByStatus(QuotationStatus status, Pageable pageable);

    Page<Quotation> findByBookingIdAndStatus(Long bookingId, QuotationStatus status, Pageable pageable);

    Page<Quotation> findByTransportIdAndStatus(Long transportId, QuotationStatus status, Pageable pageable);

    Optional<Quotation> findByQuotationIdAndBookingId(Long quotationId, Long bookingId);

    long countByTransportId(Long transportId);

    long countByBookingId(Long bookingId);

    long countByTransportIdAndStatus(Long transportId, QuotationStatus status);

    List<Quotation> findByTransportIdAndCreatedAtBetween(Long transportId, LocalDateTime start, LocalDateTime end);

    List<Quotation> findByTransportIdAndRespondedAtBetween(Long transportId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT q FROM Quotation q WHERE q.expiresAt < :now AND q.status = 'PENDING'")
    List<Quotation> findExpiredQuotations(@Param("now") LocalDateTime now);

    Optional<Quotation> findTopByBookingIdAndStatusOrderByAcceptedAtDesc(Long bookingId, QuotationStatus status);

    @Procedure(name = "sp_accept_quotation")
    void acceptQuotation(
        @Param("p_quotation_id") Long quotationId,
        @Param("p_customer_id") Long customerId,
        @Param("p_ip_address") String ipAddress
    );

    boolean existsByBookingIdAndTransportId(Long bookingId, Long transportId);
}
