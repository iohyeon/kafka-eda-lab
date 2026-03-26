package com.eda.payment;

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
 * 오케스트레이션 Saga — 결제 서비스 커맨드 핸들러.
 *
 * 코레오그래피와의 차이:
 * - 코레오그래피: "주문완료" 이벤트를 듣고 알아서 결제
 * - 오케스트레이션: 오케스트레이터의 "결제해" 커맨드를 받아서 결제
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaPaymentHandler {

    private final KafkaTemplate<String, SagaReply> replyTemplate;

    @KafkaListener(
            topics = Topics.SAGA_PAYMENT_COMMAND,
            groupId = "saga-payment-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleCommand(ConsumerRecord<String, SagaCommand> record, Acknowledgment ack) {
        SagaCommand command = record.value();

        switch (command.commandType()) {
            case PROCESS_PAYMENT -> processPayment(command);
            case CANCEL_PAYMENT -> cancelPayment(command);
            default -> log.warn("[SagaPayment] 알 수 없는 커맨드: {}", command.commandType());
        }

        ack.acknowledge();
    }

    private void processPayment(SagaCommand command) {
        log.info("[SagaPayment] 결제 처리: sagaId={}, orderId={}, amount={}",
                command.sagaId(), command.orderId(), command.amount());

        try {
            // 결제 로직 (시뮬레이션)
            log.info("[SagaPayment] PG사 결제 요청 성공");

            replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                    command.sagaId(), command.orderId(),
                    SagaReply.ReplyType.PAYMENT_SUCCESS,
                    null, LocalDateTime.now()
            ));

        } catch (Exception e) {
            replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                    command.sagaId(), command.orderId(),
                    SagaReply.ReplyType.PAYMENT_FAILED,
                    e.getMessage(), LocalDateTime.now()
            ));
        }
    }

    private void cancelPayment(SagaCommand command) {
        log.info("[SagaPayment] 결제 취소 (보상): sagaId={}, orderId={}",
                command.sagaId(), command.orderId());

        // 결제 취소 로직 (시뮬레이션)
        log.info("[SagaPayment] PG사 결제 취소 완료");

        replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                command.sagaId(), command.orderId(),
                SagaReply.ReplyType.PAYMENT_CANCELLED,
                null, LocalDateTime.now()
        ));
    }
}
