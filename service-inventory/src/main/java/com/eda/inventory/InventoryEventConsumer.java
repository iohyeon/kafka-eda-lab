package com.eda.inventory;

import com.eda.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 코레오그래피 Saga — 재고 서비스.
 *
 * 정방향: "결제완료" 이벤트 구독 → 재고 차감 → "재고차감완료" 발행
 * 실패:   재고 부족 시 → "재고차감실패" 발행 → 결제 서비스가 보상
 *
 * 주의: 순서가 바뀌었다.
 * 이전(코레오그래피만): 주문완료 → 결제/재고/알림 동시 구독
 * Saga 적용 후:         주문완료 → 결제 → 결제완료 → 재고 → 재고완료 → 알림
 * → 순서가 있어야 보상 체인이 동작하기 때문.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {

    private final InventoryEventProducer eventProducer;

    // 시뮬레이션용 재고 (실제로는 DB)
    private int stock = 10;

    /**
     * 정방향 — 결제 완료 이벤트를 듣고 재고 차감
     */
    @KafkaListener(
            topics = Topics.PAYMENT_EVENTS,
            groupId = "inventory-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentCompleted(ConsumerRecord<String, PaymentEvent> record, Acknowledgment ack) {
        PaymentEvent event = record.value();
        String correlationId = CorrelationContext.extractFromHeaders(record.headers());

        if (event.eventType() != PaymentEvent.EventType.PAYMENT_COMPLETED) {
            ack.acknowledge();
            CorrelationContext.clear();
            return;
        }

        log.info("[Inventory] 재고 차감 시작: correlationId={}, orderId={}, 현재재고={}", correlationId, event.orderId(), stock);

        try {
            deductStock(event);

            // 재고 차감 성공
            eventProducer.publish(new InventoryEvent(
                    UUID.randomUUID().toString(),
                    event.orderId(),
                    InventoryEvent.EventType.INVENTORY_DEDUCTED,
                    1,
                    null,
                    LocalDateTime.now()
            ));

        } catch (IllegalStateException e) {
            log.error("[Inventory] 재고 부족: orderId={}", event.orderId());

            // 재고 차감 실패 → "재고차감실패" 발행
            // 이 이벤트를 결제 서비스가 구독하여 결제를 취소(보상)한다
            eventProducer.publish(new InventoryEvent(
                    UUID.randomUUID().toString(),
                    event.orderId(),
                    InventoryEvent.EventType.INVENTORY_FAILED,
                    0,
                    e.getMessage(),
                    LocalDateTime.now()
            ));
        }

        ack.acknowledge();
    }

    private void deductStock(PaymentEvent event) {
        if (stock <= 0) {
            throw new IllegalStateException("재고 부족: 현재재고=" + stock);
        }
        stock--;
        log.info("[Inventory] 재고 차감 완료: 남은재고={}", stock);
    }
}
