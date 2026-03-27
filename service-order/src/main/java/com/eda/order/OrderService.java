package com.eda.order;

import com.eda.event.CorrelationContext;
import com.eda.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventProducer eventProducer;

    @Transactional
    public Order createOrder(String userId, BigDecimal totalAmount, int itemCount) {
        String orderId = UUID.randomUUID().toString();

        // Correlation ID 생성 — 이 주문에서 시작되는 모든 이벤트가 이 ID를 공유
        String correlationId = "corr-" + UUID.randomUUID().toString().substring(0, 8);
        CorrelationContext.set(correlationId);

        try {
            Order order = new Order(orderId, userId, totalAmount, itemCount);
            orderRepository.save(order);

            OrderEvent event = new OrderEvent(
                    UUID.randomUUID().toString(), orderId, userId,
                    OrderEvent.EventType.ORDER_CREATED,
                    totalAmount, itemCount, LocalDateTime.now()
            );
            eventProducer.publishOrderCreated(event);

            log.info("[Order] 주문 생성 완료: correlationId={}, orderId={}", correlationId, orderId);
            return order;
        } finally {
            CorrelationContext.clear();
        }
    }
}
