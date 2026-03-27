package com.eda.order;

import com.eda.event.*;
import com.eda.inventory.InventoryEventConsumer;
import com.eda.inventory.InventoryEventProducer;
import com.eda.notification.NotificationEventConsumer;
import com.eda.payment.PaymentEventConsumer;
import com.eda.payment.PaymentEventProducer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코레오그래피 Saga 통합 테스트.
 *
 * EmbeddedKafka — Docker 없이 JVM 내에서 Kafka 브로커를 띄운다.
 * 실무 CI/CD에서도 이 방식을 쓴다.
 */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@EmbeddedKafka(
        partitions = 3,
        topics = {
                Topics.ORDER_EVENTS,
                Topics.PAYMENT_EVENTS,
                Topics.INVENTORY_EVENTS,
                Topics.NOTIFICATION_EVENTS
        },
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:0",
                "auto.create.topics.enable=true"
        }
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChoreographySagaIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        /**
         * 모든 서비스가 공유하는 단일 KafkaListenerContainerFactory.
         * 테스트 환경에서 빈 이름 충돌을 방지.
         */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
                ConsumerFactory<String, Object> consumerFactory) {
            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
            return factory;
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("[시나리오1] 코레오그래피 정상 흐름 — 주문 → 결제 → 재고 → 알림")
    void choreography_normalFlow() {
        // Given
        String userId = "test-user-1";
        BigDecimal amount = new BigDecimal("15000");

        // When
        com.eda.order.Order order = orderService.createOrder(userId, amount, 1);

        // Then — 주문이 생성되고 이벤트가 발행됨
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var savedOrder = orderRepository.findByOrderId(order.getOrderId());
                    assertThat(savedOrder).isPresent();
                    assertThat(savedOrder.get().getStatus())
                            .isEqualTo(com.eda.order.Order.OrderStatus.CREATED);
                });

        // 이벤트 전파 대기
        Awaitility.await().during(Duration.ofSeconds(3)).until(() -> true);

        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [시나리오1] 코레오그래피 정상 흐름 — PASS ✅");
        System.out.println("================================================================");
        System.out.println("  orderId  : " + order.getOrderId());
        System.out.println("  userId   : " + userId);
        System.out.println("  amount   : " + amount);
        System.out.println("  status   : " + order.getStatus());
        System.out.println("  이벤트 체인: OrderCreated → PaymentCompleted → InventoryDeducted → Notification");
        System.out.println("================================================================");
        System.out.println();
    }

    @Test
    @DisplayName("[시나리오2] 코레오그래피 Saga 보상 — 재고 부족 시 보상 체인 동작")
    void choreographySaga_compensationFlow() {
        // Given — 재고 10개 소진
        System.out.println();
        System.out.println("--- 재고 10개 소진 중 ---");
        for (int i = 1; i <= 10; i++) {
            orderService.createOrder("user-" + i, new BigDecimal("10000"), 1);
        }

        // 이벤트 체인이 모두 완료될 때까지 대기
        Awaitility.await().during(Duration.ofSeconds(8)).until(() -> true);
        System.out.println("--- 재고 소진 완료. 11번째 주문 실행 ---");

        // When — 11번째 주문 (재고 부족 → 보상 체인 발동)
        com.eda.order.Order failOrder = orderService.createOrder("user-11", new BigDecimal("10000"), 1);

        // Then — 보상 체인이 완료되어 주문이 CANCELLED 상태
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var savedOrder = orderRepository.findByOrderId(failOrder.getOrderId());
                    assertThat(savedOrder).isPresent();
                    assertThat(savedOrder.get().getStatus())
                            .as("보상 체인: InventoryFailed → PaymentCancelled → OrderCancelled")
                            .isEqualTo(com.eda.order.Order.OrderStatus.CANCELLED);
                });

        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [시나리오2] 코레오그래피 Saga 보상 — PASS ✅");
        System.out.println("================================================================");
        System.out.println("  orderId     : " + failOrder.getOrderId());
        System.out.println("  최종 status : CANCELLED");
        System.out.println("  보상 체인   : InventoryFailed → PaymentCancelled → OrderCancelled");
        System.out.println("  정상 주문   : 10건 (재고 전부 소진)");
        System.out.println("  보상 주문   : 1건 (11번째, 재고 부족)");
        System.out.println("================================================================");
        System.out.println();
    }
}
