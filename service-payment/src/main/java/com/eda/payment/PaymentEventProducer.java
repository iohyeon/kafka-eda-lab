package com.eda.payment;

import com.eda.event.CorrelationContext;
import com.eda.event.PaymentEvent;
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
public class PaymentEventProducer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    public void publish(PaymentEvent event) {
        ProducerRecord<String, PaymentEvent> record =
                new ProducerRecord<>(Topics.PAYMENT_EVENTS, event.orderId(), event);

        // 이전 서비스에서 받은 Correlation ID를 다음 이벤트에도 전파
        String correlationId = CorrelationContext.get();
        if (correlationId != null) {
            record.headers().add(new RecordHeader(
                    CorrelationContext.HEADER_KEY,
                    correlationId.getBytes(StandardCharsets.UTF_8)));
        }

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Payment] 이벤트 발행 실패: correlationId={}, orderId={}",
                                correlationId, event.orderId(), ex);
                    } else {
                        log.info("[Payment] 이벤트 발행: correlationId={}, type={}, orderId={}",
                                correlationId, event.eventType(), event.orderId());
                    }
                });
    }
}
