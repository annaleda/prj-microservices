package com.polyglotcommerce.catalog.controller;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.service.ProductService;
import com.polyglotcommerce.catalog.storage.StoredImage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Prodotti", description = "Catalogo prodotti. Lettura pubblica, scrittura riservata al ruolo ADMIN.")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "Elenca tutti i prodotti",
            description = "Pubblico: e' la vetrina del negozio, non richiede autenticazione.")
    @GetMapping
    public List<ProductResponse> findAll() {
        return productService.findAll();
    }

    @Operation(summary = "Dettaglio di un prodotto", description = "Pubblico.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prodotto trovato"),
            @ApiResponse(responseCode = "404", description = "Prodotto inesistente", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @GetMapping("/{id}")
    public ProductResponse findById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @Operation(summary = "Crea un prodotto",
            description = "Richiede il ruolo ADMIN. Alla creazione il servizio pubblica l'evento "
                    + "`product.created`, che fa aprire all'Inventory Service la riga di magazzino "
                    + "del prodotto (a zero pezzi).",
            security = @SecurityRequirement(name = "bearer-jwt"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Creato, con header Location"),
            @ApiResponse(responseCode = "400", description = "Dati non validi", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "401", description = "Token assente o non valido", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Autenticato ma senza ruolo ADMIN", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        ProductResponse created = productService.create(request);
        URI location = uriBuilder.path("/api/products/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Aggiorna un prodotto", description = "Richiede il ruolo ADMIN.",
            security = @SecurityRequirement(name = "bearer-jwt"))
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    /**
     * Carica l'immagine di un prodotto (riservato ad ADMIN dalla
     * configurazione di sicurezza, che protegge i POST su /api/products).
     *
     * Il file va sull'object storage; qui torna il prodotto aggiornato,
     * con {@code imageUrl} che punta all'endpoint di lettura qui sotto.
     */
    @Operation(summary = "Carica l'immagine di un prodotto",
            description = "Richiede il ruolo ADMIN. Il file va sull'object storage (MinIO); nel "
                    + "database resta solo il riferimento, e `imageUrl` diventa "
                    + "`/api/products/{id}/image`. Massimo 5 MB, solo tipi `image/*`.",
            security = @SecurityRequirement(name = "bearer-jwt"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Immagine caricata, prodotto aggiornato"),
            @ApiResponse(responseCode = "400", description = "File assente, troppo grande o non un'immagine", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "Prodotto inesistente", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductResponse uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return productService.uploadImage(id, file);
    }

    /**
     * Serve l'immagine caricata. Pubblica come il resto del catalogo: e'
     * la vetrina, e un'immagine che richiedesse un token non si potrebbe
     * mostrare a chi non ha ancora un account.
     */
    @Operation(summary = "Immagine di un prodotto",
            description = "Pubblica come il resto del catalogo. Risponde 404 se il prodotto non ha "
                    + "un'immagine caricata (per esempio se usa un indirizzo esterno).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "I byte dell'immagine"),
            @ApiResponse(responseCode = "404", description = "Nessuna immagine caricata", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        return productService.findImage(id)
                .map(image -> ResponseEntity.ok()
                        .contentType(mediaTypeOf(image))
                        // Le immagini cambiano di rado e l'URL e' stabile:
                        // senza questa intestazione il browser le riscarica
                        // ad ogni visita del catalogo.
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                        .body(image.getContent()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static MediaType mediaTypeOf(StoredImage image) {
        try {
            return MediaType.parseMediaType(image.getContentType());
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @Operation(summary = "Elimina un prodotto",
            description = "Richiede il ruolo ADMIN. Rimuove anche l'eventuale immagine caricata.",
            security = @SecurityRequirement(name = "bearer-jwt"))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
