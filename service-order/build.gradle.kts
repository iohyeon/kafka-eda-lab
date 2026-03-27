dependencies {
    implementation(project(":common-event"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // DB
    runtimeOnly("com.h2database:h2")

    // Jackson
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Test — EmbeddedKafka (Docker 없이 JVM 내 Kafka 브로커)
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.awaitility:awaitility")

    // 테스트에서 다른 서비스 모듈의 Consumer/Handler를 로드
    testImplementation(project(":service-payment"))
    testImplementation(project(":service-inventory"))
    testImplementation(project(":service-notification"))
}
