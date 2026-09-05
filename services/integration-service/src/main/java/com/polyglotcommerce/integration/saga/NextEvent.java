package com.polyglotcommerce.integration.saga;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Evento che la rotta Camel deve pubblicare come passo successivo della
 * saga: l'orchestratore decide "cosa" e "dove", la rotta si occupa del
 * trasporto.
 */
@Data
@AllArgsConstructor
public class NextEvent {

    private final String topic;
    private final String key;
    private final String json;
}
