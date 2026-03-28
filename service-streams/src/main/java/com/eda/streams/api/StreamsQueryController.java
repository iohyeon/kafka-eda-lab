package com.eda.streams.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kafka Streams Interactive Query API.
 *
 * Kafka Streams의 State Store는 로컬 RocksDB에 저장된다.
 * 이 API를 통해 외부에서 State Store를 조회할 수 있다.
 *
 * 실무에서 Interactive Query의 의미:
 * - DB 없이도 실시간 집계 결과를 API로 노출할 수 있다
 * - 하지만 State Store는 인스턴스 로컬이므로, 분산 환경에서는
 *   KafkaStreams.allMetadataForStore()로 어떤 인스턴스가 어떤 키를 가지고 있는지 찾아야 한다
 */
@Slf4j
@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class StreamsQueryController {

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("{\"status\":\"UP\",\"service\":\"kafka-streams\"}");
    }
}
