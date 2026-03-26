package com.eda.event;

import java.time.LocalDateTime;

/**
 * 각 서비스가 커맨드 처리 후 오케스트레이터에게 보내는 응답.
 */
public record SagaReply(
        String sagaId,
        String orderId,
        ReplyType replyType,
        String reason,
        LocalDateTime repliedAt
) {
    public enum ReplyType {
        PAYMENT_SUCCESS,
        PAYMENT_FAILED,
        PAYMENT_CANCELLED,
        INVENTORY_SUCCESS,
        INVENTORY_FAILED,
        INVENTORY_RESTORED,
        NOTIFICATION_SENT
    }
}
