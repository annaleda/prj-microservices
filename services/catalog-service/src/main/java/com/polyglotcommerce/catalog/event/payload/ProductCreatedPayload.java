package com.polyglotcommerce.catalog.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dati dell'evento {@code product.created}.
 *
 * Non contiene il prezzo ne' la categoria: chi consuma questo evento oggi
 * e' l'Inventory Service, che deve solo sapere che esiste un nuovo prodotto
 * da tracciare a magazzino. Il resto delle informazioni resta nel Catalog
 * Service, che ne e' il proprietario.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductCreatedPayload {

    private Long productId;
    private String sku;
    private String name;
}
