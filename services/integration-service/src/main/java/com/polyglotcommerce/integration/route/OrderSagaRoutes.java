package com.polyglotcommerce.integration.route;

import com.polyglotcommerce.integration.event.EventTopics;
import com.polyglotcommerce.integration.saga.SagaOrchestrator;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.springframework.stereotype.Component;

/**
 * Rotte Camel della saga Order -> Inventory -> Payment.
 *
 * Tutte hanno la stessa forma: consuma un evento, chiedi all'orchestratore
 * qual e' il passo successivo, pubblicalo (se c'e'). La decisione sta in
 * {@link SagaOrchestrator}, qui c'e' solo il trasporto.
 *
 * <pre>
 *   order.created ------------> (apre la saga, nessun evento in uscita)
 *   inventory.reserved -------> payment.requested
 *   inventory.rejected -------> order.cancelled
 *   payment.completed --------> order.updated   (ordine CONFIRMED)
 *   payment.failed -----------> order.cancelled (compensazione: l'Inventory
 *                                                Service rilascia le scorte)
 * </pre>
 */
@Component
public class OrderSagaRoutes extends RouteBuilder {

    private static final String CONSUMER_OPTIONS = "?groupId=integration-service&autoOffsetReset=earliest";
    private static final String DLQ_ENDPOINT = "direct:saga-dlq";

    private final SagaOrchestrator orchestrator;

    public OrderSagaRoutes(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void configure() {
        // Se un evento non e' processabile nemmeno dopo i tentativi, finisce
        // in DLQ invece di bloccare la rotta ripresentandosi all'infinito.
        //
        // La destinazione e' un endpoint "direct" e non direttamente
        // "kafka:saga.dlq" perche' Camel avvolge con l'error handler ogni
        // singolo processore di ogni rotta: puntando la DLQ su Kafka
        // nascerebbe un producer per processore (qui una quarantina), tutti
        // aperti verso il broker all'avvio. Con un endpoint direct il
        // producer Kafka e' uno solo, quello della rotta qui sotto.
        errorHandler(deadLetterChannel(DLQ_ENDPOINT)
                .maximumRedeliveries(2)
                .redeliveryDelay(1000L)
                .useOriginalMessage()
                .logExhaustedMessageHistory(true));

        from(DLQ_ENDPOINT)
                .routeId("saga-dlq")
                .log("Sending unprocessable event to " + EventTopics.SAGA_DLQ)
                .to("kafka:" + EventTopics.SAGA_DLQ);

        sagaStep(EventTopics.ORDER_CREATED, "onOrderCreated");
        sagaStep(EventTopics.INVENTORY_RESERVED, "onInventoryReserved");
        sagaStep(EventTopics.INVENTORY_REJECTED, "onInventoryRejected");
        sagaStep(EventTopics.PAYMENT_COMPLETED, "onPaymentCompleted");
        sagaStep(EventTopics.PAYMENT_FAILED, "onPaymentFailed");
    }

    private void sagaStep(String topic, String orchestratorMethod) {
        from("kafka:" + topic + CONSUMER_OPTIONS)
                .routeId("saga-" + topic)
                .log("Consumed ${header.kafka.TOPIC} (key=${header.kafka.KEY})")
                .bean(orchestrator, orchestratorMethod)
                // Un passo che non produce eventi (order.created) restituisce null.
                .filter(body().isNotNull())
                    // Le intestazioni kafka.* in ingresso (topic, partizione,
                    // offset del messaggio consumato) non devono finire sul
                    // messaggio prodotto: la chiave giusta viene reimpostata
                    // subito dopo.
                    .removeHeaders("kafka.*")
                    .setHeader(KafkaConstants.KEY, simple("${body.key}"))
                    .setHeader("nextTopic", simple("${body.topic}"))
                    .setBody(simple("${body.json}"))
                    .toD("kafka:${header.nextTopic}")
                    .log("Published ${header.nextTopic} (key=${header.kafka.KEY})")
                .end();
    }
}
