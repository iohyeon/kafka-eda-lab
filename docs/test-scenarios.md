# 테스트 시나리오 및 검증 계획

## 인프라 구성

| 컴포넌트 | 포트 | 비고 |
|---------|------|------|
| eda-kafka-1 | 9092 | KRaft Broker + Controller |
| eda-kafka-2 | 9093 | KRaft Broker + Controller |
| eda-kafka-3 | 9094 | KRaft Broker + Controller |
| eda-kafka-ui | 8888 | 클러스터 모니터링 |
| service-order | 18081 | 주문 서비스 |
| service-payment | 18082 | 결제 서비스 |
| service-inventory | 18083 | 재고 서비스 |
| service-notification | 18084 | 알림 서비스 |

> **주의**: 기존 kafka-pipeline-lab 클러스터(19092/29092/39092)와 충돌 방지를 위해
> 컨테이너명(`eda-kafka-*`), 포트(9092~9094), 네트워크(`eda-network`)를 완전 분리.

---

## 시나리오 1: 코레오그래피 — 정상 흐름

### 요청

```bash
curl -X POST http://localhost:18081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "totalAmount": 15000, "itemCount": 1}'
```

### 기대 흐름

```
[Order :18081] 주문 생성 → "OrderCreated" 발행 (order-events)
  ↓
[Payment :18082] "OrderCreated" 구독 → 결제 처리 → "PaymentCompleted" 발행 (payment-events)
  ↓
[Inventory :18083] "PaymentCompleted" 구독 → 재고 차감 (10→9) → "InventoryDeducted" 발행 (inventory-events)
  ↓
[Notification :18084] "InventoryDeducted" 구독 → 알림 발송
```

### 기대 로그 패턴

```
[Order]        주문 생성 완료: orderId=xxx
[Order]        이벤트 발행 완료: orderId=xxx, partition=N, offset=N
[Payment]      결제 처리 시작: orderId=xxx, amount=15000
[Payment]      PG사 결제 요청: userId=user-1, amount=15000
[Payment]      이벤트 발행: type=PAYMENT_COMPLETED, orderId=xxx
[Inventory]    재고 차감 시작: orderId=xxx, 현재재고=10
[Inventory]    재고 차감 완료: 남은재고=9
[Inventory]    이벤트 발행: type=INVENTORY_DEDUCTED, orderId=xxx
[Notification] 알림 발송: orderId=xxx
[Notification] 주문 처리 완료 알림 발송: orderId=xxx
```

### 검증 포인트

- [ ] 주문 응답에 orderId와 status=CREATED 포함
- [ ] Payment가 order-events에서 메시지를 소비했는지 (Consumer Group: payment-group)
- [ ] Inventory가 payment-events에서 메시지를 소비했는지 (Consumer Group: inventory-group)
- [ ] Notification이 inventory-events에서 메시지를 소비했는지 (Consumer Group: notification-group)
- [ ] Kafka UI에서 각 토픽의 메시지 확인 (http://localhost:8888)

---

## 시나리오 2: 코레오그래피 Saga — 재고 부족 보상 흐름

### 선행 조건

재고 10개 → 주문 10건 실행하여 재고 소진 → 11번째 주문에서 보상 발생

```bash
# 10건 주문 (재고 소진)
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:18081/api/orders \
    -H "Content-Type: application/json" \
    -d "{\"userId\": \"user-$i\", \"totalAmount\": 10000, \"itemCount\": 1}"
  sleep 1
done

# 11번째 주문 (재고 부족 → 보상 발생)
curl -X POST http://localhost:18081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-11", "totalAmount": 10000, "itemCount": 1}'
```

### 기대 흐름 (11번째 주문)

```
[Order]      주문 생성 → "OrderCreated" 발행
  ↓
[Payment]    결제 처리 성공 → "PaymentCompleted" 발행
  ↓
[Inventory]  재고 부족 💀 → "InventoryFailed" 발행 (inventory-events)
  ↓
[Payment]    "InventoryFailed" 구독 → 결제 취소 (보상) → "PaymentCancelled" 발행
  ↓
[Order]      "PaymentCancelled" 구독 → 주문 취소 (보상) → status=CANCELLED
```

### 기대 로그 패턴 (보상)

```
[Inventory]  재고 차감 시작: orderId=xxx, 현재재고=0
[Inventory]  재고 부족: orderId=xxx
[Inventory]  이벤트 발행: type=INVENTORY_FAILED, orderId=xxx
[Payment]    보상 시작 — 결제 취소: orderId=xxx, reason=재고 부족
[Payment]    PG사 결제 취소 요청: orderId=xxx
[Payment]    이벤트 발행: type=PAYMENT_CANCELLED, orderId=xxx
[Order]      보상 시작 — 주문 취소: orderId=xxx
[Order]      주문 취소 완료: orderId=xxx, status=CANCELLED
```

### 검증 포인트

- [ ] 11번째 주문이 최종적으로 CANCELLED 상태
- [ ] 결제가 취소되었는지 (PaymentCancelled 이벤트 발행 확인)
- [ ] inventory-events에 INVENTORY_FAILED 메시지 존재
- [ ] payment-events에 PAYMENT_CANCELLED 메시지 존재

---

## 시나리오 3: 오케스트레이션 Saga — 정상 흐름

### 요청

```bash
curl -X POST http://localhost:18081/api/saga/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "totalAmount": 20000, "itemCount": 1}'
```

### 기대 흐름

```
[Orchestrator] Saga 시작: sagaId=xxx
  → "PROCESS_PAYMENT" 커맨드 발행 (saga-payment-command)

[SagaPayment]  커맨드 수신 → 결제 처리 → PAYMENT_SUCCESS 응답 (saga-reply)

[Orchestrator] 결제 성공 → Saga 상태: PAYMENT_COMPLETED
  → "DEDUCT_INVENTORY" 커맨드 발행 (saga-inventory-command)

[SagaInventory] 커맨드 수신 → 재고 차감 → INVENTORY_SUCCESS 응답 (saga-reply)

[Orchestrator] 재고 성공 → Saga 상태: INVENTORY_DEDUCTED
  → "SEND_NOTIFICATION" 커맨드 발행 (saga-notification-command)

[SagaNotification] 커맨드 수신 → 알림 발송 → NOTIFICATION_SENT 응답 (saga-reply)

[Orchestrator] Saga 완료: status=COMPLETED
```

### Saga 상태 조회

```bash
curl http://localhost:18081/api/saga/orders/{orderId}/saga-status
```

### 기대 응답

```json
{
  "sagaId": "xxx",
  "orderId": "xxx",
  "currentStep": "NOTIFICATION_SENT",
  "status": "COMPLETED",
  "failReason": ""
}
```

---

## 시나리오 4: 오케스트레이션 Saga — 재고 부족 보상 흐름

### 선행 조건

오케스트레이션 Saga 재고(sagaStock) 10개 소진 후 11번째 주문

### 기대 흐름

```
[Orchestrator] Saga 시작
  → "PROCESS_PAYMENT" 커맨드 → [Payment] → 성공

[Orchestrator] 결제 성공
  → "DEDUCT_INVENTORY" 커맨드 → [Inventory] → 실패 💀 (재고 부족)

[Orchestrator] 재고 실패 → Saga 상태: COMPENSATING
  → "CANCEL_PAYMENT" 보상 커맨드 → [Payment] → 취소 완료

[Orchestrator] 보상 완료 → 주문 취소 → Saga 상태: COMPENSATED
```

### Saga 상태 조회 기대 응답

```json
{
  "sagaId": "xxx",
  "orderId": "xxx",
  "currentStep": "PAYMENT_CANCELLED",
  "status": "COMPENSATED",
  "failReason": "재고 부족: 현재재고=0"
}
```

### 검증 포인트

- [ ] Saga 상태가 COMPENSATED
- [ ] 주문 상태가 CANCELLED
- [ ] saga-payment-command에 CANCEL_PAYMENT 커맨드 존재
- [ ] saga-reply에 PAYMENT_CANCELLED 응답 존재
- [ ] 오케스트레이터가 직접 보상을 지시 (코레오그래피와의 차이)

---

## 코레오그래피 vs 오케스트레이션 — 로그 비교

### 코레오그래피 보상

```
[Inventory]  "InventoryFailed" 발행
[Payment]    "InventoryFailed" 구독 → 스스로 결제 취소    ← 아무도 지시하지 않음
[Order]      "PaymentCancelled" 구독 → 스스로 주문 취소  ← 아무도 지시하지 않음
```

### 오케스트레이션 보상

```
[Orchestrator] 재고 실패 감지 → "CANCEL_PAYMENT" 커맨드 발행  ← 오케스트레이터가 직접 지시
[Payment]      커맨드 수신 → 결제 취소
[Orchestrator] 결제 취소 확인 → 주문 취소                      ← 오케스트레이터가 직접 실행
```

> **핵심 차이**: 코레오그래피는 "알아서 반응", 오케스트레이션은 "지시받고 실행"

---

## 실행 명령어 종합

```bash
# 1. Kafka 클러스터 시작
cd docker && docker compose up -d

# 2. 서비스 시작 (각각 별도 터미널 또는 백그라운드)
./gradlew :service-order:bootRun
./gradlew :service-payment:bootRun
./gradlew :service-inventory:bootRun
./gradlew :service-notification:bootRun

# 3. 코레오그래피 테스트
curl -X POST http://localhost:18081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "totalAmount": 15000, "itemCount": 1}'

# 4. 오케스트레이션 Saga 테스트
curl -X POST http://localhost:18081/api/saga/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "totalAmount": 20000, "itemCount": 1}'

# 5. Saga 상태 조회
curl http://localhost:18081/api/saga/orders/{orderId}/saga-status

# 6. Kafka UI 확인
open http://localhost:8888

# 7. 서비스 종료
pkill -f "bootRun"

# 8. 클러스터 종료
cd docker && docker compose down -v
```
