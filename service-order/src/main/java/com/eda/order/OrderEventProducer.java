package com.eda.order;

import com.eda.event.OrderEvent;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 주문 이벤트를 Kafka에 발행한다.
 *
 * 코레오그래피 패턴 — 주문 서비스는 "주문이 생겼다"는 사실만 발행하고 끝.
 * 결제/재고/알림이 이 이벤트를 구독하는지 주문 서비스는 모른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public void publishOrderCreated(OrderEvent event) {
        // Key = orderId → 같은 주문의 이벤트는 같은 파티션으로 (순서 보장)
        kafkaTemplate.send(Topics.ORDER_EVENTS, event.orderId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Order] 이벤트 발행 실패: orderId={}", event.orderId(), ex);
                    } else {
                        log.info("[Order] 이벤트 발행 완료: orderId={}, partition={}, offset={}",
                                event.orderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
