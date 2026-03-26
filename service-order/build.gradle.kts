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
}
