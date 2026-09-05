package com.polyglotcommerce.order.dto;

import com.polyglotcommerce.order.model.Order;
import com.polyglotcommerce.order.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {

    private Long id;
    private String customerEmail;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private List<OrderItemResponse> items;
    /** Valorizzato solo sugli ordini annullati dalla saga. */
    private String cancellationReason;
    private Instant createdAt;
    private Instant updatedAt;

    public static OrderResponse fromEntity(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .customerEmail(order.getCustomerEmail())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .items(order.getItems().stream()
                        .map(OrderItemResponse::fromEntity)
                        .collect(Collectors.toList()))
                .cancellationReason(order.getCancellationReason())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
