package com.polyglotcommerce.order.service;

import com.polyglotcommerce.order.dto.OrderItemRequest;
import com.polyglotcommerce.order.dto.OrderRequest;
import com.polyglotcommerce.order.dto.OrderResponse;
import com.polyglotcommerce.order.exception.InvalidOrderStatusTransitionException;
import com.polyglotcommerce.order.exception.ResourceNotFoundException;
import com.polyglotcommerce.order.model.Order;
import com.polyglotcommerce.order.model.OrderItem;
import com.polyglotcommerce.order.model.OrderStatus;
import com.polyglotcommerce.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

// Nota: il documento di design prevede che la creazione di un ordine
// pubblichi l'evento order.created, che innesca la saga orchestrata
// dall'Integration Service (Order -> Inventory -> Payment). Kafka non
// e' ancora presente nel progetto (Phase 3 della roadmap): per ora
// l'ordine viene semplicemente persistito con stato CREATED, senza
// alcuna pubblicazione di eventi.
@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return OrderResponse.fromEntity(getOrderOrThrow(id));
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Order order = Order.builder()
                .customerEmail(request.getCustomerEmail())
                .status(OrderStatus.CREATED)
                .totalAmount(BigDecimal.ZERO)
                .build();

        for (OrderItemRequest itemRequest : request.getItems()) {
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .productId(itemRequest.getProductId())
                    .productName(itemRequest.getProductName())
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(itemRequest.getUnitPrice())
                    .build();
            order.getItems().add(item);
        }
        order.setTotalAmount(computeTotal(order.getItems()));

        return OrderResponse.fromEntity(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus newStatus) {
        Order order = getOrderOrThrow(id);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStatusTransitionException(
                    "Order " + id + " is already cancelled and its status cannot change");
        }

        order.setStatus(newStatus);
        return OrderResponse.fromEntity(orderRepository.save(order));
    }

    private Order getOrderOrThrow(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    private BigDecimal computeTotal(List<OrderItem> items) {
        return items.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
