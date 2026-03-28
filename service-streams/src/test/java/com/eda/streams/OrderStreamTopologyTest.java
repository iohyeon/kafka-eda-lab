package com.eda.streams;

import com.eda.event.OrderEvent;
import com.eda.event.PaymentEvent;
import com.eda.event.Topics;
import com.eda.streams.model.EnrichedOrder;
import com.eda.streams.model.OrderStats;
import com.eda.streams.serde.JsonSerde;
import com.eda.streams.topology.OrderStreamTopology;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TopologyTestDriver 기반 Kafka Streams 토폴로지 테스트.
 *
 * TopologyTestDriver란?
 * - Kafka 브로커 없이 JVM 내에서 토폴로지를 실행하는 테스트 유틸
 * - 실제 Kafka와 동일한 직렬화/역직렬화 + State Store 동작
 * - 시간을 명시적으로 제어 가능 (advanceWallClockTime)
 * - 밀리초 단위 실행 → 수백 개 테스트도 수 초 내 완료
 *
 * 한계:
 * - 파티셔닝, 리밸런싱은 테스트 불가 (단일 파티션 환경)
 * - 네트워크 지연, 브로커 장애 시뮬레이션 불가
 * - 이런 건 EmbeddedKafka나 Testcontainers로 보완
 */
class OrderStreamTopologyTest {

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, OrderEvent> orderInput;
    private TestInputTopic<String, PaymentEvent> paymentInput;
    private TestOutputTopic<String, String> highValueOutput;
    private TestOutputTopic<String, OrderStats> statsOutput;
    private TestOutputTopic<String, EnrichedOrder> enrichedOutput;

    @BeforeEach
    void setup() {
        StreamsBuilder builder = new StreamsBuilder();
        OrderStreamTopology.build(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-streams");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

        testDriver = new TopologyTestDriver(builder.build(), props);

        // 입력 토픽
        orderInput = testDriver.createInputTopic(
                Topics.ORDER_EVENTS,
                new StringSerializer(),
                new JsonSerde<>(OrderEvent.class).serializer());

        paymentInput = testDriver.createInputTopic(
                Topics.PAYMENT_EVENTS,
                new StringSerializer(),
                new JsonSerde<>(PaymentEvent.class).serializer());

        // 출력 토픽
        highValueOutput = testDriver.createOutputTopic(
                Topics.STREAMS_HIGH_VALUE_ORDERS,
                new StringDeserializer(),
                new StringDeserializer());

        statsOutput = testDriver.createOutputTopic(
                Topics.STREAMS_ORDER_STATS,
                new StringDeserializer(),
                new JsonSerde<>(OrderStats.class).deserializer());

        enrichedOutput = testDriver.createOutputTopic(
                Topics.STREAMS_ORDER_ENRICHED,
                new StringDeserializer(),
                new JsonSerde<>(EnrichedOrder.class).deserializer());
    }

    @AfterEach
    void tearDown() {
        if (testDriver != null) testDriver.close();
    }

    // ── Topology 1: Windowed Aggregation ──

    @Test
    @DisplayName("집계: 주문 이벤트가 들어오면 OrderStats가 누적된다")
    void aggregation_accumulatesOrderStats() {
        // Given: 3건의 주문
        orderInput.pipeInput("order-1", createOrderEvent("order-1", "15000"));
        orderInput.pipeInput("order-2", createOrderEvent("order-2", "25000"));
        orderInput.pipeInput("order-3", createOrderEvent("order-3", "10000"));

        // When: statsOutput에서 결과 읽기
        // aggregate()는 이벤트마다 중간 결과를 emit하므로 3건이 나온다
        var results = statsOutput.readValuesToList();

        // Then: 마지막 결과가 3건 합산
        assertThat(results).isNotEmpty();
        OrderStats latest = results.get(results.size() - 1);
        assertThat(latest.orderCount()).isEqualTo(3);
        assertThat(latest.totalRevenue()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(latest.avgOrderAmount()).isEqualByComparingTo(new BigDecimal("16666.67"));
    }

    @Test
    @DisplayName("집계: ORDER_CANCELLED 이벤트는 집계에 포함되지 않는다")
    void aggregation_ignoresCancelledEvents() {
        // Given
        orderInput.pipeInput("order-1", createOrderEvent("order-1", "15000"));
        orderInput.pipeInput("order-2", new OrderEvent(
                UUID.randomUUID().toString(), "order-2", "user-1",
                OrderEvent.EventType.ORDER_CANCELLED,
                new BigDecimal("20000"), 1, LocalDateTime.now()));

        // Then: cancelled는 무시되어 1건만 집계
        var results = statsOutput.readValuesToList();
        assertThat(results).isNotEmpty();
        OrderStats latest = results.get(results.size() - 1);
        assertThat(latest.orderCount()).isEqualTo(1);
    }

    // ── Topology 2: Filter + Transform ──

    @Test
    @DisplayName("필터: 50,000원 이상 주문만 고액 주문 토픽에 발행된다")
    void filter_onlyHighValueOrders() {
        // Given
        orderInput.pipeInput("order-1", createOrderEvent("order-1", "30000"));  // 미달
        orderInput.pipeInput("order-2", createOrderEvent("order-2", "50000"));  // 정확히 기준
        orderInput.pipeInput("order-3", createOrderEvent("order-3", "100000")); // 초과

        // Then: 50000 이상인 2건만
        var results = highValueOutput.readValuesToList();
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).contains("order-2");
        assertThat(results.get(1)).contains("order-3");
    }

    @Test
    @DisplayName("필터: 변환된 메시지에 HIGH_VALUE_ORDER 타입이 포함된다")
    void filter_transformedMessageContainsType() {
        orderInput.pipeInput("order-1", createOrderEvent("order-1", "75000"));

        String result = highValueOutput.readValue();
        assertThat(result).contains("\"type\":\"HIGH_VALUE_ORDER\"");
        assertThat(result).contains("\"orderId\":\"order-1\"");
        assertThat(result).contains("\"amount\":75000");
    }

    // ── Topology 3: KStream-KStream Join ──

    @Test
    @DisplayName("조인: 주문과 결제가 같은 orderId로 매칭되면 EnrichedOrder가 생성된다")
    void join_orderAndPaymentProduceEnrichedOrder() {
        String orderId = "order-join-1";
        Instant now = Instant.now();

        // Given: 주문 이벤트 → 결제 이벤트 (같은 orderId)
        orderInput.pipeInput(orderId, createOrderEvent(orderId, "20000"), now);
        paymentInput.pipeInput(orderId, createPaymentEvent(orderId, "20000"), now.plusMillis(500));

        // Then: 조인 결과
        var results = enrichedOutput.readValuesToList();
        assertThat(results).hasSize(1);

        EnrichedOrder enriched = results.get(0);
        assertThat(enriched.orderId()).isEqualTo(orderId);
        assertThat(enriched.totalAmount()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(enriched.paymentStatus()).isEqualTo("PAYMENT_COMPLETED");
    }

    @Test
    @DisplayName("조인: orderId가 다르면 매칭되지 않는다")
    void join_differentOrderIdsDoNotMatch() {
        Instant now = Instant.now();

        orderInput.pipeInput("order-A", createOrderEvent("order-A", "10000"), now);
        paymentInput.pipeInput("order-B", createPaymentEvent("order-B", "10000"), now.plusMillis(100));

        // Then: 키가 다르므로 조인 결과 없음
        assertThat(enrichedOutput.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("조인: 30초 윈도우를 초과하면 매칭되지 않는다")
    void join_outsideWindowDoesNotMatch() {
        String orderId = "order-timeout";
        Instant now = Instant.now();

        orderInput.pipeInput(orderId, createOrderEvent(orderId, "10000"), now);
        // 31초 후 결제 → 윈도우 초과
        paymentInput.pipeInput(orderId, createPaymentEvent(orderId, "10000"),
                now.plusSeconds(31));

        assertThat(enrichedOutput.isEmpty()).isTrue();
    }

    // ── Helper ──

    private OrderEvent createOrderEvent(String orderId, String amount) {
        return new OrderEvent(
                UUID.randomUUID().toString(),
                orderId,
                "user-1",
                OrderEvent.EventType.ORDER_CREATED,
                new BigDecimal(amount),
                1,
                LocalDateTime.now()
        );
    }

    private PaymentEvent createPaymentEvent(String orderId, String amount) {
        return new PaymentEvent(
                UUID.randomUUID().toString(),
                orderId,
                "user-1",
                PaymentEvent.EventType.PAYMENT_COMPLETED,
                new BigDecimal(amount),
                null,
                LocalDateTime.now()
        );
    }
}
