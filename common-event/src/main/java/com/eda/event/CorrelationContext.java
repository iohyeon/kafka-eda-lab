package com.eda.event;

/**
 * Correlation ID를 스레드 단위로 관리하는 컨텍스트.
 *
 * 하나의 요청이 만들어낸 모든 이벤트에 같은 ID를 붙인다.
 * ThreadLocal을 사용하여 서비스 내부에서 어디서든 접근 가능.
 *
 * 흐름:
 * 1. Consumer가 이벤트를 받으면 → 헤더에서 Correlation ID를 꺼내 set()
 * 2. 서비스 내부 로직 실행 → get()으로 어디서든 조회
 * 3. Producer가 이벤트를 발행할 때 → get()으로 꺼내서 헤더에 붙임
 * 4. 처리 완료 후 → clear()
 */
public final class CorrelationContext {

    public static final String HEADER_KEY = "X-Correlation-ID";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private CorrelationContext() {}

    public static void set(String correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    public static String get() {
        return CORRELATION_ID.get();
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }

    /**
     * Kafka 메시지 헤더에서 Correlation ID를 추출하여 ThreadLocal에 설정.
     * Consumer에서 메시지를 받을 때마다 호출.
     */
    public static String extractFromHeaders(org.apache.kafka.common.header.Headers headers) {
        org.apache.kafka.common.header.Header header = headers.lastHeader(HEADER_KEY);
        if (header != null) {
            String correlationId = new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
            set(correlationId);
            return correlationId;
        }
        return null;
    }
}
