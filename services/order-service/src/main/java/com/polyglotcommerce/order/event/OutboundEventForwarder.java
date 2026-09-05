package com.polyglotcommerce.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Inoltra su Kafka gli eventi di dominio, ma solo <b>dopo</b> il commit
 * della transazione che li ha generati: pubblicare prima significherebbe
 * annunciare un ordine che un rollback potrebbe far sparire, o che i
 * consumer (e le risposte della saga) vedrebbero prima che sia
 * effettivamente leggibile dal database.
 *
 * Nota: resta comunque possibile che il commit riesca e la pubblicazione
 * fallisca. La soluzione completa e' il pattern Transactional Outbox
 * (evento scritto nella stessa transazione su una tabella e spedito da un
 * relay separato); qui, in un progetto dimostrativo, ci si ferma al
 * commit-then-publish.
 */
@Component
public class OutboundEventForwarder {

    private static final Logger log = LoggerFactory.getLogger(OutboundEventForwarder.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboundEventForwarder(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboundEvent(OutboundEvent event) {
        EventEnvelope<?> envelope = event.getEnvelope();
        try {
            kafkaTemplate.send(event.getTopic(), event.getKey(), objectMapper.writeValueAsString(envelope));
            log.info("Published {} to topic {} (key={}, correlationId={})",
                    envelope.getEventType(), event.getTopic(), event.getKey(), envelope.getCorrelationId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize event " + envelope.getEventType(), e);
        }
    }
}
