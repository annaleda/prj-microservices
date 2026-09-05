package com.polyglotcommerce.order.controller;

import com.polyglotcommerce.order.dto.OrderRequest;
import com.polyglotcommerce.order.dto.OrderResponse;
import com.polyglotcommerce.order.dto.OrderStatusUpdateRequest;
import com.polyglotcommerce.order.security.Caller;
import com.polyglotcommerce.order.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Un cliente vede i propri ordini; ADMIN e SUPPORT quelli di tutti. */
    @GetMapping
    public List<OrderResponse> findAll(@AuthenticationPrincipal Jwt jwt) {
        return orderService.findAllFor(Caller.from(jwt));
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.findByIdFor(id, Caller.from(jwt));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request,
                                                 @AuthenticationPrincipal Jwt jwt,
                                                 UriComponentsBuilder uriBuilder) {
        OrderResponse created = orderService.create(request, Caller.from(jwt));
        URI location = uriBuilder.path("/api/orders/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Elimina un ordine annullato (solo ADMIN, vedi SecurityConfig).
     * Gli ordini confermati non si eliminano: sono la traccia di una
     * vendita avvenuta.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        orderService.deleteCancelled(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable Long id, @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.updateStatus(id, request.getStatus());
    }
}
