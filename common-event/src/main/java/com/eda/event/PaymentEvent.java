package com.eda.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentEvent(
        String eventId,
        String orderId,
        String userId,
        EventType eventType,
        BigDecimal amount,
        String reason,
        LocalDateTime occurredAt
) {
    public enum EventType {
        PAYMENT_COMPLETED,
        PAYMENT_FAILED,
        PAYMENT_CANCELLED
    }
}
