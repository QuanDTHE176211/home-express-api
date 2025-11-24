package com.homeexpress.home_express_api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.Payment;
import com.homeexpress.home_express_api.entity.PaymentMethod;
import com.homeexpress.home_express_api.entity.PaymentStatus;
import com.homeexpress.home_express_api.entity.PaymentType;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.PaymentRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.PaymentService;
import com.homeexpress.home_express_api.service.payment.PayOSService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.payos.type.CheckoutResponseData;
import vn.payos.type.Webhook;
import vn.payos.type.WebhookData;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PayOSService payOSService;
    private final PaymentService paymentService;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public PaymentController(
            PayOSService payOSService,
            PaymentService paymentService,
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.payOSService = payOSService;
        this.paymentService = paymentService;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/create-deposit-link/{bookingId}")
    public ResponseEntity<?> createDepositLink(@PathVariable Long bookingId, Authentication authentication) {
        Payment payment = null;
        try {
            User user = AuthenticationUtils.getUser(authentication, userRepository);

            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new RuntimeException("Booking not found"));

            if (!booking.getCustomerId().equals(user.getUserId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You can only create payment links for your own bookings");
            }

            var existingDeposits = paymentRepository.findByBookingIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
                    bookingId,
                    PaymentType.DEPOSIT,
                    EnumSet.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING));

            if (!existingDeposits.isEmpty()) {
                Payment latest = existingDeposits.get(0);
                String existingLink = extractCheckoutUrl(latest.getMetadata());
                if (existingLink != null) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of(
                                    "message", "A deposit link is already pending for this booking",
                                    "paymentId", latest.getPaymentId(),
                                    "checkoutUrl", existingLink,
                                    "paymentLinkId", extractPaymentLinkId(latest.getMetadata())
                            ));
                }
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body("A deposit payment is already pending for this booking");
            }

            boolean depositAlreadyPaid = paymentRepository.findByBookingId(bookingId).stream()
                    .anyMatch(p -> PaymentType.DEPOSIT.equals(p.getPaymentType())
                            && PaymentStatus.COMPLETED.equals(p.getStatus()));
            if (depositAlreadyPaid) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body("Deposit has already been completed for this booking");
            }

            BigDecimal depositAmount = paymentService.calculateDepositAmount(booking);
            long orderCode = payOSService.generateOrderCode(bookingId);

            payment = new Payment();
            payment.setBookingId(bookingId);
            payment.setAmount(depositAmount);
            payment.setPaymentMethod(PaymentMethod.PAYOS);
            payment.setPaymentType(PaymentType.DEPOSIT);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setTransactionId(String.valueOf(orderCode));
            payment.setIdempotencyKey(String.valueOf(orderCode));
            paymentRepository.save(payment);

            CheckoutResponseData data = payOSService.createDepositLink(booking, depositAmount, orderCode);

            BigDecimal gatewayAmount = BigDecimal.valueOf(data.getAmount());
            payment.setAmount(gatewayAmount);
            payment.setMetadata(buildGatewayMetadata(gatewayAmount, orderCode, data.getPaymentLinkId(), data.getCheckoutUrl()));
            paymentRepository.save(payment);

            ObjectNode response = objectMapper.valueToTree(data);
            response.put("paymentId", payment.getPaymentId());
            response.put("bookingId", bookingId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (payment != null && payment.getPaymentId() != null) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureMessage(e.getMessage());
                paymentRepository.save(payment);
            }
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/webhook/payos")
    public ResponseEntity<String> handleWebhook(@RequestBody Webhook body) {
        try {
            WebhookData data = payOSService.verifyWebhook(body);

            if ("00".equals(data.getCode())) {
                paymentService.confirmPayOsPayment(
                        data.getOrderCode(),
                        data.getAmount(),
                        data.getPaymentLinkId(),
                        data.getReference());
            }

            return ResponseEntity.ok("Webhook received");
        } catch (Exception e) {
            log.error("PayOS webhook processing failed: {}", e.getMessage(), e);
            return ResponseEntity.ok("Webhook received with error: " + e.getMessage());
        }
    }

    private String buildGatewayMetadata(BigDecimal amount, long orderCode, String paymentLinkId, String checkoutUrl) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("gateway", "PayOS");
            metadata.put("orderCode", String.valueOf(orderCode));
            if (paymentLinkId != null) {
                metadata.put("paymentLinkId", paymentLinkId);
            }
            if (checkoutUrl != null) {
                metadata.put("checkoutUrl", checkoutUrl);
            }
            if (amount != null) {
                metadata.put("amount", amount.longValue());
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractCheckoutUrl(String metadataJson) {
        try {
            if (metadataJson == null) {
                return null;
            }
            return objectMapper.readTree(metadataJson).path("checkoutUrl").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPaymentLinkId(String metadataJson) {
        try {
            if (metadataJson == null) {
                return null;
            }
            return objectMapper.readTree(metadataJson).path("paymentLinkId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
