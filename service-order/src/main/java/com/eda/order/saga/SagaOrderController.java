package com.eda.order.saga;

import com.eda.order.Order;
import com.eda.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 오케스트레이션 Saga용 API.
 * 코레오그래피(/api/orders)와 분리하여 두 패턴을 비교 실험할 수 있다.
 */
@RestController
@RequestMapping("/api/saga/orders")
@RequiredArgsConstructor
public class SagaOrderController {

    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator orchestrator;
    private final OrderSagaRepository sagaRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrderWithSaga(@RequestBody CreateSagaOrderRequest request) {
        String orderId = UUID.randomUUID().toString();

        // 주문 생성 (CREATED 상태)
        Order order = new Order(orderId, request.userId(), request.totalAmount(), request.itemCount());
        orderRepository.save(order);

        // Saga 시작 — 오케스트레이터가 전체 흐름을 관리
        orchestrator.startSaga(orderId, request.userId(), request.totalAmount(), request.itemCount());

        return ResponseEntity.ok(Map.of(
                "orderId", orderId,
                "status", order.getStatus(),
                "message", "Saga started — orchestrator is managing the flow"
        ));
    }

    @GetMapping("/{orderId}/saga-status")
    public ResponseEntity<Map<String, Object>> getSagaStatus(@PathVariable String orderId) {
        return sagaRepository.findByOrderId(orderId)
                .map(saga -> ResponseEntity.ok(Map.<String, Object>of(
                        "sagaId", saga.getSagaId(),
                        "orderId", saga.getOrderId(),
                        "currentStep", saga.getCurrentStep(),
                        "status", saga.getStatus(),
                        "failReason", saga.getFailReason() != null ? saga.getFailReason() : ""
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    public record CreateSagaOrderRequest(
            String userId,
            BigDecimal totalAmount,
            int itemCount
    ) {}
}
