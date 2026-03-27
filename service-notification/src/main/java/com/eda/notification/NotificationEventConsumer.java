package com.eda.notification;

import com.eda.event.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 코레오그래피 Saga — 알림 서비스.
 *
 * 알림은 보상이 불가능한 동작이다 (이미 보낸 알림은 회수 불가).
 * 그래서 Saga의 마지막 단계에 배치한다.
 *
 * 재고 차감까지 성공한 후에만 알림을 보낸다.
 * → 결제 성공 + 재고 성공이 확인된 후이므로 보상이 발생할 가능성이 낮다.
 */
@Slf4j
@Component
public class NotificationEventConsumer {

    @KafkaListener(
            topics = Topics.INVENTORY_EVENTS,
            groupId = "notification-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleInventoryDeducted(ConsumerRecord<String, InventoryEvent> record, Acknowledgment ack) {
        InventoryEvent event = record.value();
        String correlationId = CorrelationContext.extractFromHeaders(record.headers());

        if (event.eventType() != InventoryEvent.EventType.INVENTORY_DEDUCTED) {
            ack.acknowledge();
            CorrelationContext.clear();
            return;
        }

        log.info("[Notification] 알림 발송: correlationId={}, orderId={}", correlationId, event.orderId());

        sendNotification(event);

        ack.acknowledge();
    }

    private void sendNotification(InventoryEvent event) {
        log.info("[Notification] 주문 처리 완료 알림 발송: orderId={}", event.orderId());
    }
}
