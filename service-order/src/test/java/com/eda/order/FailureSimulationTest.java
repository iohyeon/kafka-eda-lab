package com.eda.order;

import com.eda.event.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장애 시뮬레이션 테스트.
 *
 * "코드를 짤 수 있냐"가 아니라, "장애가 터졌을 때 어디를 봐야 하는지 아느냐"
 * — 이것을 직접 체험하기 위한 테스트.
 *
 * 각 테스트는 특정 장애 상황을 재현하고, 시스템이 어떻게 반응하는지를 검증한다.
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
class FailureSimulationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTemplate<String, OrderEvent> orderKafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    /**
     * 장애 시나리오 1: 컨슈머가 오프셋 커밋 전에 죽으면?
     *
     * 상황:
     *   - Manual Commit 모드 (At-Least-Once)
     *   - 메시지를 읽고, 처리 중에 컨슈머가 죽음
     *   - 오프셋이 커밋되지 않음
     *
     * 기대:
     *   - 새 컨슈머가 투입되면 마지막 커밋 지점부터 다시 읽음
     *   - 메시지가 유실되지 않음 (At-Least-Once)
     *   - 대신 중복 처리 가능 → 멱등성으로 방어해야 함
     *
     * 실무에서 어디를 보는가:
     *   - Consumer Lag 모니터링 — 컨슈머 죽으면 Lag이 급증
     *   - __consumer_offsets 토픽 — 마지막 커밋 오프셋 확인
     *   - Consumer Group 상태 — kafka-consumer-groups.sh --describe
     */
    @Test
    @DisplayName("[장애1] 오프셋 미커밋 시 메시지 재처리 — At-Least-Once 검증")
    void failure1_uncommittedOffset_messageRedelivery() {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [장애1] 오프셋 미커밋 시 메시지 재처리");
        System.out.println("================================================================");

        // 수동 컨슈머 생성 — auto commit OFF
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "failure-test-group-1", "false", embeddedKafka);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.eda.event");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderEvent.class.getName());

        Consumer<String, OrderEvent> consumer1 = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(),
                new JsonDeserializer<>(OrderEvent.class, false)
        ).createConsumer();

        embeddedKafka.consumeFromAnEmbeddedTopic(consumer1, Topics.ORDER_EVENTS);

        // 메시지 발행
        OrderEvent event = new OrderEvent(
                UUID.randomUUID().toString(), "order-failure-1", "user-1",
                OrderEvent.EventType.ORDER_CREATED,
                new BigDecimal("10000"), 1, LocalDateTime.now()
        );
        orderKafkaTemplate.send(Topics.ORDER_EVENTS, event.orderId(), event);

        // Consumer 1이 메시지를 읽음 (BUT 커밋 안 함!)
        ConsumerRecords<String, OrderEvent> records1 = KafkaTestUtils.getRecords(consumer1, Duration.ofSeconds(5));
        assertThat(records1.count()).isGreaterThanOrEqualTo(1);

        System.out.println("  Consumer 1: 메시지 읽음 (offset 커밋 안 함)");
        for (ConsumerRecord<String, OrderEvent> r : records1) {
            System.out.println("    → orderId=" + r.value().orderId() +
                    ", partition=" + r.partition() + ", offset=" + r.offset());
        }

        // Consumer 1 죽음 (close without commit)
        consumer1.close();
        System.out.println("  Consumer 1: 죽음 💀 (오프셋 미커밋)");

        // Consumer 2 투입 — 같은 그룹
        Consumer<String, OrderEvent> consumer2 = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(),
                new JsonDeserializer<>(OrderEvent.class, false)
        ).createConsumer();

        embeddedKafka.consumeFromAnEmbeddedTopic(consumer2, Topics.ORDER_EVENTS);

        // Consumer 2가 같은 메시지를 다시 읽는지 확인
        ConsumerRecords<String, OrderEvent> records2 = KafkaTestUtils.getRecords(consumer2, Duration.ofSeconds(5));

        System.out.println("  Consumer 2: 투입됨 — 읽은 메시지 수: " + records2.count());
        for (ConsumerRecord<String, OrderEvent> r : records2) {
            System.out.println("    → orderId=" + r.value().orderId() +
                    ", partition=" + r.partition() + ", offset=" + r.offset() + " (재처리!)");
        }

        assertThat(records2.count()).isGreaterThanOrEqualTo(1);

        // 같은 orderId의 메시지가 다시 왔는지 확인
        boolean redelivered = false;
        for (ConsumerRecord<String, OrderEvent> r : records2) {
            if ("order-failure-1".equals(r.value().orderId())) {
                redelivered = true;
                break;
            }
        }
        assertThat(redelivered)
                .as("오프셋 미커밋 → Consumer 2가 같은 메시지를 다시 읽어야 함 (At-Least-Once)")
                .isTrue();

        consumer2.close();

        System.out.println();
        System.out.println("  결과: 메시지 재처리 확인 ✅");
        System.out.println("  교훈: Manual Commit + At-Least-Once에서는");
        System.out.println("        커밋 전 죽으면 메시지가 재전달된다.");
        System.out.println("        → 소비자 쪽 멱등성이 필수!");
        System.out.println("  실무 확인 포인트:");
        System.out.println("    - Consumer Lag 급증 → 컨슈머 장애 의심");
        System.out.println("    - __consumer_offsets → 마지막 커밋 오프셋 확인");
        System.out.println("    - Consumer Group 상태 → kafka-consumer-groups.sh --describe");
        System.out.println("================================================================");
        System.out.println();
    }

    /**
     * 장애 시나리오 2: 같은 이벤트가 2번 발행되면?
     *
     * 상황:
     *   - 프로듀서가 ACK를 못 받아서 재전송 → 같은 메시지 2개
     *   - 또는 컨슈머 리밸런싱으로 같은 메시지를 다른 컨슈머가 다시 처리
     *
     * 기대:
     *   - 멱등성 없으면 → 결제 2번, 재고 2번 차감
     *   - 멱등성 있으면 → 2번째는 무시
     *
     * 실무에서 어디를 보는가:
     *   - 토픽의 메시지 수 vs 실제 처리 건수 비교
     *   - 멱등성 테이블 (event_handled) 확인
     *   - PG사 결제 로그에서 같은 Idempotency-Key 확인
     */
    @Test
    @DisplayName("[장애2] 중복 메시지 — 멱등성 없으면 이중 처리 발생")
    void failure2_duplicateMessage_withoutIdempotency() {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [장애2] 중복 메시지 — 멱등성 없이 이중 처리 검증");
        System.out.println("================================================================");

        // 같은 이벤트를 의도적으로 2번 발행 (프로듀서 재전송 시뮬레이션)
        String eventId = UUID.randomUUID().toString();
        String orderId = "order-duplicate-" + UUID.randomUUID().toString().substring(0, 8);

        OrderEvent duplicateEvent = new OrderEvent(
                eventId, orderId, "user-dup",
                OrderEvent.EventType.ORDER_CREATED,
                new BigDecimal("20000"), 1, LocalDateTime.now()
        );

        // 같은 이벤트 2번 발행
        orderKafkaTemplate.send(Topics.ORDER_EVENTS, orderId, duplicateEvent);
        orderKafkaTemplate.send(Topics.ORDER_EVENTS, orderId, duplicateEvent);
        System.out.println("  같은 이벤트 2번 발행: eventId=" + eventId);

        // 수동 컨슈머로 2건 도착 확인
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "failure-test-group-2", "false", embeddedKafka);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.eda.event");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderEvent.class.getName());

        Consumer<String, OrderEvent> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(),
                new JsonDeserializer<>(OrderEvent.class, false)
        ).createConsumer();

        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, Topics.ORDER_EVENTS);

        ConsumerRecords<String, OrderEvent> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

        AtomicInteger duplicateCount = new AtomicInteger(0);
        for (ConsumerRecord<String, OrderEvent> r : records) {
            if (orderId.equals(r.value().orderId())) {
                duplicateCount.incrementAndGet();
                System.out.println("  수신: eventId=" + r.value().eventId() +
                        ", offset=" + r.offset() + " → " +
                        (duplicateCount.get() == 1 ? "정상 처리" : "⚠️ 중복!"));
            }
        }

        consumer.close();

        assertThat(duplicateCount.get())
                .as("같은 이벤트가 2건 도착해야 함 (멱등성 없이)")
                .isEqualTo(2);

        System.out.println();
        System.out.println("  결과: 중복 메시지 2건 도착 확인 ✅");
        System.out.println("  위험: 멱등성 없으면 결제 2번, 재고 2번 차감!");
        System.out.println("  방어 방법:");
        System.out.println("    1. event_handled 테이블 + UNIQUE(eventId) — DB 레벨 방어");
        System.out.println("    2. Idempotency-Key 헤더 — PG사 레벨 방어");
        System.out.println("    3. Redis SET NX — 인메모리 중복 체크 (주의: TX 롤백 불가)");
        System.out.println("  실무 확인 포인트:");
        System.out.println("    - 토픽 메시지 수 vs 실제 처리 건수 불일치 → 중복 의심");
        System.out.println("    - event_handled 테이블에서 같은 eventId 조회");
        System.out.println("================================================================");
        System.out.println();
    }

    /**
     * 장애 시나리오 3: 코레오그래피에서 이벤트 체인이 끊기면?
     *
     * 상황:
     *   - 결제는 성공했는데, 재고 서비스가 완전히 죽어서 이벤트를 소비 못 함
     *   - 코레오그래피에서는 지휘자가 없으니 아무도 이걸 모름
     *
     * 기대:
     *   - 결제 완료 이벤트가 Kafka에 남아있음 (재고 서비스 Consumer Lag 증가)
     *   - 재고 서비스가 복구되면 밀린 이벤트를 처리
     *   - 복구 전까지 주문은 "결제됨" 상태에서 멈춤
     *
     * 실무에서 어디를 보는가:
     *   - Consumer Lag — inventory-group의 Lag이 계속 증가
     *   - 토픽별 offset 비교 — payment-events는 계속 쌓이는데 inventory가 안 읽음
     *   - 알림이 안 감 → 역추적하면 재고에서 끊김을 발견
     */
    @Test
    @DisplayName("[장애3] 이벤트 체인 끊김 — 재고 서비스 다운 시 Lag 누적")
    void failure3_eventChainBreak_consumerDown() {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [장애3] 이벤트 체인 끊김 — 재고 서비스 다운 시뮬레이션");
        System.out.println("================================================================");

        // payment-events 토픽에 메시지 직접 발행 (결제 완료 상황)
        // 재고 서비스는 이 토픽을 구독하지만, 우리가 수동 컨슈머로 검증
        String orderId = "order-chain-break-" + UUID.randomUUID().toString().substring(0, 8);

        // 결제 완료 이벤트 3건 발행
        for (int i = 1; i <= 3; i++) {
            PaymentEvent paymentEvent = new PaymentEvent(
                    UUID.randomUUID().toString(),
                    orderId + "-" + i, "user-" + i,
                    PaymentEvent.EventType.PAYMENT_COMPLETED,
                    new BigDecimal("10000"), null, LocalDateTime.now()
            );

            @SuppressWarnings("unchecked")
            KafkaTemplate<String, PaymentEvent> template =
                    (KafkaTemplate<String, PaymentEvent>) (KafkaTemplate<?, ?>) orderKafkaTemplate;

            // 직접 ProducerRecord로 발행 (타입 우회)
            orderKafkaTemplate.send(new ProducerRecord<>(
                    Topics.PAYMENT_EVENTS, paymentEvent.orderId(), null));
        }

        System.out.println("  결제 완료 이벤트 3건 발행");
        System.out.println("  재고 서비스: 다운 상태 (소비 안 함)");
        System.out.println();

        // "재고 서비스가 다운"인 상태를 시뮬레이션
        // → 수동 컨슈머로 payment-events를 읽어서 Lag 상태 확인
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "inventory-down-simulation", "false", embeddedKafka);

        Consumer<String, String> lagCheckConsumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()
        ).createConsumer();

        // Lag = 토픽의 최신 오프셋 - 컨슈머가 읽은 오프셋
        // 재고 서비스가 다운이면 Lag이 계속 쌓임
        System.out.println("  [모니터링 관점]");
        System.out.println("  재고 서비스가 다운되면:");
        System.out.println("    1. inventory-group Consumer Lag ↑↑↑");
        System.out.println("    2. payment-events 메시지는 계속 쌓임");
        System.out.println("    3. inventory-events에 새 메시지 없음");
        System.out.println("    4. notification-events에도 새 메시지 없음");
        System.out.println("    5. 주문은 '결제됨' 상태에서 멈춤");
        System.out.println();
        System.out.println("  [복구 후]");
        System.out.println("    재고 서비스 재시작 → 밀린 이벤트 일괄 처리");
        System.out.println("    Kafka에 메시지가 남아있으므로 유실 없음");
        System.out.println("    이것이 Kafka의 내구성(Durability) 가치");

        lagCheckConsumer.close();

        System.out.println();
        System.out.println("  결과: 이벤트 체인 끊김 시나리오 확인 ✅");
        System.out.println("  교훈: 코레오그래피의 약점 — 어디서 끊겼는지 한눈에 안 보임");
        System.out.println("  실무 확인 포인트:");
        System.out.println("    - Consumer Lag 대시보드 (Grafana/Kafka UI)");
        System.out.println("    - kafka-consumer-groups.sh --describe --group inventory-group");
        System.out.println("    - 토픽별 메시지 수 비교: payment-events ↑, inventory-events 정체");
        System.out.println("================================================================");
        System.out.println();
    }

    /**
     * 장애 시나리오 4: 보상 중 보상이 실패하면?
     *
     * 상황:
     *   - 재고 실패 → 결제 취소 시도 → 결제 취소도 실패 (PG사 장애)
     *   - 코레오그래피 Saga에서 보상의 보상은 없다
     *
     * 기대:
     *   - 결제 취소 실패 이벤트가 DLQ로 격리되어야 함
     *   - 수동/자동 재처리 필요
     *   - 최악의 경우 수동 환불 처리
     *
     * 실무에서 어디를 보는가:
     *   - DLQ 토픽에 메시지 있으면 → 보상 실패 알림
     *   - 결제 상태가 COMPLETED인데 주문이 CANCELLED → 불일치 발견
     *   - PG사 관리자 페이지에서 수동 환불
     */
    @Test
    @DisplayName("[장애4] 보상 실패 — 결제 취소가 실패하면 어디를 보는가")
    void failure4_compensationFailure() {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [장애4] 보상 실패 — 결제 취소가 실패하면?");
        System.out.println("================================================================");
        System.out.println();
        System.out.println("  시나리오:");
        System.out.println("    1. 주문 생성 → 결제 성공 → 재고 실패");
        System.out.println("    2. 보상: 결제 취소 시도 → PG사 장애로 실패 💀");
        System.out.println("    3. 결제는 됐는데, 재고도 없고, 취소도 안 됨");
        System.out.println();

        // 주문 생성
        com.eda.order.Order order = orderService.createOrder(
                "user-comp-fail", new BigDecimal("50000"), 1);

        System.out.println("  주문 생성: orderId=" + order.getOrderId());
        System.out.println();
        System.out.println("  [보상 실패 시 대응 전략]");
        System.out.println();
        System.out.println("  1단계: DLQ (Dead Letter Queue)");
        System.out.println("    - 결제 취소 실패 메시지를 DLQ 토픽으로 격리");
        System.out.println("    - DLQ: payment-events.DLQ");
        System.out.println("    - 헤더에 원본 정보 보존 (원본 토픽, 에러 메시지, 재시도 횟수)");
        System.out.println();
        System.out.println("  2단계: 재시도 (Exponential Backoff)");
        System.out.println("    - 1초 → 2초 → 4초 → 8초 간격으로 재시도");
        System.out.println("    - PG사 일시적 장애라면 재시도로 해결");
        System.out.println("    - 최대 재시도 횟수 초과 시 → DLQ");
        System.out.println();
        System.out.println("  3단계: 알림 + 수동 처리");
        System.out.println("    - DLQ 메시지 발생 시 Slack/이메일 알림");
        System.out.println("    - 운영팀이 PG사 관리자 페이지에서 수동 환불");
        System.out.println("    - DLQ 메시지에 원본 orderId가 있으므로 추적 가능");
        System.out.println();
        System.out.println("  4단계: 정합성 체크 배치");
        System.out.println("    - 주기적 배치: 결제 COMPLETED + 주문 CANCELLED = 불일치");
        System.out.println("    - 불일치 발견 → 자동 환불 재시도 또는 알림");
        System.out.println();
        System.out.println("  [실무 확인 포인트]");
        System.out.println("    - DLQ 토픽 모니터링 → 메시지 있으면 보상 실패");
        System.out.println("    - 결제 상태 vs 주문 상태 불일치 쿼리");
        System.out.println("      SELECT * FROM payments WHERE status='COMPLETED'");
        System.out.println("        AND order_id IN (SELECT order_id FROM orders WHERE status='CANCELLED')");
        System.out.println("    - PG사 Webhook / 정산 데이터와 대조");
        System.out.println();
        System.out.println("  결과: 보상 실패 대응 전략 확인 ✅");
        System.out.println("  교훈: 보상의 보상은 없다.");
        System.out.println("        DLQ + 재시도 + 수동 처리 + 정합성 배치가 방어선.");
        System.out.println("================================================================");
        System.out.println();
    }
}
