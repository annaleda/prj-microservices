package com.polyglotcommerce.integration.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyglotcommerce.integration.event.EventEnvelope;
import com.polyglotcommerce.integration.event.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestratore della saga Order -> Inventory -> Payment (documento di
 * design, sezione "Saga Orchestration").
 *
 * Ogni metodo riceve il JSON di un evento e restituisce il passo successivo
 * ({@link NextEvent}) oppure {@code null} se non c'e' nulla da pubblicare.
 * La conoscenza di Kafka sta tutta nelle rotte Camel, qui c'e' solo la
 * logica di coordinamento.
 *
 * I servizi di dominio restano ignari l'uno dell'altro: l'Order Service non
 * sa che esistono scorte o pagamenti, l'Inventory Service non sa che esiste
 * un pagamento; e' questo componente a legare i passi e a innescare le
 * compensazioni.
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    /**
     * L'evento order.created non trasporta un metodo di pagamento (il
     * checkout non lo chiede ancora): finche' non esiste, la richiesta di
     * pagamento ne dichiara uno di default.
     */
    private static final String DEFAULT_PAYMENT_METHOD = "CARD";

    /**
     * Codici del motivo di annullamento, in aggiunta al testo libero.
     *
     * Il testo ("Insufficient stock for product 10...") serve a chi legge i
     * log; il codice serve a chi deve decidere qualcosa, ad esempio il
     * checkout che vuole dire al cliente se il problema erano le scorte o il
     * pagamento. Far dipendere quella scelta dal testo significherebbe
     * spezzare il frontend il giorno in cui si riformula un messaggio.
     */
    private static final String REASON_INVENTORY_REJECTED = "INVENTORY_REJECTED";
    private static final String REASON_PAYMENT_FAILED = "PAYMENT_FAILED";
    private static final String REASON_SAGA_STATE_LOST = "SAGA_STATE_LOST";

    private final SagaStateStore stateStore;
    private final ObjectMapper objectMapper;

    public SagaOrchestrator(SagaStateStore stateStore, ObjectMapper objectMapper) {
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    /** Passo 1: la saga si apre. Le scorte le riserva l'Inventory Service, che consuma lo stesso evento. */
    public NextEvent onOrderCreated(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        JsonNode data = envelope.path("data");
        Long orderId = data.path("orderId").asLong();

        stateStore.start(new SagaState(
                orderId,
                envelope.path("correlationId").asText(null),
                data.path("customerEmail").asText(null),
                new BigDecimal(data.path("totalAmount").asText("0")),
                Instant.now()));

        log.info("Saga started for order {} ({} in corso)", orderId, stateStore.size());
        return null;
    }

    /** Passo 2 (successo): scorte riservate, si chiede il pagamento. */
    public NextEvent onInventoryReserved(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        Long orderId = envelope.path("data").path("orderId").asLong();
        String correlationId = envelope.path("correlationId").asText(null);

        Optional<SagaState> state = stateStore.find(orderId);
        if (!state.isPresent()) {
            // Puo' succedere solo se l'orchestratore e' ripartito mentre la
            // saga era aperta (lo stato e' in memoria). Non conoscendo
            // l'importo non si puo' chiedere il pagamento: si annulla
            // l'ordine, il che fa anche rilasciare le scorte appena
            // riservate, invece di lasciare ordine e scorte bloccati.
            log.warn("No saga state for order {}: cancelling instead of requesting payment", orderId);
            return orderCancelled(orderId, correlationId, REASON_SAGA_STATE_LOST,
                    "Saga state lost (orchestrator restarted)");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("amount", state.get().getTotalAmount());
        payload.put("method", DEFAULT_PAYMENT_METHOD);

        log.info("Order {}: stock reserved, requesting payment of {}", orderId, state.get().getTotalAmount());
        return next(EventTopics.PAYMENT_REQUESTED, orderId,
                EventEnvelope.of("PAYMENT_REQUESTED", state.get().getCorrelationId(), payload));
    }

    /** Passo 2 (fallimento): scorte insufficienti, la saga si chiude subito. Nulla da compensare. */
    public NextEvent onInventoryRejected(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        JsonNode data = envelope.path("data");
        Long orderId = data.path("orderId").asLong();

        stateStore.complete(orderId);
        log.info("Order {}: stock rejected, cancelling order", orderId);
        return orderCancelled(orderId, envelope.path("correlationId").asText(null),
                REASON_INVENTORY_REJECTED, "Inventory rejected: " + data.path("reason").asText(""));
    }

    /** Passo 3 (successo): la saga si chiude confermando l'ordine. */
    public NextEvent onPaymentCompleted(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        Long orderId = envelope.path("data").path("orderId").asLong();

        stateStore.complete(orderId);
        log.info("Order {}: payment completed, confirming order", orderId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("status", "CONFIRMED");

        return next(EventTopics.ORDER_UPDATED, orderId,
                EventEnvelope.of("ORDER_UPDATED", envelope.path("correlationId").asText(null), payload));
    }

    /**
     * Passo 3 (fallimento): pagamento rifiutato, si compensa.
     *
     * La compensazione non richiede un evento dedicato: annullare l'ordine
     * e' anche il segnale che fa rilasciare le scorte, perche' l'Inventory
     * Service consuma order.cancelled e ripristina le prenotazioni di
     * quell'ordine (pubblicando poi inventory.released).
     */
    public NextEvent onPaymentFailed(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        JsonNode data = envelope.path("data");
        Long orderId = data.path("orderId").asLong();

        stateStore.complete(orderId);
        log.info("Order {}: payment failed, cancelling order and releasing stock", orderId);
        return orderCancelled(orderId, envelope.path("correlationId").asText(null),
                REASON_PAYMENT_FAILED, "Payment failed: " + data.path("reason").asText(""));
    }

    private NextEvent orderCancelled(Long orderId, String correlationId, String reasonCode, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("reasonCode", reasonCode);
        payload.put("reason", reason);

        return next(EventTopics.ORDER_CANCELLED, orderId,
                EventEnvelope.of("ORDER_CANCELLED", correlationId, payload));
    }

    private NextEvent next(String topic, Long orderId, EventEnvelope<?> envelope) {
        try {
            return new NextEvent(topic, String.valueOf(orderId), objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize event " + envelope.getEventType(), e);
        }
    }
}
