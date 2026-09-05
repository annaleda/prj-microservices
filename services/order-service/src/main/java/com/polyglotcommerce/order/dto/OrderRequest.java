package com.polyglotcommerce.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Nota: non contiene piu' l'email del cliente. L'ordine viene intestato a
 * chi presenta il token, non a un indirizzo scritto nella richiesta.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;
}
