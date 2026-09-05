package com.polyglotcommerce.catalog.service;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.event.EventEnvelope;
import com.polyglotcommerce.catalog.event.EventTopics;
import com.polyglotcommerce.catalog.event.OutboundEvent;
import com.polyglotcommerce.catalog.event.payload.ProductCreatedPayload;
import com.polyglotcommerce.catalog.exception.ResourceNotFoundException;
import com.polyglotcommerce.catalog.model.Category;
import com.polyglotcommerce.catalog.model.Product;
import com.polyglotcommerce.catalog.repository.CategoryRepository;
import com.polyglotcommerce.catalog.repository.ProductRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
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
