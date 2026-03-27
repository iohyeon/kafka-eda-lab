# 장애 시뮬레이션 테스트 결과

**실행일시**: 2026-03-27 22:08 KST
**실행환경**: EmbeddedKafka (Docker 불필요)
**테스트 결과**: 4 tests, 0 failures, **100% PASS**
**소요 시간**: 15.393초

> **"코드를 짤 수 있냐"가 아니라, "장애가 터졌을 때 어디를 봐야 하는지 아느냐"**

---

## 장애 1: 오프셋 미커밋 시 메시지 재처리 — PASS (6.230초)

### 시나리오

```
Consumer 1: 메시지 읽음 → 처리 중 → 오프셋 커밋 전에 죽음 💀
Consumer 2: 같은 그룹으로 투입 → 마지막 커밋 지점부터 다시 읽음
→ 같은 메시지를 다시 처리 (At-Least-Once)
```

### 검증 결과

- [x] Consumer 1이 메시지를 읽었으나 커밋하지 않음
- [x] Consumer 1 죽음 (close without commit)
- [x] Consumer 2가 같은 메시지를 다시 읽음 (재처리 확인)
- [x] 메시지 유실 없음 — At-Least-Once 동작 확인

### 실무에서 어디를 보는가

| 확인 대상 | 도구/명령어 |
|----------|-----------|
| Consumer Lag 급증 | Kafka UI, Grafana 대시보드 |
| 마지막 커밋 오프셋 | `kafka-consumer-groups.sh --describe --group {group}` |
| Consumer Group 상태 | `kafka-consumer-groups.sh --describe` → EMPTY/DEAD |
| __consumer_offsets 토픽 | 커밋된 오프셋 직접 확인 |

### 교훈

> **Manual Commit + At-Least-Once에서는 커밋 전 죽으면 메시지가 재전달된다.**
> → 소비자 쪽 멱등성이 필수! (EDA-03, EDA-09)
> → event_handled 테이블 + UNIQUE(eventId)로 중복 방어 (kafka-pipeline-lab Phase 5)

---

## 장애 2: 중복 메시지 — 멱등성 없이 이중 처리 — PASS (4.828초)

### 시나리오

```
프로듀서가 ACK를 못 받아 재전송 → 같은 메시지가 브로커에 2건
또는 컨슈머 리밸런싱으로 같은 메시지를 다른 컨슈머가 다시 처리
→ 멱등성 없으면 결제 2번, 재고 2번 차감
```

### 검증 결과

- [x] 같은 eventId의 이벤트를 의도적으로 2번 발행
- [x] 컨슈머가 2건 모두 수신 확인
- [x] 멱등성 없으면 2번 처리됨을 확인

### 방어 방법 3가지

| 방법 | 구현 | 장단점 |
|------|------|--------|
| **DB UNIQUE 제약** | `event_handled` 테이블 + UNIQUE(eventId) | 비즈니스 TX와 원자적 롤백 가능 (권장) |
| **Idempotency-Key** | PG사 API 헤더에 멱등 키 전송 | PG사가 중복 결제 방지 |
| **Redis SET NX** | 인메모리 중복 체크 | 빠르지만 비즈니스 실패 시 재처리 불가 |

### 실무에서 어디를 보는가

| 증상 | 확인 방법 |
|------|----------|
| 토픽 메시지 수 ≠ 처리 건수 | 토픽 offset vs event_handled 테이블 count 비교 |
| 중복 결제/발급 | PG사 결제 로그에서 같은 주문 ID 검색 |
| 멱등성 테이블 | `SELECT * FROM event_handled WHERE event_id = ?` |

### 교훈

> **At-Least-Once = 중복이 올 수 있다. 멱등성은 선택이 아니라 필수.**
> DB UNIQUE 제약이 가장 안전 — 비즈니스 실패 시 TX 롤백으로 재처리 가능.

---

## 장애 3: 이벤트 체인 끊김 — 재고 서비스 다운 — PASS (1.962초)

### 시나리오

```
결제 성공 → PaymentCompleted 발행 → 재고 서비스 다운 💀
→ payment-events에 메시지는 계속 쌓이는데 아무도 소비 안 함
→ 코레오그래피에서는 지휘자가 없으니 아무도 이걸 모름
```

### 체인 끊김 시 관찰되는 현상

```
[정상 시] order-events → payment-events → inventory-events → notification-events
         (메시지 흐름)    (메시지 흐름)     (메시지 흐름)       (메시지 흐름)

[재고 다운] order-events → payment-events → ❌ inventory-events  ❌ notification-events
           (계속 쌓임)    (계속 쌓임)      (Consumer Lag ↑↑↑)   (새 메시지 없음)
```

### 실무에서 어디를 보는가

| 확인 대상 | 도구/명령어 | 기대 값 |
|----------|-----------|---------|
| Consumer Lag | `kafka-consumer-groups.sh --describe --group inventory-group` | LAG가 계속 증가 |
| 토픽별 메시지 수 | Kafka UI → Topics → Messages | payment-events ↑, inventory-events 정체 |
| 서비스 상태 | Actuator `/health` | inventory: DOWN |
| 알림 발송 여부 | notification 서비스 로그 | 새 로그 없음 |

### 복구 후

```
재고 서비스 재시작 → 밀린 이벤트 일괄 처리
Kafka에 메시지가 retention 기간 동안 남아있으므로 유실 없음
이것이 Kafka의 내구성(Durability) 가치
```

### 교훈

> **코레오그래피의 약점: 어디서 끊겼는지 한눈에 안 보인다.**
> Consumer Lag 모니터링이 핵심 — Lag이 계속 증가하면 해당 서비스 장애 의심.
> 오케스트레이션이었다면 오케스트레이터가 타임아웃으로 감지하고 알림을 보낼 수 있음.

---

## 장애 4: 보상 실패 — 결제 취소가 실패하면? — PASS (0.373초)

### 시나리오

```
주문 → 결제 성공 → 재고 실패 → 결제 취소 시도 → PG사 장애로 실패 💀
→ 결제는 됐는데, 재고도 없고, 취소도 안 됨
→ "보상의 보상"은 없다
```

### 대응 전략 (4단계 방어선)

```
1단계: DLQ (Dead Letter Queue)
  → 결제 취소 실패 메시지를 DLQ 토픽으로 격리
  → payment-events.DLQ
  → 헤더에 원본 정보 보존 (원본 토픽, 에러 메시지, 재시도 횟수)

2단계: 재시도 (Exponential Backoff)
  → 1초 → 2초 → 4초 → 8초 간격으로 재시도
  → PG사 일시적 장애라면 재시도로 해결
  → 최대 재시도 횟수 초과 시 → DLQ

3단계: 알림 + 수동 처리
  → DLQ 메시지 발생 시 Slack/이메일 알림
  → 운영팀이 PG사 관리자 페이지에서 수동 환불
  → DLQ 메시지에 원본 orderId가 있으므로 추적 가능

4단계: 정합성 체크 배치
  → 주기적 배치: 결제 COMPLETED + 주문 CANCELLED = 불일치
  → 불일치 발견 → 자동 환불 재시도 또는 알림
```

### 실무에서 어디를 보는가

| 확인 대상 | SQL/명령어 |
|----------|-----------|
| DLQ 토픽 | `kafka-console-consumer.sh --topic payment-events.DLQ` |
| 결제-주문 불일치 | `SELECT * FROM payments WHERE status='COMPLETED' AND order_id IN (SELECT order_id FROM orders WHERE status='CANCELLED')` |
| PG사 결제 내역 | PG사 관리자 페이지에서 해당 orderId 검색 |

### 교훈

> **보상의 보상은 없다.**
> DLQ + 재시도 + 수동 처리 + 정합성 배치가 방어선.
> 100% 자동화는 불가능 — 최후의 방어선은 사람(운영팀).

---

## 전체 요약

| 장애 | 핵심 질문 | 답 | 소요 |
|------|----------|-----|------|
| 오프셋 미커밋 | 메시지 유실? | 아니오, 재전달 (At-Least-Once) | 6.2s |
| 중복 메시지 | 이중 처리? | 멱등성 없으면 예, 있으면 아니오 | 4.8s |
| 체인 끊김 | 어디서 끊겼나? | Consumer Lag으로 감지 | 2.0s |
| 보상 실패 | 어떻게 복구? | DLQ → 재시도 → 수동 → 배치 | 0.4s |

### 실무 핵심 모니터링 대상

```
1. Consumer Lag      → 서비스 장애/지연 감지
2. DLQ 메시지 수     → 처리 실패 감지
3. 토픽별 메시지 수   → 이벤트 체인 끊김 감지
4. 상태 불일치 쿼리   → 보상 실패 감지
```

---

## 실행 방법

```bash
# 장애 시뮬레이션 테스트 실행 (Docker 불필요)
./gradlew :service-order:cleanTest :service-order:test \
  --tests "com.eda.order.FailureSimulationTest"
```
