package com.eda.payment;

import com.eda.event.PaymentEvent;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    public void publish(PaymentEvent event) {
        kafkaTemplate.send(Topics.PAYMENT_EVENTS, event.orderId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Payment] 이벤트 발행 실패: orderId={}", event.orderId(), ex);
                    } else {
                        log.info("[Payment] 이벤트 발행: type={}, orderId={}",
                                event.eventType(), event.orderId());
                    }
                });
    }
}
