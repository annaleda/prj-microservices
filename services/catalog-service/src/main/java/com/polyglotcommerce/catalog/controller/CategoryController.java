package com.polyglotcommerce.catalog.controller;

import com.polyglotcommerce.catalog.dto.CategoryResponse;
import com.polyglotcommerce.catalog.repository.CategoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@Tag(name = "Categorie", description = "Categorie del catalogo, in sola lettura.")
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Operation(summary = "Elenca le categorie", description = "Pubblico. Il catalogo non espone CRUD sulle categorie.")
    @GetMapping
    public List<CategoryResponse> findAll() {
        return categoryRepository.findAll().stream()
                .map(CategoryResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
