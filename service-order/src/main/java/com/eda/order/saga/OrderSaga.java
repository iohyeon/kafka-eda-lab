package com.eda.order.saga;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 오케스트레이션 Saga — 주문 처리 전체 상태를 추적하는 엔티티.
 *
 * 오케스트레이터가 각 단계의 결과를 받을 때마다 상태를 갱신한다.
 * 어떤 인스턴스가 죽어도 DB에서 상태를 읽고 이어갈 수 있다.
 */
@Entity
@Table(name = "order_saga")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sagaId;

    @Column(nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStep currentStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public OrderSaga(String sagaId, String orderId) {
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.currentStep = SagaStep.STARTED;
        this.status = SagaStatus.IN_PROGRESS;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void advanceTo(SagaStep step) {
        this.currentStep = step;
        this.updatedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = SagaStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(SagaStep failedStep, String reason) {
        this.currentStep = failedStep;
        this.status = SagaStatus.COMPENSATING;
        this.failReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    public void compensated() {
        this.status = SagaStatus.COMPENSATED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Saga 단계 — 오케스트레이터의 코드만 보면 전체 흐름이 보인다.
     */
    public enum SagaStep {
        STARTED,
        PAYMENT_REQUESTED,
        PAYMENT_COMPLETED,
        INVENTORY_REQUESTED,
        INVENTORY_DEDUCTED,
        NOTIFICATION_SENT,
        // 보상 단계
        PAYMENT_CANCEL_REQUESTED,
        PAYMENT_CANCELLED
    }

    public enum SagaStatus {
        IN_PROGRESS,    // 진행 중
        COMPLETED,      // 전부 성공
        COMPENSATING,   // 보상 중
        COMPENSATED     // 보상 완료
    }
}
