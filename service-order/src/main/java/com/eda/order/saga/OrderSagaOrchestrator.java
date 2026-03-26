package com.eda.order.saga;

import com.eda.event.SagaCommand;
import com.eda.event.SagaReply;
import com.eda.event.Topics;
import com.eda.order.Order;
import com.eda.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 오케스트레이션 Saga — 중앙 지휘자.
 *
 * 전체 흐름을 이 클래스 하나만 보면 파악할 수 있다:
 *   1. 결제 커맨드 발행
 *   2. 결제 응답 수신 → 재고 커맨드 발행
 *   3. 재고 응답 수신 → 알림 커맨드 발행
 *   4. 알림 응답 수신 → Saga 완료
 *
 *   실패 시: 이전 단계의 보상 커맨드를 직접 발행
 *
 * 코레오그래피 Saga와의 차이:
 * - 코레오그래피: 각 서비스가 실패 이벤트를 구독하여 "알아서" 보상
 * - 오케스트레이션: 오케스트레이터가 "결제 취소해"라고 직접 지시
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final OrderSagaRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, SagaCommand> commandTemplate;

    /**
     * Saga 시작 — 주문 생성 후 결제 커맨드 발행
     */
    @Transactional
    public void startSaga(String orderId, String userId, BigDecimal amount, int itemCount) {
        String sagaId = UUID.randomUUID().toString();

        // Saga 상태 저장
        OrderSaga saga = new OrderSaga(sagaId, orderId);
        saga.advanceTo(OrderSaga.SagaStep.PAYMENT_REQUESTED);
        sagaRepository.save(saga);

        // 1단계: 결제 커맨드 발행
        SagaCommand command = new SagaCommand(
                sagaId, orderId, userId,
                SagaCommand.CommandType.PROCESS_PAYMENT,
                amount, itemCount, LocalDateTime.now()
        );

        commandTemplate.send(Topics.SAGA_PAYMENT_COMMAND, orderId, command);
        log.info("[Orchestrator] Saga 시작: sagaId={}, 1단계 결제 커맨드 발행", sagaId);
    }

    /**
     * 각 서비스의 응답을 수신하여 다음 단계를 진행하거나 보상을 실행.
     * 전체 흐름이 이 메서드 하나에 있다.
     */
    @KafkaListener(
            topics = Topics.SAGA_REPLY,
            groupId = "saga-orchestrator-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleReply(ConsumerRecord<String, SagaReply> record, Acknowledgment ack) {
        SagaReply reply = record.value();

        OrderSaga saga = sagaRepository.findBySagaId(reply.sagaId())
                .orElse(null);

        if (saga == null) {
            log.warn("[Orchestrator] 알 수 없는 sagaId: {}", reply.sagaId());
            ack.acknowledge();
            return;
        }

        log.info("[Orchestrator] 응답 수신: sagaId={}, type={}, currentStep={}",
                saga.getSagaId(), reply.replyType(), saga.getCurrentStep());

        switch (reply.replyType()) {
            // ── 정방향 응답 ──
            case PAYMENT_SUCCESS -> handlePaymentSuccess(saga, reply);
            case INVENTORY_SUCCESS -> handleInventorySuccess(saga, reply);
            case NOTIFICATION_SENT -> handleNotificationSent(saga);

            // ── 실패 응답 → 보상 시작 ──
            case PAYMENT_FAILED -> handlePaymentFailed(saga, reply);
            case INVENTORY_FAILED -> handleInventoryFailed(saga, reply);

            // ── 보상 응답 ──
            case PAYMENT_CANCELLED -> handlePaymentCancelled(saga);
        }

        sagaRepository.save(saga);
        ack.acknowledge();
    }

    // ── 정방향 핸들러 ──

    private void handlePaymentSuccess(OrderSaga saga, SagaReply reply) {
        saga.advanceTo(OrderSaga.SagaStep.PAYMENT_COMPLETED);

        // 2단계: 재고 커맨드 발행
        SagaCommand command = new SagaCommand(
                saga.getSagaId(), saga.getOrderId(), null,
                SagaCommand.CommandType.DEDUCT_INVENTORY,
                null, 0, LocalDateTime.now()
        );
        commandTemplate.send(Topics.SAGA_INVENTORY_COMMAND, saga.getOrderId(), command);
        saga.advanceTo(OrderSaga.SagaStep.INVENTORY_REQUESTED);

        log.info("[Orchestrator] 2단계 재고 커맨드 발행: sagaId={}", saga.getSagaId());
    }

    private void handleInventorySuccess(OrderSaga saga, SagaReply reply) {
        saga.advanceTo(OrderSaga.SagaStep.INVENTORY_DEDUCTED);

        // 3단계: 알림 커맨드 발행 (마지막, 보상 불가)
        SagaCommand command = new SagaCommand(
                saga.getSagaId(), saga.getOrderId(), null,
                SagaCommand.CommandType.SEND_NOTIFICATION,
                null, 0, LocalDateTime.now()
        );
        commandTemplate.send(Topics.SAGA_NOTIFICATION_COMMAND, saga.getOrderId(), command);

        log.info("[Orchestrator] 3단계 알림 커맨드 발행: sagaId={}", saga.getSagaId());
    }

    private void handleNotificationSent(OrderSaga saga) {
        saga.advanceTo(OrderSaga.SagaStep.NOTIFICATION_SENT);
        saga.complete();

        log.info("[Orchestrator] Saga 완료: sagaId={}, orderId={}", saga.getSagaId(), saga.getOrderId());
    }

    // ── 실패 핸들러 → 보상 ──

    private void handlePaymentFailed(OrderSaga saga, SagaReply reply) {
        saga.fail(OrderSaga.SagaStep.PAYMENT_REQUESTED, reply.reason());

        // 결제가 첫 단계이므로 보상할 이전 단계가 없다 → 주문 취소만
        cancelOrder(saga);
        saga.compensated();

        log.info("[Orchestrator] 결제 실패 → 주문 취소: sagaId={}", saga.getSagaId());
    }

    private void handleInventoryFailed(OrderSaga saga, SagaReply reply) {
        saga.fail(OrderSaga.SagaStep.INVENTORY_REQUESTED, reply.reason());

        // 재고 실패 → 결제 취소 커맨드 발행 (오케스트레이터가 직접 지시!)
        SagaCommand compensationCommand = new SagaCommand(
                saga.getSagaId(), saga.getOrderId(), null,
                SagaCommand.CommandType.CANCEL_PAYMENT,
                null, 0, LocalDateTime.now()
        );
        commandTemplate.send(Topics.SAGA_PAYMENT_COMMAND, saga.getOrderId(), compensationCommand);
        saga.advanceTo(OrderSaga.SagaStep.PAYMENT_CANCEL_REQUESTED);

        log.info("[Orchestrator] 재고 실패 → 결제 취소 커맨드 발행: sagaId={}", saga.getSagaId());
    }

    // ── 보상 핸들러 ──

    private void handlePaymentCancelled(OrderSaga saga) {
        saga.advanceTo(OrderSaga.SagaStep.PAYMENT_CANCELLED);

        cancelOrder(saga);
        saga.compensated();

        log.info("[Orchestrator] 보상 완료: sagaId={}, orderId={}", saga.getSagaId(), saga.getOrderId());
    }

    private void cancelOrder(OrderSaga saga) {
        orderRepository.findByOrderId(saga.getOrderId())
                .ifPresent(order -> {
                    order.cancel();
                    orderRepository.save(order);
                    log.info("[Orchestrator] 주문 취소: orderId={}", saga.getOrderId());
                });
    }
}
