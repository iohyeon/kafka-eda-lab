package com.eda.order;

import com.eda.event.CorrelationContext;
import com.eda.event.OrderEvent;
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
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public void publishOrderCreated(OrderEvent event) {
        ProducerRecord<String, OrderEvent> record =
                new ProducerRecord<>(Topics.ORDER_EVENTS, event.orderId(), event);

        // Correlation ID를 Kafka 헤더에 주입
        String correlationId = CorrelationContext.get();
        if (correlationId != null) {
            record.headers().add(new RecordHeader(
                    CorrelationContext.HEADER_KEY,
                    correlationId.getBytes(StandardCharsets.UTF_8)));
        }

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Order] 이벤트 발행 실패: correlationId={}, orderId={}",
                                correlationId, event.orderId(), ex);
                    } else {
                        log.info("[Order] 이벤트 발행 완료: correlationId={}, orderId={}, partition={}, offset={}",
                                correlationId, event.orderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
