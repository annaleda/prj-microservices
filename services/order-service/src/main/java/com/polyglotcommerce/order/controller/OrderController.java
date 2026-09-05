package com.polyglotcommerce.order.controller;

import com.polyglotcommerce.order.dto.OrderRequest;
import com.polyglotcommerce.order.dto.OrderResponse;
import com.polyglotcommerce.order.dto.OrderStatusUpdateRequest;
import com.polyglotcommerce.order.security.Caller;
import com.polyglotcommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Ordini", description = "Ordini dei clienti. Tutte le operazioni richiedono un token.")
@SecurityRequirement(name = "bearer-jwt")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Elenca gli ordini",
            description = "Un cliente vede solo i propri ordini, ADMIN e SUPPORT quelli di tutti. "
                    + "Il filtro e' sui dati e non sull'URL, quindi il percorso e' lo stesso per "
                    + "entrambi. Ordinati dal piu' recente.")
    @GetMapping
    public List<OrderResponse> findAll(@AuthenticationPrincipal Jwt jwt) {
        return orderService.findAllFor(Caller.from(jwt));
    }

    @Operation(summary = "Dettaglio di un ordine")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ordine trovato"),
            @ApiResponse(responseCode = "403",
                    description = "L'ordine esiste ma appartiene a un altro cliente. "
                            + "403 e non 404 proprio perche' esiste.",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Ordine inesistente", content = @Content)
    })
    @GetMapping("/{id}")
    public OrderResponse findById(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.findByIdFor(id, Caller.from(jwt));
    }

    @Operation(summary = "Crea un ordine",
            description = "Richiede il ruolo CUSTOMER. L'intestatario non si dichiara: viene preso "
                    + "dal claim `sub` del token. L'ordine nasce in stato CREATED e viene poi "
                    + "portato a CONFIRMED o CANCELLED dalla saga, in modo asincrono: per "
                    + "conoscerne l'esito va riletto.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ordine creato in stato CREATED"),
            @ApiResponse(responseCode = "400", description = "Richiesta non valida", content = @Content),
            @ApiResponse(responseCode = "403", description = "Autenticato ma senza ruolo CUSTOMER", content = @Content)
    })
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
    @Operation(summary = "Elimina un ordine annullato",
            description = "Richiede il ruolo ADMIN. Si possono eliminare **solo** gli ordini in "
                    + "stato CANCELLED: uno confermato e' la traccia di una vendita avvenuta, e "
                    + "cancellarlo perderebbe quella storia.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Eliminato"),
            @ApiResponse(responseCode = "409", description = "L'ordine non e' annullato", content = @Content),
            @ApiResponse(responseCode = "404", description = "Ordine inesistente", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        orderService.deleteCancelled(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Forza lo stato di un ordine",
            description = "Richiede il ruolo ADMIN. E' un intervento manuale: il percorso normale "
                    + "e' che sia la saga a decidere lo stato. Un ordine annullato non puo' piu' "
                    + "cambiare stato.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stato aggiornato"),
            @ApiResponse(responseCode = "409", description = "L'ordine e' gia' annullato", content = @Content)
    })
    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable Long id, @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.updateStatus(id, request.getStatus());
    }
}
