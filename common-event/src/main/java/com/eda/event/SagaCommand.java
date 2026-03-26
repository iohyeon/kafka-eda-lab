package com.eda.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 오케스트레이션 Saga에서 오케스트레이터가 각 서비스에게 보내는 커맨드.
 *
 * 이벤트(Event) vs 커맨드(Command):
 * - 이벤트: "주문이 생겼다" (사실 통보, 누가 듣든 상관없음)
 * - 커맨드: "결제해라" (지시, 특정 서비스에게 보냄)
 */
public record SagaCommand(
        String sagaId,
        String orderId,
        String userId,
        CommandType commandType,
        BigDecimal amount,
        int itemCount,
        LocalDateTime issuedAt
) {
    public enum CommandType {
        // 정방향 커맨드
        PROCESS_PAYMENT,
        DEDUCT_INVENTORY,
        SEND_NOTIFICATION,
        // 보상 커맨드
        CANCEL_PAYMENT,
        RESTORE_INVENTORY
    }
}
