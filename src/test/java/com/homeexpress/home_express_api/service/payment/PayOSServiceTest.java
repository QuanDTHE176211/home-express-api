package com.homeexpress.home_express_api.service.payment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import vn.payos.PayOS;

class PayOSServiceTest {

    @Test
    void generateOrderCodeShouldStayWithinGatewayLimit() {
        PayOSService service = new PayOSService(mock(PayOS.class));

        long code = service.generateOrderCode(1_000_000_000L);

        assertTrue(code <= 999_999_999_999_999L, "orderCode must not exceed gateway limit");
        assertTrue(code > 0, "orderCode must be positive");
    }
}
