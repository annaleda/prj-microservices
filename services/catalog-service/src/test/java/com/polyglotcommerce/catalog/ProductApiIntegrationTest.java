package com.polyglotcommerce.catalog;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.model.Category;
import com.polyglotcommerce.catalog.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    static Long categoryId;

    @BeforeAll
    static void init() {
        // categoryId is populated lazily in the first test via the injected repository
    }

    private Long ensureCategory() {
        if (categoryId == null) {
            // "Electronics" viene creata da data.sql all'avvio dell'app: la riusiamo
            // invece di crearne una nuova per non violare il vincolo di unicita' sul nome.
            Category category = categoryRepository.findAll().stream()
                    .filter(c -> "Electronics".equals(c.getName()))
                    .findFirst()
                    .orElseGet(() -> categoryRepository.save(
                            Category.builder().name("Electronics").description("Test category").build()));
            categoryId = category.getId();
        }
        return categoryId;
    }

    @Test
    void createFindUpdateDeleteProduct() {
        Long catId = ensureCategory();

        ProductRequest createRequest = new ProductRequest("Wireless Mouse", "Ergonomic mouse",
                new BigDecimal("29.90"), "SKU-001", catId);

        ResponseEntity<ProductResponse> createResponse =
                restTemplate.postForEntity("/api/products", createRequest, ProductResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long productId = createResponse.getBody().getId();
        assertThat(productId).isNotNull();

        ResponseEntity<ProductResponse> getResponse =
                restTemplate.getForEntity("/api/products/" + productId, ProductResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getSku()).isEqualTo("SKU-001");

        ResponseEntity<ProductResponse[]> listResponse =
                restTemplate.getForEntity("/api/products", ProductResponse[].class);
        assertThat(listResponse.getBody()).extracting(ProductResponse::getId).contains(productId);

        ProductRequest updateRequest = new ProductRequest("Wireless Mouse", "Ergonomic mouse",
                new BigDecimal("24.90"), "SKU-001", catId);
        restTemplate.put("/api/products/" + productId, updateRequest);

        ResponseEntity<ProductResponse> updatedResponse =
                restTemplate.getForEntity("/api/products/" + productId, ProductResponse.class);
        assertThat(updatedResponse.getBody().getPrice()).isEqualByComparingTo("24.90");

        restTemplate.delete("/api/products/" + productId);

        ResponseEntity<ProductResponse> deletedResponse =
                restTemplate.getForEntity("/api/products/" + productId, ProductResponse.class);
        assertThat(deletedResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listCategories() {
        ensureCategory();

        ResponseEntity<Object[]> response = restTemplate.getForEntity("/api/categories", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }
}
