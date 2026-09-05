package com.polyglotcommerce.integration.saga;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stato delle saghe in corso, tenuto in memoria.
 *
 * Limite dichiarato: al riavvio dell'Integration Service le saghe ancora
 * aperte perdono il loro stato (vedi come viene gestito il caso in
 * {@link SagaOrchestrator#onInventoryReserved}). Un'implementazione di
 * produzione userebbe uno store persistente — una tabella dedicata, o il
 * Saga Service di Camel — cosi' che l'orchestratore possa riprendere da
 * dove era rimasto.
 */
@Component
public class SagaStateStore {

    private final Map<Long, SagaState> states = new ConcurrentHashMap<>();

    public void start(SagaState state) {
        states.put(state.getOrderId(), state);
    }

    public Optional<SagaState> find(Long orderId) {
        return Optional.ofNullable(states.get(orderId));
    }

    public void complete(Long orderId) {
        states.remove(orderId);
    }

    public int size() {
        return states.size();
    }
}
