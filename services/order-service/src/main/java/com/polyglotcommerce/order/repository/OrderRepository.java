package com.polyglotcommerce.order.repository;

import com.polyglotcommerce.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Ordini di un cliente, dal piu' recente: la lista e' uno storico, e
     * senza un ordinamento esplicito il database e' libero di
     * restituirli come capita.
     *
     * La ricerca e' per identificativo dell'utente e non per email: due
     * account diversi possono dichiarare la stessa email, un
     * identificativo no.
     */
    List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId);
}
