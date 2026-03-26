package com.eda.event;

/**
 * Kafka 토픽명 상수.
 * 서비스 간 토픽명 불일치를 방지한다.
 */
public final class Topics {

    private Topics() {}

    public static final String ORDER_EVENTS = "order-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String INVENTORY_EVENTS = "inventory-events";
    public static final String NOTIFICATION_EVENTS = "notification-events";
}
