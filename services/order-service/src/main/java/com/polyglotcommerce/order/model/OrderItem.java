package com.polyglotcommerce.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    /**
     * Immagine del prodotto al momento dell'acquisto.
     *
     * Conservata qui come gia' il nome e il prezzo: un ordine e' una
     * ricevuta, e deve restare leggibile anche se il prodotto viene tolto
     * dal catalogo o gli viene cambiata la foto. Cercarla nel catalogo al
     * momento di mostrarla e' cio' che faceva sparire l'immagine dagli
     * ordini vecchi appena il prodotto spariva.
     *
     * Nulla sugli ordini creati prima che questa colonna esistesse, e sui
     * prodotti che non hanno un'immagine.
     */
    @Column(name = "image_url")
    private String imageUrl;
}
