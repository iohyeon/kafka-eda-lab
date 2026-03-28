rootProject.name = "kafka-eda-lab"

// 서비스 모듈 — 각각 독립 서비스 (별도 프로세스로 실행)
include("service-order")
include("service-payment")
include("service-inventory")
include("service-notification")

// 스트림 프로세싱 — Kafka Streams 실시간 집계/변환/조인
include("service-streams")

// 공유 모듈 — 이벤트 스키마, 공통 설정
include("common-event")
