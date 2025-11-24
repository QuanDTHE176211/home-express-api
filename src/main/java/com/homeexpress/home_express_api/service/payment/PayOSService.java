package com.homeexpress.home_express_api.service.payment;

import com.homeexpress.home_express_api.entity.Booking;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.payos.PayOS;
import vn.payos.type.CheckoutResponseData;
import vn.payos.type.PaymentData;
import vn.payos.type.Webhook;
import vn.payos.type.WebhookData;

@Service
public class PayOSService {

    private static final long MAX_ORDER_CODE = 999_999_999_999_999L;
    private static final long BOOKING_ID_MOD = 100_000L;
    private static final long TIMESTAMP_MOD = 1_000_000_000L;
    private static final long BOOKING_FACTOR = 1_000_000_000L;

    private final PayOS payOS;

    @Value("${payos.return-url}")
    private String returnUrl;

    @Value("${payos.cancel-url}")
    private String cancelUrl;

    public PayOSService(PayOS payOS) {
        this.payOS = payOS;
    }

    public long generateOrderCode(Long bookingId) {
        long safeBookingId = bookingId != null ? bookingId : 0L;
        long compressedBooking = Math.abs(safeBookingId % BOOKING_ID_MOD);
        long timestampFragment = Math.abs(System.currentTimeMillis() % TIMESTAMP_MOD);
        long orderCode = compressedBooking * BOOKING_FACTOR + timestampFragment;

        if (orderCode > MAX_ORDER_CODE) {
            throw new IllegalStateException("Generated order code exceeds gateway limit");
        }

        return orderCode;
    }

    public CheckoutResponseData createDepositLink(Booking booking, BigDecimal depositAmount, long orderCode) throws Exception {
        if (booking == null) {
            throw new IllegalArgumentException("Booking is required to create PayOS link");
        }

        if (depositAmount == null || depositAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }

        String description = "COC DON " + booking.getBookingId();

        int amount = depositAmount.setScale(0, RoundingMode.CEILING).intValueExact();

        if (amount < 2000) {
            amount = 2000;
        }

        PaymentData paymentData = PaymentData.builder()
                .orderCode(orderCode)
                .amount(amount)
                .description(description)
                .returnUrl(returnUrl + "?bookingId=" + booking.getBookingId())
                .cancelUrl(cancelUrl + "?bookingId=" + booking.getBookingId())
                .build();

        return payOS.createPaymentLink(paymentData);
    }

    public WebhookData verifyWebhook(Webhook body) throws Exception {
        return payOS.verifyPaymentWebhookData(body);
    }
}
