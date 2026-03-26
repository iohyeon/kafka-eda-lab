package com.eda.notification;

import com.eda.event.SagaCommand;
import com.eda.event.SagaReply;
import com.eda.event.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 오케스트레이션 Saga — 알림 서비스 커맨드 핸들러.
 * Saga의 마지막 단계. 보상 불가 동작이므로 마지막에 배치.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaNotificationHandler {

    private final KafkaTemplate<String, SagaReply> replyTemplate;

    @KafkaListener(
            topics = Topics.SAGA_NOTIFICATION_COMMAND,
            groupId = "saga-notification-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleCommand(ConsumerRecord<String, SagaCommand> record, Acknowledgment ack) {
        SagaCommand command = record.value();

        if (command.commandType() != SagaCommand.CommandType.SEND_NOTIFICATION) {
            ack.acknowledge();
            return;
        }

        log.info("[SagaNotification] 알림 발송: sagaId={}, orderId={}",
                command.sagaId(), command.orderId());

        // 알림 발송 (시뮬레이션)
        log.info("[SagaNotification] 주문 완료 알림 발송 성공");

        replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                command.sagaId(), command.orderId(),
                SagaReply.ReplyType.NOTIFICATION_SENT,
                null, LocalDateTime.now()
        ));

        ack.acknowledge();
    }
}
