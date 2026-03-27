# kafka-eda-lab

**이벤트 드리븐 아키텍처(EDA) 설계 패턴**을 멀티 서비스 환경에서 학습하고 비교 실험하는 프로젝트.

단순히 Kafka를 연동하는 수준이 아닌, **코레오그래피 vs 오케스트레이션**, **Saga 패턴의 보상 흐름**, **서비스 간 결합도 차이**를 직접 구현하고 동작시켜서 체감하는 것이 목표.

## Tech Stack

- **Runtime**: Java 21, Spring Boot 3.4.4
- **Messaging**: Apache Kafka (KRaft, 3-Broker Cluster)
- **Persistence**: H2 (In-Memory), Spring Data JPA
- **Infra**: Docker Compose (Multi-Broker + Kafka UI)

## Architecture

### 멀티 서비스 구조

```
┌─────────────────┐
│  service-order   │ :18081 — 주문 생성, Saga 오케스트레이터
├─────────────────┤
│  service-payment │ :18082 — 결제 처리, 결제 취소(보상)
├─────────────────┤
│ service-inventory│ :8083  — 재고 차감, 재고 복구(보상)
├─────────────────┤
│service-notification│ :18084 — 알림 발송 (보상 불가, 마지막 단계)
├─────────────────┤
│  common-event    │        — 공유 이벤트 스키마
└─────────────────┘
```

### Kafka 클러스터

```
Kafka 3-Broker KRaft (ZooKeeper 없음)
├── Replication Factor = 3
├── Min ISR = 2
├── acks = all + Idempotent Producer
└── Manual Commit (At-Least-Once)
```

## 구현된 EDA 패턴

### 1. 코레오그래피 (Choreography)

**지휘자 없이** 각 서비스가 이벤트에 반응하여 독립적으로 처리.

```
POST /api/orders

[주문] → "OrderCreated" → [결제] → "PaymentCompleted" → [재고] → "InventoryDeducted" → [알림]

- 주문 서비스는 결제/재고/알림의 존재를 모른다
- 각 서비스가 관심 있는 이벤트만 구독
- 서비스 추가 시 기존 코드 변경 없음
```

### 2. 코레오그래피 Saga (보상 체인)

재고 부족 시 **이벤트 체인**으로 보상이 전파.

```
[주문] → [결제 성공] → [재고 실패 💀]
                         → "InventoryFailed"
         [결제 취소] ←──── 구독
           → "PaymentCancelled"
[주문 취소] ←──── 구독

- 아무도 "결제 취소해"라고 지시하지 않는다
- 각 서비스가 실패 이벤트를 구독하여 스스로 보상
```

### 3. 오케스트레이션 Saga (중앙 지휘자)

**오케스트레이터**가 전체 Saga 상태를 DB에 저장하며 흐름을 관리.

```
POST /api/saga/orders
GET  /api/saga/orders/{orderId}/saga-status

[오케스트레이터]
  1. "결제해" 커맨드 → [결제] → "성공" 응답
  2. "재고 빼" 커맨드 → [재고] → "실패" 응답 💀
  3. "결제 취소해" 보상 커맨드 → [결제] → "취소완료" 응답
  4. 주문 취소 → Saga 상태: COMPENSATED

- 전체 흐름이 오케스트레이터 코드 한 곳에 있다
- Saga 상태를 DB에 저장하여 장애 복구 가능
- 이벤트(사실 통보) vs 커맨드(지시)의 차이를 체감
```

## 코레오그래피 vs 오케스트레이션 비교

| | 코레오그래피 | 오케스트레이션 |
|---|---|---|
| **API** | `POST /api/orders` | `POST /api/saga/orders` |
| **지휘자** | 없음 | OrderSagaOrchestrator |
| **통신 방식** | 이벤트 (사실 통보) | 커맨드 (지시) + Reply (응답) |
| **결합도** | 느슨함 | 상대적으로 강함 |
| **흐름 파악** | 여러 서비스에 분산 | 오케스트레이터 한 곳 |
| **보상** | 이벤트 체인 (각자 알아서) | 오케스트레이터가 직접 지시 |
| **상태 추적** | 없음 | DB에 Saga 상태 저장 |
| **적합한 경우** | 단순한 흐름 | 복잡한 흐름, 순서/보상 중요 |

## 프로젝트 구조

```
kafka-eda-lab/
├── common-event/                  # 공유 이벤트 스키마
│   └── src/main/java/com/eda/event/
│       ├── OrderEvent.java        # 코레오그래피 이벤트
│       ├── PaymentEvent.java
│       ├── InventoryEvent.java
│       ├── SagaCommand.java       # 오케스트레이션 커맨드
│       ├── SagaReply.java         # 오케스트레이션 응답
│       └── Topics.java            # 토픽명 상수
├── service-order/                 # 주문 서비스 (:18081)
│   └── src/main/java/com/eda/order/
│       ├── OrderController.java          # 코레오그래피 API
│       ├── OrderService.java
│       ├── OrderEventProducer.java
│       ├── OrderCompensationConsumer.java # 코레오그래피 Saga 보상
│       └── saga/
│           ├── OrderSaga.java            # Saga 상태 엔티티
│           ├── OrderSagaOrchestrator.java # 오케스트레이터 (핵심)
│           └── SagaOrderController.java   # 오케스트레이션 API
├── service-payment/               # 결제 서비스 (:18082)
│   └── src/main/java/com/eda/payment/
│       ├── PaymentEventConsumer.java     # 코레오그래피 + 보상
│       ├── PaymentEventProducer.java
│       └── SagaPaymentHandler.java       # 오케스트레이션 커맨드 핸들러
├── service-inventory/             # 재고 서비스 (:18083)
│   └── src/main/java/com/eda/inventory/
│       ├── InventoryEventConsumer.java   # 코레오그래피 + 보상
│       ├── InventoryEventProducer.java
│       └── SagaInventoryHandler.java     # 오케스트레이션 커맨드 핸들러
├── service-notification/          # 알림 서비스 (:18084)
│   └── src/main/java/com/eda/notification/
│       ├── NotificationEventConsumer.java # 코레오그래피
│       └── SagaNotificationHandler.java   # 오케스트레이션 커맨드 핸들러
├── docker/
│   └── docker-compose.yml         # Kafka 3-Broker KRaft + Kafka UI
└── docs/
    └── test-scenarios.md          # 테스트 시나리오 및 기대 로그 패턴
```

## 실행 방법

### 1. Kafka 클러스터 실행

```bash
cd docker
docker compose up -d
```

- Kafka UI: http://localhost:8888

> **포트 참고**: 기존 kafka-pipeline-lab(8081~8084, 19092~39092)과 충돌 방지를 위해
> 서비스 포트 18081~18084, Kafka 포트 9092~9094, Kafka UI 8888을 사용.

### 2. 서비스 실행

```bash
# 각 서비스를 별도 터미널에서 실행
./gradlew :service-order:bootRun        # :18081
./gradlew :service-payment:bootRun      # :18082
./gradlew :service-inventory:bootRun    # :18083
./gradlew :service-notification:bootRun # :18084
```

### 3. 테스트

**코레오그래피 — 정상 흐름:**
```bash
curl -X POST http://localhost:18081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "totalAmount": 15000, "itemCount": 1}'
```

**코레오그래피 Saga — 재고 부족 보상 (11번째 주문):**
```bash
# 재고 10개 소진 후 11번째 주문
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:18081/api/orders \
    -H "Content-Type: application/json" \
    -d "{\"userId\": \"user-$i\", \"totalAmount\": 10000, \"itemCount\": 1}"
  sleep 1
done

# 11번째 → 보상 체인 발동 (결제 취소 → 주문 취소)
curl -X POST http://localhost:18081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-11", "totalAmount": 10000, "itemCount": 1}'
```

**오케스트레이션 Saga — 정상 흐름:**
```bash
curl -X POST http://localhost:18081/api/saga/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "totalAmount": 20000, "itemCount": 1}'
```

**Saga 상태 조회:**
```bash
curl http://localhost:18081/api/saga/orders/{orderId}/saga-status

# 기대 응답 (정상): {"currentStep": "NOTIFICATION_SENT", "status": "COMPLETED"}
# 기대 응답 (보상): {"currentStep": "PAYMENT_CANCELLED", "status": "COMPENSATED"}
```

### 4. 상세 테스트 시나리오

[docs/test-scenarios.md](docs/test-scenarios.md) — 4개 시나리오 + 기대 로그 패턴 + 검증 체크리스트

## 학습 연결

이 프로젝트는 [EDA 학습 시리즈](../fileSystem/learning/기술%20공부/kafka/)와 연결됩니다:

- **EDA-01**: 동기 호출의 강결합 → 이 프로젝트에서 메시지 브로커로 해결
- **EDA-06**: `acks=all` + `min.insync.replicas=2` 실무 표준 적용
- **EDA-07**: Manual Commit (At-Least-Once) 적용
- **EDA-09**: Idempotent Producer 적용
- **EDA-14**: 코레오그래피 vs 오케스트레이션 두 패턴 구현
- **EDA-15**: Saga 패턴 — 코레오그래피 Saga + 오케스트레이션 Saga 구현
- **EDA-16**: Outbox 패턴 + CDC — DB↔Kafka 원자성 문제
- **EDA-17**: CQRS + Kafka — 읽기/쓰기 분리 설계

## Endpoints

| Service | URL |
|---------|-----|
| Order Service | http://localhost:18081 |
| Payment Service | http://localhost:18082 |
| Inventory Service | http://localhost:18083 |
| Notification Service | http://localhost:18084 |
| Kafka UI | http://localhost:8888 |

## Key Design Decisions

| 결정 | 선택 | 근거 |
|------|------|------|
| Broker 수 | 3 (KRaft) | 과반수 투표 최소 홀수, ZooKeeper 없음 (EDA-13) |
| acks | all | 메시지 유실 방지 (EDA-06) |
| min.insync.replicas | 2 | ISR 1대일 때 쓰기 거부 (EDA-06) |
| ACK 모드 | Manual | 처리 완료 후에만 offset 커밋 (EDA-07) |
| Idempotent Producer | true | PID+Seq 기반 중복 전송 방지 (EDA-09) |
| Saga 상태 저장 | DB | 오케스트레이터 장애 시 복구 가능 |
| 알림 단계 순서 | 마지막 | 보상 불가 동작이므로 Saga 최후단에 배치 (EDA-15) |
| 포트 범위 | 18081~18084 | kafka-pipeline-lab(8081~8084)과 충돌 방지 |
