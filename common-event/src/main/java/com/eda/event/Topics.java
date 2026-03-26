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

    // 오케스트레이션 Saga 전용 토픽
    public static final String SAGA_PAYMENT_COMMAND = "saga-payment-command";
    public static final String SAGA_INVENTORY_COMMAND = "saga-inventory-command";
    public static final String SAGA_NOTIFICATION_COMMAND = "saga-notification-command";
    public static final String SAGA_REPLY = "saga-reply";
}
