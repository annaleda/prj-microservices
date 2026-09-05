package com.polyglotcommerce.payment.event;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Evento applicativo interno (Spring), non ancora su Kafka: viene
 * pubblicato dentro la transazione e inoltrato al broker solo dopo il
 * commit (vedi {@link OutboundEventForwarder}).
 */
@Data
@AllArgsConstructor
public class OutboundEvent {

    private final String topic;
    private final String key;
    private final EventEnvelope<?> envelope;
}
