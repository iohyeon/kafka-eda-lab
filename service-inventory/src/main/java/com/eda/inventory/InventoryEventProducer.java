package com.eda.inventory;

import com.eda.event.CorrelationContext;
import com.eda.event.InventoryEvent;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final KafkaTemplate<String, InventoryEvent> kafkaTemplate;

    public void publish(InventoryEvent event) {
        ProducerRecord<String, InventoryEvent> record =
                new ProducerRecord<>(Topics.INVENTORY_EVENTS, event.orderId(), event);

        String correlationId = CorrelationContext.get();
        if (correlationId != null) {
            record.headers().add(new RecordHeader(
                    CorrelationContext.HEADER_KEY,
                    correlationId.getBytes(StandardCharsets.UTF_8)));
        }

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Inventory] 이벤트 발행 실패: correlationId={}, orderId={}",
                                correlationId, event.orderId(), ex);
                    } else {
                        log.info("[Inventory] 이벤트 발행: correlationId={}, type={}, orderId={}",
                                correlationId, event.eventType(), event.orderId());
                    }
                });
    }
}
