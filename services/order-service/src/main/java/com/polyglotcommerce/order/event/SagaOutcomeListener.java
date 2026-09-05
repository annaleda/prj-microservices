package com.polyglotcommerce.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyglotcommerce.order.model.OrderStatus;
import com.polyglotcommerce.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Chiude il cerchio della saga: l'Integration Service, al termine
 * dell'orchestrazione Order -> Inventory -> Payment, pubblica
 * {@code order.updated} (successo) o {@code order.cancelled} (fallimento);
 * qui l'ordine viene allineato allo stato deciso dalla saga.
 *
 * L'Order Service non conosce ne' l'Inventory ne' il Payment Service:
 * riceve solo l'esito.
 */
@Component
public class SagaOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(SagaOutcomeListener.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public SagaOutcomeListener(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = EventTopics.ORDER_UPDATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderUpdated(String message) throws Exception {
        JsonNode data = objectMapper.readTree(message).path("data");
        Long orderId = data.path("orderId").asLong();
        OrderStatus status = OrderStatus.valueOf(data.path("status").asText());

        log.info("Saga outcome: order {} -> {}", orderId, status);
        orderService.applyStatusFromSaga(orderId, status);
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderCancelled(String message) throws Exception {
        JsonNode data = objectMapper.readTree(message).path("data");
        Long orderId = data.path("orderId").asLong();

        log.info("Saga outcome: order {} cancelled (reason={})", orderId, data.path("reason").asText(""));
        orderService.applyStatusFromSaga(orderId, OrderStatus.CANCELLED);
    }
}
