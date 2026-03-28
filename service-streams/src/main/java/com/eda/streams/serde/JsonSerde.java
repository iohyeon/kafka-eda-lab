package com.eda.streams.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Kafka Streams용 JSON Serde.
 *
 * Kafka Streams는 토픽에서 읽은 바이트를 자바 객체로 변환해야 한다.
 * Spring Kafka의 JsonSerializer/Deserializer와 달리,
 * Streams DSL은 Serde 인터페이스를 요구하므로 직접 구현.
 *
 * 핵심: ObjectMapper는 인스턴스당 하나만 생성 (thread-safe).
 */
public class JsonSerde<T> implements Serde<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("JSON 직렬화 실패: " + type.getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, type);
            } catch (Exception e) {
                throw new RuntimeException("JSON 역직렬화 실패: " + type.getSimpleName(), e);
            }
        };
    }
}
