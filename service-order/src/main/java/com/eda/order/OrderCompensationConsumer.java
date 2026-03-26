package com.eda.order;

import com.eda.event.PaymentEvent;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 코레오그래피 Saga — 주문 서비스의 보상 처리.
 *
 * "결제취소완료" 이벤트를 구독하여 주문을 취소한다.
 * 아무도 "주문 취소해"라고 지시하지 않는다. 각자 알아서 반응.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCompensationConsumer {

    private final OrderRepository orderRepository;

    @KafkaListener(
            topics = Topics.PAYMENT_EVENTS,
            groupId = "order-compensation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentCancelled(ConsumerRecord<String, PaymentEvent> record, Acknowledgment ack) {
        PaymentEvent event = record.value();

        if (event.eventType() != PaymentEvent.EventType.PAYMENT_CANCELLED) {
            ack.acknowledge();
            return;
        }

        log.info("[Order] 보상 시작 — 주문 취소: orderId={}, reason={}",
                event.orderId(), event.reason());

        orderRepository.findByOrderId(event.orderId())
                .ifPresent(order -> {
                    order.cancel();
                    orderRepository.save(order);
                    log.info("[Order] 주문 취소 완료: orderId={}, status={}",
                            order.getOrderId(), order.getStatus());
                });

        ack.acknowledge();
    }
}
