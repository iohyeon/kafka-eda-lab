package com.eda.event;

import java.time.LocalDateTime;

public record InventoryEvent(
        String eventId,
        String orderId,
        EventType eventType,
        int itemCount,
        String reason,
        LocalDateTime occurredAt
) {
    public enum EventType {
        INVENTORY_DEDUCTED,
        INVENTORY_FAILED,
        INVENTORY_RESTORED
    }
}
