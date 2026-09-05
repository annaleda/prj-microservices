package com.polyglotcommerce.catalog.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Inoltra su Kafka gli eventi di dominio, ma solo <b>dopo</b> il commit
 * della transazione che li ha generati: pubblicare prima significherebbe
 * annunciare un prodotto che un rollback potrebbe far sparire, o che i
 * consumer vedrebbero prima che sia effettivamente leggibile dal database.
 *
 * Stesso schema (e stesso limite) dell'Order Service: resta possibile che
 * il commit riesca e la pubblicazione fallisca. La soluzione completa e' il
 * pattern Transactional Outbox; qui, in un progetto dimostrativo, ci si
 * ferma al commit-then-publish.
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

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize event " + envelope.getEventType(), e);
        }

        // send() e' asincrona: restituisce subito, mentre il messaggio e'
        // ancora nel buffer del producer. Prima qui si scriveva "Published"
        // appena dopo la chiamata, il che era falso — quel log compariva
        // anche quando il broker non riceveva mai nulla.
        //
        // `completable()` trasforma il ListenableFuture di Spring in un
        // CompletableFuture; `whenComplete` viene eseguito quando l'esito
        // e' noto, in un caso o nell'altro.
        //
        // Non si fa `.get()`: bloccherebbe il thread della richiesta HTTP
        // in attesa del broker, trasformando un producer asincrono in uno
        // sincrono e legando la latenza dell'API a quella di Kafka.
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(event.getTopic(), event.getKey(), payload).completable();

        future.whenComplete((result, error) -> {
            if (error != null) {
                // ERROR e non WARN: la transazione e' gia' stata committata,
                // quindi il dato esiste ma nessuno e' stato avvisato. E'
                // esattamente la falla che il pattern Transactional Outbox
                // chiuderebbe: qui resta visibile solo nei log.
                log.error("FAILED to publish {} to topic {} (key={}, correlationId={}): {}",
                        envelope.getEventType(), event.getTopic(), event.getKey(),
                        envelope.getCorrelationId(), error.toString());
                return;
            }

            // Partizione e offset arrivano solo ora, dal broker: sono la
            // prova che il messaggio e' stato scritto davvero.
            log.info("Published {} to {}-{}@{} (key={}, correlationId={})",
                    envelope.getEventType(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    event.getKey(),
                    envelope.getCorrelationId());
        });
        // whenComplete gira sul thread di I/O del producer: quel thread
        // serve a tutte le pubblicazioni, quindi qui dentro si logga e
        // basta, senza lavoro pesante.
    }
}
