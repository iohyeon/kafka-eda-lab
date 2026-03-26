package com.eda.inventory;

import com.eda.event.InventoryEvent;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final KafkaTemplate<String, InventoryEvent> kafkaTemplate;

    public void publish(InventoryEvent event) {
        kafkaTemplate.send(Topics.INVENTORY_EVENTS, event.orderId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Inventory] 이벤트 발행 실패: orderId={}", event.orderId(), ex);
                    } else {
                        log.info("[Inventory] 이벤트 발행: type={}, orderId={}",
                                event.eventType(), event.orderId());
                    }
                });
    }
}
