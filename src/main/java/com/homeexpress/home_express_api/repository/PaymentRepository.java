package com.homeexpress.home_express_api.repository;

import com.homeexpress.home_express_api.entity.Payment;
import com.homeexpress.home_express_api.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByBookingId(Long bookingId);

    List<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    java.util.Optional<Payment> findByTransactionId(String transactionId);

    List<Payment> findByBookingIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
            Long bookingId, com.homeexpress.home_express_api.entity.PaymentType paymentType,
            java.util.Collection<PaymentStatus> statuses);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.bookingId = :bookingId AND p.status = :status")
    BigDecimal sumAmountByBookingIdAndStatus(
        @Param("bookingId") Long bookingId,
        @Param("status") PaymentStatus status
    );

    @Query("SELECT p FROM Payment p WHERE p.bookingId = :bookingId " +
           "ORDER BY p.createdAt ASC")
    List<Payment> findByBookingIdOrderByCreatedAtAsc(@Param("bookingId") Long bookingId);

    java.util.Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
