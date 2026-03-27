package com.eda.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 통합 테스트 전용 Application.
 * 모든 서비스 패키지를 스캔하여 하나의 컨텍스트에서 동작하게 한다.
 */
@SpringBootApplication(
        scanBasePackages = "com.eda",
        exclude = {}
)
@org.springframework.context.annotation.ComponentScan(
        basePackages = "com.eda",
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.eda.payment.KafkaConfig.class,
                        com.eda.inventory.KafkaConfig.class,
                        com.eda.notification.KafkaConfig.class,
                        com.eda.payment.PaymentApplication.class,
                        com.eda.inventory.InventoryApplication.class,
                        com.eda.notification.NotificationApplication.class
                }
        )
)
public class TestApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
