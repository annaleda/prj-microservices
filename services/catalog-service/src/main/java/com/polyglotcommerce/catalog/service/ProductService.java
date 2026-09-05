package com.polyglotcommerce.catalog.service;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.exception.ResourceNotFoundException;
import com.polyglotcommerce.catalog.model.Category;
import com.polyglotcommerce.catalog.model.Product;
import com.polyglotcommerce.catalog.repository.CategoryRepository;
import com.polyglotcommerce.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
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
        return ProductResponse.fromEntity(productRepository.save(product));
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

    private Product getProductOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    private Category resolveCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
    }
}
