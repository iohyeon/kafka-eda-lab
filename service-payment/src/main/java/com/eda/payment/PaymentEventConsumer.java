package com.eda.payment;

import com.eda.event.InventoryEvent;
import com.eda.event.OrderEvent;
import com.eda.event.PaymentEvent;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 코레오그래피 Saga — 결제 서비스.
 *
 * 정방향: "주문완료" 이벤트 구독 → 결제 처리 → "결제완료" 발행
 * 보상:   "재고차감실패" 이벤트 구독 → 결제 취소 → "결제취소완료" 발행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentEventProducer eventProducer;

    /**
     * 정방향 — 주문 이벤트를 듣고 결제 처리
     */
    @KafkaListener(
            topics = Topics.ORDER_EVENTS,
            groupId = "payment-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(ConsumerRecord<String, OrderEvent> record, Acknowledgment ack) {
        OrderEvent event = record.value();

        if (event.eventType() != OrderEvent.EventType.ORDER_CREATED) {
            ack.acknowledge();
            return;
        }

        log.info("[Payment] 결제 처리 시작: orderId={}, amount={}",
                event.orderId(), event.totalAmount());

        try {
            processPayment(event);

            // 결제 성공 → "결제완료" 이벤트 발행
            eventProducer.publish(new PaymentEvent(
                    UUID.randomUUID().toString(),
                    event.orderId(),
                    event.userId(),
                    PaymentEvent.EventType.PAYMENT_COMPLETED,
                    event.totalAmount(),
                    null,
                    LocalDateTime.now()
            ));

        } catch (Exception e) {
            log.error("[Payment] 결제 실패: orderId={}", event.orderId(), e);

            // 결제 실패 → "결제실패" 이벤트 발행
            eventProducer.publish(new PaymentEvent(
                    UUID.randomUUID().toString(),
                    event.orderId(),
                    event.userId(),
                    PaymentEvent.EventType.PAYMENT_FAILED,
                    event.totalAmount(),
                    e.getMessage(),
                    LocalDateTime.now()
            ));
        }

        ack.acknowledge();
    }

    /**
     * 보상 — 재고 차감 실패 이벤트를 듣고 결제 취소
     *
     * 코레오그래피 Saga의 핵심:
     * 재고 서비스가 "재고차감실패"를 발행하면,
     * 결제 서비스가 이것을 구독하여 스스로 결제를 취소한다.
     * 아무도 "결제 취소해"라고 지시하지 않는다. 각자 알아서 반응.
     */
    @KafkaListener(
            topics = Topics.INVENTORY_EVENTS,
            groupId = "payment-compensation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleInventoryFailed(ConsumerRecord<String, InventoryEvent> record, Acknowledgment ack) {
        InventoryEvent event = record.value();

        if (event.eventType() != InventoryEvent.EventType.INVENTORY_FAILED) {
            ack.acknowledge();
            return;
        }

        log.info("[Payment] 보상 시작 — 결제 취소: orderId={}, reason={}",
                event.orderId(), event.reason());

        cancelPayment(event.orderId());

        // 결제 취소 완료 → "결제취소완료" 이벤트 발행
        eventProducer.publish(new PaymentEvent(
                UUID.randomUUID().toString(),
                event.orderId(),
                null,
                PaymentEvent.EventType.PAYMENT_CANCELLED,
                null,
                "재고 부족으로 인한 결제 취소",
                LocalDateTime.now()
        ));

        ack.acknowledge();
    }

    private void processPayment(OrderEvent event) {
        log.info("[Payment] PG사 결제 요청: userId={}, amount={}",
                event.userId(), event.totalAmount());
    }

    private void cancelPayment(String orderId) {
        log.info("[Payment] PG사 결제 취소 요청: orderId={}", orderId);
    }
}
