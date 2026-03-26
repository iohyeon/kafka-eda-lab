package com.eda.notification;

import com.eda.event.OrderEvent;
import com.eda.event.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 코레오그래피 — 알림 서비스가 "주문완료" 이벤트를 구독.
 * 결제/재고와 독립적으로 동시에 반응한다.
 *
 * EDA-01에서 본 문제:
 * 동기 호출에서는 알림이 3초 걸리면 주문도 3초 걸렸다.
 * 코레오그래피에서는 주문 서비스는 이벤트 발행하고 끝.
 * 알림이 3초 걸리든 10초 걸리든 주문 서비스에 영향 없다.
 */
@Slf4j
@Component
public class NotificationEventConsumer {

    @KafkaListener(
            topics = Topics.ORDER_EVENTS,
            groupId = "notification-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(ConsumerRecord<String, OrderEvent> record, Acknowledgment ack) {
        OrderEvent event = record.value();

        if (event.eventType() != OrderEvent.EventType.ORDER_CREATED) {
            ack.acknowledge();
            return;
        }

        log.info("[Notification] 알림 발송: orderId={}, userId={}",
                event.orderId(), event.userId());

        sendNotification(event);

        ack.acknowledge();
    }

    private void sendNotification(OrderEvent event) {
        // 실제로는 카카오톡, SMS, 이메일 등
        log.info("[Notification] 주문 알림 발송 완료: userId={}, orderId={}",
                event.userId(), event.orderId());
    }
}
