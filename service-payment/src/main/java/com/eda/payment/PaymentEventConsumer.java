package com.eda.payment;

import com.eda.event.OrderEvent;
import com.eda.event.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 코레오그래피 — 결제 서비스가 "주문완료" 이벤트를 구독.
 * 주문 서비스는 결제 서비스의 존재를 모른다.
 * 결제 서비스가 스스로 관심 있는 이벤트를 구독하여 반응한다.
 */
@Slf4j
@Component
public class PaymentEventConsumer {

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

        // 결제 처리 (실제로는 PG사 API 호출)
        processPayment(event);

        log.info("[Payment] 결제 처리 완료: orderId={}", event.orderId());

        // Manual Commit — EDA-07에서 배운 At-Least-Once
        ack.acknowledge();
    }

    private void processPayment(OrderEvent event) {
        // 실제 결제 로직이 들어갈 자리
        // 지금은 로그만 남긴다
        log.info("[Payment] PG사 결제 요청: userId={}, amount={}",
                event.userId(), event.totalAmount());
    }
}
