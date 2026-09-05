package com.polyglotcommerce.payment.repository;

import com.polyglotcommerce.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Primo pagamento registrato per un ordine: serve a rendere idempotente
     * il consumo di {@code payment.requested}, che Kafka puo' consegnare
     * piu' di una volta.
     *
     * La saga crea un solo pagamento per ordine; l'endpoint REST, che resta
     * un ingresso manuale, non lo impedisce, quindi la query prende il primo
     * invece di pretendere che ce ne sia al massimo uno.
     */
    Optional<Payment> findFirstByOrderIdOrderByIdAsc(Long orderId);
}
