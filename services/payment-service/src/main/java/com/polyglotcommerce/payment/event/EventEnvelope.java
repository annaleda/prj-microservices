package com.polyglotcommerce.payment.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope comune a tutti gli eventi pubblicati su Kafka (documento di
 * design, sezione 8 "Event Envelope").
 *
 * Ogni servizio ne mantiene una propria copia invece di condividere una
 * libreria comune: in un'architettura poliglotta il contratto tra servizi
 * e' il JSON sul topic, non una classe Java (l'Inventory Service, in
 * Python, costruisce lo stesso envelope a mano).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventEnvelope<T> {

    private String eventId;
    private String eventType;
    private int eventVersion;
    private Instant timestamp;
    private String correlationId;
    private String source;
    private T data;

    public static <T> EventEnvelope<T> of(String eventType, String correlationId, T data) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .eventVersion(1)
                .timestamp(Instant.now())
                .correlationId(correlationId != null ? correlationId : UUID.randomUUID().toString())
                .source("payment-service")
                .data(data)
                .build();
    }
}
