package com.polyglotcommerce.catalog.repository;

import com.polyglotcommerce.catalog.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
