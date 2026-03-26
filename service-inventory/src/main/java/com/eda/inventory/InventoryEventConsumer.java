package com.eda.inventory;

import com.eda.event.OrderEvent;
import com.eda.event.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 코레오그래피 — 재고 서비스가 "주문완료" 이벤트를 구독.
 * 결제 서비스와 독립적으로, 동시에 반응한다.
 */
@Slf4j
@Component
public class InventoryEventConsumer {

    @KafkaListener(
            topics = Topics.ORDER_EVENTS,
            groupId = "inventory-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(ConsumerRecord<String, OrderEvent> record, Acknowledgment ack) {
        OrderEvent event = record.value();

        if (event.eventType() != OrderEvent.EventType.ORDER_CREATED) {
            ack.acknowledge();
            return;
        }

        log.info("[Inventory] 재고 차감 시작: orderId={}, itemCount={}",
                event.orderId(), event.itemCount());

        deductStock(event);

        log.info("[Inventory] 재고 차감 완료: orderId={}", event.orderId());

        ack.acknowledge();
    }

    private void deductStock(OrderEvent event) {
        log.info("[Inventory] 재고 차감 처리: itemCount={}", event.itemCount());
    }
}
