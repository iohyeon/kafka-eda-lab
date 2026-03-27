# 통합 테스트 실행 결과

**실행일시**: 2026-03-27 21:16 KST
**실행환경**: EmbeddedKafka (JVM 내장 Kafka 브로커, Docker 불필요)
**테스트 결과**: 2 tests, 0 failures, **100% PASS**
**소요 시간**: 19.238초 (EmbeddedKafka 부팅 포함)

---

## 시나리오 1: 코레오그래피 정상 흐름 — PASS

### 실행 로그 (시간순)

```
21:17:08.292 [Order]        주문 생성 완료: orderId=dd6a3fec
21:17:08.363 [Order]        이벤트 발행 완료: orderId=dd6a3fec, partition=0, offset=0
21:17:08.483 [Payment]      결제 처리 시작: orderId=dd6a3fec, amount=15000
21:17:08.484 [Payment]      PG사 결제 요청: userId=test-user-1, amount=15000
21:17:08.504 [Payment]      이벤트 발행: type=PAYMENT_COMPLETED, orderId=dd6a3fec
21:17:08.515 [Inventory]    재고 차감 시작: orderId=dd6a3fec, 현재재고=10
21:17:08.515 [Inventory]    재고 차감 완료: 남은재고=9
21:17:08.527 [Inventory]    이벤트 발행: type=INVENTORY_DEDUCTED, orderId=dd6a3fec
21:17:08.534 [Notification] 알림 발송: orderId=dd6a3fec
21:17:08.534 [Notification] 주문 처리 완료 알림 발송: orderId=dd6a3fec
```

### 이벤트 흐름도

```
OrderCreated (order-events, offset=0)
  → PaymentCompleted (payment-events)
    → InventoryDeducted (inventory-events, 재고 10→9)
      → Notification 발송
```

### 소요 시간 분석

| 구간 | 소요 |
|------|------|
| 주문 생성 → 이벤트 발행 | 71ms |
| 이벤트 발행 → 결제 시작 | 120ms |
| 결제 → 재고 차감 | 11ms |
| 재고 → 알림 발송 | 7ms |
| **전체 (주문→알림)** | **242ms** |

### 검증 결과

- [x] 주문 상태: CREATED
- [x] 이벤트 체인: OrderCreated → PaymentCompleted → InventoryDeducted → Notification
- [x] 각 서비스가 독립적으로 이벤트에 반응 (코레오그래피)
- [x] 지휘자 없이 전체 흐름이 완성됨

---

## 시나리오 2: 코레오그래피 Saga 보상 — PASS

### 선행: 재고 10개 소진

```
재고: 10 → 9 → 8 → 7 → 6 → 5 → 4 → 3 → 2 → 1 → 0 (10건 주문 완료)
10건 모두 정상 처리: OrderCreated → PaymentCompleted → InventoryDeducted → Notification
```

### 11번째 주문 (보상 체인 발동) — 실행 로그

```
21:17:26.874 [Order]       주문 생성 완료: orderId=16375f4f
21:17:26.881 [Order]       이벤트 발행 완료: orderId=16375f4f, partition=0, offset=4
21:17:26.888 [Payment]     결제 처리 시작: orderId=16375f4f, amount=10000
21:17:26.888 [Payment]     PG사 결제 요청: userId=user-11, amount=10000
21:17:26.902 [Payment]     이벤트 발행: type=PAYMENT_COMPLETED, orderId=16375f4f
21:17:26.904 [Inventory]   재고 차감 시작: orderId=16375f4f, 현재재고=0
21:17:26.905 [Inventory]   재고 부족: orderId=16375f4f                           ← 💀 실패 지점
21:17:26.918 [Inventory]   이벤트 발행: type=INVENTORY_FAILED, orderId=16375f4f  ← 실패 이벤트
21:17:26.926 [Payment]     보상 시작 — 결제 취소: orderId=16375f4f, reason=재고 부족: 현재재고=0
21:17:26.926 [Payment]     PG사 결제 취소 요청: orderId=16375f4f                 ← 보상 실행
21:17:26.942 [Payment]     이벤트 발행: type=PAYMENT_CANCELLED, orderId=16375f4f
21:17:26.948 [Order]       보상 시작 — 주문 취소: orderId=16375f4f, reason=재고 부족으로 인한 결제 취소
21:17:27.022 [Order]       주문 취소 완료: orderId=16375f4f, status=CANCELLED    ← 최종 상태
```

### 보상 흐름도

```
OrderCreated (order-events)
  → PaymentCompleted (payment-events)    ← 결제 성공 (아직 문제 모름)
    → InventoryFailed 💀 (inventory-events, 재고=0)
      → PaymentCancelled (payment-events)  ← 결제 서비스가 "알아서" 취소 (코레오그래피!)
        → OrderCancelled (DB status=CANCELLED)  ← 주문 서비스가 "알아서" 취소
```

### 보상 소요 시간 분석

| 구간 | 소요 |
|------|------|
| 재고 실패 → INVENTORY_FAILED 발행 | 13ms |
| INVENTORY_FAILED → 결제 취소 시작 | 8ms |
| 결제 취소 → PAYMENT_CANCELLED 발행 | 16ms |
| PAYMENT_CANCELLED → 주문 취소 시작 | 6ms |
| 주문 취소 → DB 반영 완료 | 74ms |
| **보상 전체 (재고 실패 → 주문 CANCELLED)** | **117ms** |

### 검증 결과

- [x] 정상 주문 10건: 전부 처리 완료 (재고 10→0)
- [x] 11번째 주문: 재고 부족으로 INVENTORY_FAILED 발행
- [x] 결제 서비스: INVENTORY_FAILED를 **스스로 구독**하여 결제 취소 (아무도 지시하지 않음)
- [x] 주문 서비스: PAYMENT_CANCELLED를 **스스로 구독**하여 주문 취소
- [x] 최종 주문 상태: **CANCELLED**
- [x] 보상 체인이 자동으로 역순 실행됨

---

## 코레오그래피 Saga 보상의 핵심 관찰

### 아무도 지시하지 않았다

```
[Inventory] → INVENTORY_FAILED 발행
[Payment]   → INVENTORY_FAILED 구독 → "스스로" 결제 취소   ← 오케스트레이터가 없다
[Order]     → PAYMENT_CANCELLED 구독 → "스스로" 주문 취소  ← 아무도 명령하지 않았다
```

이것이 **코레오그래피 Saga**의 본질:
- 지휘자 없이 각 서비스가 이벤트에 반응하여 보상을 수행
- 장점: 느슨한 결합, 각 서비스가 자기 보상 로직을 캡슐화
- 단점: 전체 흐름을 한 곳에서 볼 수 없다, 복잡해지면 추적이 어렵다

### 이벤트 토픽별 메시지

| 토픽 | 메시지 | 건수 |
|------|--------|------|
| order-events | ORDER_CREATED | 11건 (정상 10 + 보상 대상 1) |
| payment-events | PAYMENT_COMPLETED | 11건 |
| payment-events | PAYMENT_CANCELLED | 1건 (보상) |
| inventory-events | INVENTORY_DEDUCTED | 10건 (정상) |
| inventory-events | INVENTORY_FAILED | 1건 (실패) |

---

## 실행 방법

```bash
# 테스트 실행 (Docker 불필요 — EmbeddedKafka 사용)
./gradlew :service-order:cleanTest :service-order:test \
  --tests "com.eda.order.ChoreographySagaIntegrationTest"
```
