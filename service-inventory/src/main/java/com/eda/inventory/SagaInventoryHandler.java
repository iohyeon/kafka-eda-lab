package com.eda.inventory;

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
 * 오케스트레이션 Saga — 재고 서비스 커맨드 핸들러.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaInventoryHandler {

    private final KafkaTemplate<String, SagaReply> replyTemplate;

    // 시뮬레이션용 재고 (코레오그래피와 별도)
    private int sagaStock = 10;

    @KafkaListener(
            topics = Topics.SAGA_INVENTORY_COMMAND,
            groupId = "saga-inventory-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleCommand(ConsumerRecord<String, SagaCommand> record, Acknowledgment ack) {
        SagaCommand command = record.value();

        switch (command.commandType()) {
            case DEDUCT_INVENTORY -> deductInventory(command);
            case RESTORE_INVENTORY -> restoreInventory(command);
            default -> log.warn("[SagaInventory] 알 수 없는 커맨드: {}", command.commandType());
        }

        ack.acknowledge();
    }

    private void deductInventory(SagaCommand command) {
        log.info("[SagaInventory] 재고 차감: sagaId={}, 현재재고={}", command.sagaId(), sagaStock);

        if (sagaStock <= 0) {
            log.error("[SagaInventory] 재고 부족!");
            replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                    command.sagaId(), command.orderId(),
                    SagaReply.ReplyType.INVENTORY_FAILED,
                    "재고 부족: 현재재고=" + sagaStock,
                    LocalDateTime.now()
            ));
            return;
        }

        sagaStock--;
        log.info("[SagaInventory] 재고 차감 완료: 남은재고={}", sagaStock);

        replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                command.sagaId(), command.orderId(),
                SagaReply.ReplyType.INVENTORY_SUCCESS,
                null, LocalDateTime.now()
        ));
    }

    private void restoreInventory(SagaCommand command) {
        sagaStock++;
        log.info("[SagaInventory] 재고 복구 (보상): sagaId={}, 복구후재고={}", command.sagaId(), sagaStock);

        replyTemplate.send(Topics.SAGA_REPLY, command.orderId(), new SagaReply(
                command.sagaId(), command.orderId(),
                SagaReply.ReplyType.INVENTORY_RESTORED,
                null, LocalDateTime.now()
        ));
    }
}
