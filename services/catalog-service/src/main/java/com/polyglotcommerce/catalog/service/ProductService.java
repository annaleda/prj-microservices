package com.polyglotcommerce.catalog.service;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.event.EventEnvelope;
import com.polyglotcommerce.catalog.event.EventTopics;
import com.polyglotcommerce.catalog.event.OutboundEvent;
import com.polyglotcommerce.catalog.event.payload.ProductCreatedPayload;
import com.polyglotcommerce.catalog.exception.InvalidImageException;
import com.polyglotcommerce.catalog.exception.ResourceNotFoundException;
import com.polyglotcommerce.catalog.model.Category;
import com.polyglotcommerce.catalog.model.Product;
import com.polyglotcommerce.catalog.repository.CategoryRepository;
import com.polyglotcommerce.catalog.repository.ProductRepository;
import com.polyglotcommerce.catalog.storage.ProductImageStore;
import com.polyglotcommerce.catalog.storage.StoredImage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * La mappatura verso {@link ProductResponse} avviene qui, dentro il confine
 * transazionale: con {@code spring.jpa.open-in-view=false} la sessione
 * Hibernate si chiude al termine del metodo, quindi l'accesso lazy a
 * {@code Category} non puo' avvenire piu' avanti nel controller.
 */
@Service
public class ProductService {

    /**
     * Percorso su cui il servizio stesso serve l'immagine caricata.
     *
     * Nel database finisce questo, non l'indirizzo di MinIO: un URL
     * assoluto dell'object storage cambia fra locale e cluster e
     * resterebbe congelato in ogni riga scritta prima del cambiamento.
     * Un percorso relativo funziona sia col proxy di sviluppo sia dietro
     * l'API Gateway, come gia' fanno tutte le chiamate dei frontend.
     */
    private static final String IMAGE_PATH = "/api/products/%d/image";

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductImageStore imageStore;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          ApplicationEventPublisher eventPublisher,
                          ProductImageStore imageStore) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
        this.imageStore = imageStore;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.fromEntity(getProductOrThrow(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Category category = resolveCategory(request.getCategoryId());
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .sku(request.getSku())
                .imageUrl(request.getImageUrl())
                .category(category)
                .build();

        Product saved = productRepository.save(product);
        publishProductCreated(saved);

        return ProductResponse.fromEntity(saved);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getProductOrThrow(id);
        Category category = resolveCategory(request.getCategoryId());

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setSku(request.getSku());
        product.setImageUrl(request.getImageUrl());
        product.setCategory(category);

        return ProductResponse.fromEntity(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        Product product = getProductOrThrow(id);
        productRepository.delete(product);
        imageStore.deleteQuietly(id);
    }

    /**
     * Carica l'immagine di un prodotto e ne aggiorna il riferimento.
     *
     * Due sistemi in un'operazione sola: il file va sull'object storage, il
     * riferimento nel database. Si scrive prima il file, cosi' se il
     * caricamento fallisce il prodotto resta con l'immagine di prima invece
     * di puntare a un file che non esiste. Il caso opposto -- file scritto
     * e transazione annullata -- lascia un oggetto orfano nel bucket, che e'
     * spazio sprecato e non un dato sbagliato.
     */
    @Transactional
    public ProductResponse uploadImage(Long id, MultipartFile file) {
        Product product = getProductOrThrow(id);

        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("Nessun file caricato");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new InvalidImageException("Immagine troppo grande: il limite e' 5 MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidImageException("Il file non e' un'immagine (tipo: " + contentType + ")");
        }

        try {
            imageStore.put(id, file.getBytes(), contentType);
        } catch (IOException e) {
            throw new InvalidImageException("Impossibile leggere il file caricato: " + e.getMessage());
        }

        product.setImageUrl(String.format(IMAGE_PATH, id));
        return ProductResponse.fromEntity(productRepository.save(product));
    }

    /**
     * L'immagine caricata, se c'e'. Sola lettura e senza transazione di
     * scrittura: e' un file, non un dato del dominio.
     */
    public Optional<StoredImage> findImage(Long id) {
        return imageStore.find(id);
    }

    /**
     * Annuncia il prodotto agli altri servizi.
     *
     * Serve all'Inventory Service, che crea la riga di magazzino
     * corrispondente: senza questo evento le scorte di un prodotto nuovo
     * andrebbero create a mano da qualche parte, e finche' non lo si fa
     * ogni ordine su quel prodotto viene rifiutato e annullato dalla saga.
     * E' esattamente il bug del 5 settembre 2026, in cui i prodotti
     * dimostrativi del catalogo non avevano corrispondenza a magazzino.
     *
     * Nota: i prodotti inseriti direttamente nel database (data.sql) non
     * passano di qui e quindi non generano l'evento; per quelli resta il
     * seed dell'Inventory Service.
     */
    private void publishProductCreated(Product product) {
        ProductCreatedPayload payload = ProductCreatedPayload.builder()
                .productId(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .build();

        EventEnvelope<ProductCreatedPayload> envelope =
                EventEnvelope.of("PRODUCT_CREATED", UUID.randomUUID().toString(), payload);

        eventPublisher.publishEvent(
                new OutboundEvent(EventTopics.PRODUCT_CREATED, String.valueOf(product.getId()), envelope));
    }

    private Product getProductOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    private Category resolveCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
    }
}
