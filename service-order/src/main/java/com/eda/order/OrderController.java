package com.eda.order;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(
                request.userId(),
                request.totalAmount(),
                request.itemCount()
        );

        return ResponseEntity.ok(Map.of(
                "orderId", order.getOrderId(),
                "status", order.getStatus()
        ));
    }

    public record CreateOrderRequest(
            String userId,
            BigDecimal totalAmount,
            int itemCount
    ) {}
}
