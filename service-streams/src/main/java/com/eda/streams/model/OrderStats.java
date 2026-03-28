package com.eda.streams.model;

import java.math.BigDecimal;

/**
 * 윈도우별 주문 통계 집계 결과.
 *
 * KTable의 Value로 사용된다.
 * Kafka Streams의 aggregate()는 이 객체를 "누적"하며 갱신한다.
 */
public record OrderStats(
        long orderCount,
        BigDecimal totalRevenue,
        BigDecimal avgOrderAmount
) {
    /** 집계 초기값 — aggregate()의 initializer */
    public static OrderStats ZERO = new OrderStats(0, BigDecimal.ZERO, BigDecimal.ZERO);

    /** 새 주문을 누적하여 새 통계를 반환 (불변 객체) */
    public OrderStats add(BigDecimal amount) {
        long newCount = orderCount + 1;
        BigDecimal newRevenue = totalRevenue.add(amount);
        BigDecimal newAvg = newRevenue.divide(
                BigDecimal.valueOf(newCount), 2, java.math.RoundingMode.HALF_UP);
        return new OrderStats(newCount, newRevenue, newAvg);
    }
}
