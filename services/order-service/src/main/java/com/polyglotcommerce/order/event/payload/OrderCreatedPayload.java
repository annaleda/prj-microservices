package com.polyglotcommerce.order.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Corpo ({@code data}) dell'evento {@code order.created}.
 *
 * E' un contratto pubblico verso gli altri servizi, quindi volutamente
 * separato dai DTO REST: contiene solo cio' che serve alla saga
 * (l'Inventory Service usa gli item per la riserva, l'Integration Service
 * l'importo totale per richiedere il pagamento).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCreatedPayload {

    private Long orderId;
    private String customerEmail;
    private BigDecimal totalAmount;
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Item {
        private Long productId;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
