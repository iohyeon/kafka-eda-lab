package com.eda.streams.topology;

import com.eda.event.OrderEvent;
import com.eda.event.PaymentEvent;
import com.eda.event.Topics;
import com.eda.streams.model.EnrichedOrder;
import com.eda.streams.model.OrderStats;
import com.eda.streams.serde.JsonSerde;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Kafka Streams 토폴로지 — 3가지 스트림 처리 패턴.
 *
 * <h2>1. Windowed Aggregation (실시간 집계)</h2>
 * order-events를 1분 텀블링 윈도우로 집계하여 주문 수 + 매출 합계를 산출.
 * <p>
 * KStream → groupByKey → windowedBy(tumbling 1min) → aggregate → toStream → to(output)
 * <p>
 * 내부 동작:
 * - aggregate()는 State Store(RocksDB)에 윈도우별 중간 결과를 저장
 * - 새 이벤트가 들어올 때마다 해당 윈도우의 값을 갱신 (incremental)
 * - 윈도우 종료 시 suppress()로 최종 결과만 downstream으로 전달
 *
 * <h2>2. Filter + Transform (고액 주문 필터링 + 알림 변환)</h2>
 * 50,000원 이상 주문을 필터링하고, 알림 메시지 형식으로 변환하여 별도 토픽에 발행.
 * <p>
 * KStream → filter → mapValues → to(output)
 * <p>
 * Stateless 연산 — State Store 불필요, 리파티셔닝도 없음.
 *
 * <h2>3. KStream-KStream Join (주문 + 결제 조인)</h2>
 * order-events와 payment-events를 orderId로 조인하여 enriched 이벤트를 생성.
 * <p>
 * KStream(order) join KStream(payment) within 30s → EnrichedOrder → to(output)
 * <p>
 * 내부 동작:
 * - 양쪽 스트림의 이벤트를 각각 State Store에 30초간 보관
 * - 같은 키(orderId)를 가진 이벤트가 양쪽에 모두 도착하면 ValueJoiner가 호출
 * - 30초 내 조인 상대가 없으면 이벤트는 버려진다 (inner join)
 */
@Slf4j
public class OrderStreamTopology {

    public static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("50000");
    public static final Duration WINDOW_SIZE = Duration.ofMinutes(1);
    public static final Duration JOIN_WINDOW = Duration.ofSeconds(30);

    /**
     * StreamsBuilder에 3개 토폴로지를 등록.
     * Spring에서 이 메서드를 호출하여 KafkaStreams를 구성한다.
     */
    public static void build(StreamsBuilder builder) {
        JsonSerde<OrderEvent> orderSerde = new JsonSerde<>(OrderEvent.class);
        JsonSerde<PaymentEvent> paymentSerde = new JsonSerde<>(PaymentEvent.class);
        JsonSerde<OrderStats> statsSerde = new JsonSerde<>(OrderStats.class);
        JsonSerde<EnrichedOrder> enrichedSerde = new JsonSerde<>(EnrichedOrder.class);

        // 소스 스트림 — ORDER_CREATED 이벤트만 필터
        KStream<String, OrderEvent> orderStream = builder
                .stream(Topics.ORDER_EVENTS, Consumed.with(Serdes.String(), orderSerde))
                .filter((key, event) -> event != null
                        && event.eventType() == OrderEvent.EventType.ORDER_CREATED);

        // 소스 스트림 — PAYMENT_COMPLETED 이벤트만 필터
        KStream<String, PaymentEvent> paymentStream = builder
                .stream(Topics.PAYMENT_EVENTS, Consumed.with(Serdes.String(), paymentSerde))
                .filter((key, event) -> event != null
                        && event.eventType() == PaymentEvent.EventType.PAYMENT_COMPLETED);

        // ── Topology 1: Windowed Aggregation ──
        buildAggregation(orderStream, statsSerde);

        // ── Topology 2: Filter + Transform ──
        buildHighValueFilter(orderStream);

        // ── Topology 3: KStream-KStream Join ──
        buildOrderPaymentJoin(orderStream, paymentStream, enrichedSerde);
    }

    /**
     * Topology 1: 1분 텀블링 윈도우 집계.
     *
     * 텀블링 윈도우(Tumbling Window)란?
     * - 고정 크기의 겹치지 않는 시간 구간
     * - [00:00~01:00), [01:00~02:00), ... 각 윈도우는 독립적
     * - 호핑 윈도우와 달리 겹침이 없어 중복 계산 없음
     *
     * "all" 키로 groupBy하는 이유:
     * - 전체 시스템의 주문 통계를 하나의 윈도우에서 집계하기 위해
     * - 실무에서는 userId, region 등으로 세분화
     */
    private static void buildAggregation(
            KStream<String, OrderEvent> orderStream,
            JsonSerde<OrderStats> statsSerde) {

        orderStream
                // 키를 "all"로 재설정 → 전체 주문을 하나의 그룹으로 집계
                // ⚠️ selectKey()는 리파티셔닝을 유발한다 (내부적으로 repartition 토픽 생성)
                .selectKey((key, event) -> "all")
                .groupByKey(Grouped.with(Serdes.String(), new JsonSerde<>(OrderEvent.class)))
                // 1분 텀블링 윈도우
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW_SIZE))
                // aggregate: 초기값 → accumulator로 누적
                .aggregate(
                        () -> OrderStats.ZERO,                    // Initializer
                        (key, event, stats) -> {                  // Aggregator
                            OrderStats updated = stats.add(event.totalAmount());
                            log.info("[Streams] 윈도우 집계 갱신: orders={}, revenue={}",
                                    updated.orderCount(), updated.totalRevenue());
                            return updated;
                        },
                        Materialized.with(Serdes.String(), statsSerde)
                )
                .toStream()
                // 윈도우 키 → 일반 문자열 키로 변환
                .selectKey((windowedKey, stats) -> windowedKey.key())
                .to(Topics.STREAMS_ORDER_STATS,
                        Produced.with(Serdes.String(), statsSerde));
    }

    /**
     * Topology 2: 고액 주문 필터 + 변환.
     *
     * filter()와 mapValues()는 Stateless 연산.
     * - State Store를 사용하지 않는다 → RocksDB 오버헤드 없음
     * - 리파티셔닝도 발생하지 않는다 → 키를 변경하지 않으니까
     * - 가장 가벼운 스트림 처리 패턴
     */
    private static void buildHighValueFilter(KStream<String, OrderEvent> orderStream) {
        orderStream
                .filter((key, event) -> event.totalAmount().compareTo(HIGH_VALUE_THRESHOLD) >= 0)
                .mapValues(event -> {
                    String alert = String.format(
                            "{\"type\":\"HIGH_VALUE_ORDER\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":%s,\"detectedAt\":\"%s\"}",
                            event.orderId(), event.userId(), event.totalAmount(), LocalDateTime.now());
                    log.info("[Streams] 고액 주문 감지: orderId={}, amount={}",
                            event.orderId(), event.totalAmount());
                    return alert;
                })
                .to(Topics.STREAMS_HIGH_VALUE_ORDERS,
                        Produced.with(Serdes.String(), Serdes.String()));
    }

    /**
     * Topology 3: 주문-결제 KStream-KStream Join.
     *
     * KStream-KStream Join vs KStream-KTable Join:
     * - KStream-KStream: 양쪽 모두 시간 제한(JoinWindows) 내에서만 매칭
     *   → "이 주문의 결제가 30초 안에 완료됐는가?"
     * - KStream-KTable: 한쪽은 최신 상태(changelog), 다른 한쪽은 이벤트 스트림
     *   → "이 주문의 현재 결제 상태는?"
     *
     * 여기서는 KStream-KStream Join을 사용:
     * 주문과 결제가 모두 이벤트 스트림이고, 시간 윈도우 내 매칭이 목적이므로.
     *
     * 내부 State Store:
     * - 양쪽 스트림에 각각 WindowStore가 생성된다 (총 2개)
     * - 각 이벤트는 JoinWindow(30초) 동안 Store에 보관
     * - 한쪽에 이벤트가 도착하면 반대쪽 Store를 탐색하여 같은 키 매칭
     */
    private static void buildOrderPaymentJoin(
            KStream<String, OrderEvent> orderStream,
            KStream<String, PaymentEvent> paymentStream,
            JsonSerde<EnrichedOrder> enrichedSerde) {

        orderStream.join(
                paymentStream,
                // ValueJoiner — 양쪽 이벤트를 결합
                (order, payment) -> {
                    long processingTimeMs = Duration.between(
                            order.occurredAt(), payment.occurredAt()).toMillis();

                    EnrichedOrder enriched = new EnrichedOrder(
                            order.orderId(),
                            order.userId(),
                            order.totalAmount(),
                            order.itemCount(),
                            payment.eventType().name(),
                            payment.eventId(),
                            order.occurredAt(),
                            payment.occurredAt(),
                            Math.abs(processingTimeMs)
                    );

                    log.info("[Streams] 주문-결제 조인 완료: orderId={}, processingTime={}ms",
                            enriched.orderId(), enriched.processingTimeMs());
                    return enriched;
                },
                // JoinWindows — 30초 이내 매칭
                JoinWindows.ofTimeDifferenceWithNoGrace(JOIN_WINDOW),
                StreamJoined.with(Serdes.String(), new JsonSerde<>(OrderEvent.class), new JsonSerde<>(PaymentEvent.class))
        ).to(Topics.STREAMS_ORDER_ENRICHED,
                Produced.with(Serdes.String(), enrichedSerde));
    }
}
