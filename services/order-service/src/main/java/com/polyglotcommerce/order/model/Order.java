package com.polyglotcommerce.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.OrderColumn;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identificativo dell'utente che ha creato l'ordine: il claim "sub"
     * del token, assegnato dall'identity provider.
     *
     * E' questo, e non l'email, a determinare di chi e' l'ordine.
     * L'email si puo' cambiare e chiunque puo' dichiararla al momento
     * della registrazione: legare la proprieta' a quella significherebbe
     * che registrarsi con l'indirizzo di un altro basta per vederne gli
     * ordini.
     *
     * Nullo sugli ordini creati prima che esistesse l'autenticazione:
     * non sono attribuibili a nessuna identita', e restano visibili solo
     * al personale interno.
     */
    @Column(name = "customer_id")
    private String customerId;

    /** Conservata per mostrarla e per le notifiche, non per autorizzare. */
    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "item_index")
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Perche' l'ordine e' stato annullato, come codice deciso dalla saga
     * (INVENTORY_REJECTED, PAYMENT_FAILED, SAGA_STATE_LOST).
     *
     * Un codice e non il testo dell'evento: e' quello che il checkout usa
     * per dire al cliente cosa e' andato storto, e un messaggio riformulato
     * non deve poter cambiare il comportamento del frontend.
     *
     * Nullo su ogni ordine non annullato, e su quelli annullati prima che
     * questa colonna esistesse.
     */
    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
