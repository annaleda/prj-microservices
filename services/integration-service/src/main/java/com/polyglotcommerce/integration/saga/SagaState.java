package com.polyglotcommerce.integration.saga;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** Quel che l'orchestratore ricorda di un ordine mentre la saga e' in corso. */
@Data
@AllArgsConstructor
public class SagaState {

    private final Long orderId;
    private final String correlationId;
    private final String customerEmail;
    /**
     * Importo da addebitare: lo conosce solo l'evento order.created, mentre
     * inventory.reserved (che innesca il pagamento) non lo trasporta. E' il
     * motivo principale per cui l'orchestratore ha bisogno di uno stato.
     */
    private final BigDecimal totalAmount;
    private final Instant startedAt;
}
