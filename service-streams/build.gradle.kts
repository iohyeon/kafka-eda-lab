dependencies {
    implementation(project(":common-event"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Kafka Streams
    implementation("org.apache.kafka:kafka-streams")
    implementation("org.springframework.kafka:spring-kafka")

    // Jackson (이벤트 직렬화/역직렬화)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Test — TopologyTestDriver (JVM 내 Streams 테스트)
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.awaitility:awaitility")
}
