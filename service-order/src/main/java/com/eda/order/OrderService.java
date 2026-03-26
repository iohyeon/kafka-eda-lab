package com.eda.order;

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

        // 1. 주문 저장
        Order order = new Order(orderId, userId, totalAmount, itemCount);
        orderRepository.save(order);

        // 2. 이벤트 발행 — "주문이 생겼다" (코레오그래피)
        // 주문 서비스는 이후에 무슨 일이 일어나는지 모른다.
        // 결제가 되는지, 재고가 빠지는지, 알림이 가는지 — 관심 없음.
        OrderEvent event = new OrderEvent(
                UUID.randomUUID().toString(),
                orderId,
                userId,
                OrderEvent.EventType.ORDER_CREATED,
                totalAmount,
                itemCount,
                LocalDateTime.now()
        );
        eventProducer.publishOrderCreated(event);

        log.info("[Order] 주문 생성 완료: orderId={}", orderId);
        return order;
    }
}
