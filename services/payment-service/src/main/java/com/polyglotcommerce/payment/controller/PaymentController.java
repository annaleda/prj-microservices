package com.polyglotcommerce.payment.controller;

import com.polyglotcommerce.payment.dto.PaymentRequest;
import com.polyglotcommerce.payment.dto.PaymentResponse;
import com.polyglotcommerce.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Pagamenti",
        description = "Il percorso normale NON passa da qui: il pagamento nasce dall'evento "
                + "`payment.requested` pubblicato dalla saga. Questi endpoint servono a "
                + "consultazione e prove manuali.")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Registra un pagamento",
            description = "L'esito (COMPLETED / FAILED) e' deciso da una regola **simulata**: non "
                    + "esiste un gateway di pagamento reale, e gli importi oltre una soglia "
                    + "vengono rifiutati apposta, cosi' resta provabile anche il percorso di "
                    + "fallimento e la relativa compensazione della saga.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pagamento registrato (COMPLETED o FAILED)"),
            @ApiResponse(responseCode = "400", description = "Richiesta non valida", content = @Content),
            @ApiResponse(responseCode = "403", description = "Ruolo non sufficiente", content = @Content)
    })
    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        PaymentResponse created = paymentService.create(request);
        URI location = uriBuilder.path("/api/payments/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Dettaglio di un pagamento")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagamento trovato"),
            @ApiResponse(responseCode = "404", description = "Pagamento inesistente", content = @Content)
    })
    @GetMapping("/{id}")
    public PaymentResponse findById(@PathVariable Long id) {
        return paymentService.findById(id);
    }
}
