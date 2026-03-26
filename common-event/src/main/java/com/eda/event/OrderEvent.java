package com.eda.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 관련 이벤트.
 * 모든 서비스가 이 이벤트 스키마를 공유한다.
 */
public record OrderEvent(
        String eventId,
        String orderId,
        String userId,
        EventType eventType,
        BigDecimal totalAmount,
        int itemCount,
        LocalDateTime occurredAt
) {
    public enum EventType {
        ORDER_CREATED,
        ORDER_CANCELLED
    }
}
