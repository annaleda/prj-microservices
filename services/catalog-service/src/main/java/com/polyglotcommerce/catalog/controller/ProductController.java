package com.polyglotcommerce.catalog.controller;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.service.ProductService;
import com.polyglotcommerce.catalog.storage.StoredImage;
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
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> findAll() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    public ProductResponse findById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        ProductResponse created = productService.create(request);
        URI location = uriBuilder.path("/api/products/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

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
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductResponse uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return productService.uploadImage(id, file);
    }

    /**
     * Serve l'immagine caricata. Pubblica come il resto del catalogo: e'
     * la vetrina, e un'immagine che richiedesse un token non si potrebbe
     * mostrare a chi non ha ancora un account.
     */
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
