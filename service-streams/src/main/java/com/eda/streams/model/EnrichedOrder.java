package com.eda.streams.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 + 결제 정보를 조인한 결과.
 *
 * KStream-KStream Join의 결과로 생성된다.
 * 두 토픽의 이벤트가 같은 orderId를 키로 조인되어 하나의 enriched 이벤트가 된다.
 */
public record EnrichedOrder(
        String orderId,
        String userId,
        BigDecimal totalAmount,
        int itemCount,
        String paymentStatus,
        String paymentEventId,
        LocalDateTime orderCreatedAt,
        LocalDateTime paymentCompletedAt,
        long processingTimeMs
) {}
