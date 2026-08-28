package com.polyglotcommerce.catalog.repository;

import com.polyglotcommerce.catalog.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
