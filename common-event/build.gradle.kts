// 공유 이벤트 모듈 — 서비스 간 이벤트 스키마 정의
// Spring Boot 실행 가능 jar가 아닌 라이브러리 모듈

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.getByName<Jar>("jar") {
    enabled = true
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}
