package com.polyglotcommerce.catalog;

import com.polyglotcommerce.catalog.dto.ProductRequest;
import com.polyglotcommerce.catalog.dto.ProductResponse;
import com.polyglotcommerce.catalog.model.Category;
import com.polyglotcommerce.catalog.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

import static com.polyglotcommerce.catalog.TestJwtSupport.ADMIN;
import static com.polyglotcommerce.catalog.TestJwtSupport.ANONYMOUS;
import static com.polyglotcommerce.catalog.TestJwtSupport.CUSTOMER;
import static com.polyglotcommerce.catalog.TestJwtSupport.as;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
class ProductApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    // Broker vero e non mock: creare un prodotto pubblica product.created, e
    // il valore del test sta proprio nel verificare cio' che finisce sul
    // topic, che e' il contratto con l'Inventory Service.
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    // Object storage vero, non un finto: cio' che si vuole verificare e'
    // che il file caricato torni indietro identico passando davvero da S3.
    @Container
    static GenericContainer<?> minio = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2024-09-13T20-26-02Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static final String BUCKET = "product-images";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("storage.endpoint", ProductApiIntegrationTest::minioEndpoint);
        registry.add("storage.bucket", () -> BUCKET);
    }

    static String minioEndpoint() {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    @BeforeAll
    static void createBucket() {
        // Nello stack reale il bucket lo crea `minio-init` (docker-compose):
        // il servizio non se lo crea da solo, perche' creare bucket e' un
        // permesso che in un object storage vero non si concede a
        // un'applicazione.
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(minioEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    static Long categoryId;

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
                new BigDecimal("29.90"), "SKU-001", "https://example.com/mouse.jpg", catId);

        ResponseEntity<ProductResponse> createResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST, as(ADMIN, createRequest), ProductResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long productId = createResponse.getBody().getId();
        assertThat(productId).isNotNull();

        ResponseEntity<ProductResponse> getResponse =
                restTemplate.getForEntity("/api/products/" + productId, ProductResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getSku()).isEqualTo("SKU-001");
        assertThat(getResponse.getBody().getImageUrl()).isEqualTo("https://example.com/mouse.jpg");

        ResponseEntity<ProductResponse[]> listResponse =
                restTemplate.getForEntity("/api/products", ProductResponse[].class);
        assertThat(listResponse.getBody()).extracting(ProductResponse::getId).contains(productId);

        ProductRequest updateRequest = new ProductRequest("Wireless Mouse", "Ergonomic mouse",
                new BigDecimal("24.90"), "SKU-001", "https://example.com/mouse.jpg", catId);
        restTemplate.exchange("/api/products/" + productId, HttpMethod.PUT,
                as(ADMIN, updateRequest), ProductResponse.class);

        ResponseEntity<ProductResponse> updatedResponse =
                restTemplate.getForEntity("/api/products/" + productId, ProductResponse.class);
        assertThat(updatedResponse.getBody().getPrice()).isEqualByComparingTo("24.90");

        restTemplate.exchange("/api/products/" + productId, HttpMethod.DELETE,
                as(ADMIN), Void.class);

        ResponseEntity<ProductResponse> deletedResponse =
                restTemplate.getForEntity("/api/products/" + productId, ProductResponse.class);
        assertThat(deletedResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creatingAProductAnnouncesItOnKafka() {
        // Senza questo evento l'Inventory Service non sa che il prodotto
        // esiste, e ogni ordine che lo contiene viene rifiutato e annullato
        // dalla saga: e' il bug del 5 settembre 2026.
        ProductRequest request = new ProductRequest("Docking Station", "USB-C dock",
                new BigDecimal("119.00"), "SKU-DOCK-01", null, ensureCategory());

        ResponseEntity<ProductResponse> created = restTemplate.exchange(
                "/api/products", HttpMethod.POST, as(ADMIN, request), ProductResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long productId = created.getBody().getId();
        JsonNode envelope = KafkaTestSupport.awaitProductEvent(
                kafka.getBootstrapServers(), "product.created", productId, Duration.ofSeconds(20));

        assertThat(envelope.path("eventType").asText()).isEqualTo("PRODUCT_CREATED");
        assertThat(envelope.path("source").asText()).isEqualTo("catalog-service");
        assertThat(envelope.path("data").path("sku").asText()).isEqualTo("SKU-DOCK-01");
        assertThat(envelope.path("data").path("name").asText()).isEqualTo("Docking Station");
    }

    /** PNG minimo valido: bastano dei byte riconoscibili, non serve un'immagine vera. */
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8
    };

    private HttpEntity<MultiValueMap<String, Object>> imageUpload(String token, byte[] content, String filename,
                                                                  MediaType type) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(type);
        Resource part = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", new HttpEntity<>(part, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private Long createProductAs(String token, String sku) {
        ProductRequest request = new ProductRequest("Prodotto con foto", "Con immagine caricata",
                new BigDecimal("49.00"), sku, null, ensureCategory());
        return restTemplate.exchange("/api/products", HttpMethod.POST, as(token, request), ProductResponse.class)
                .getBody()
                .getId();
    }

    @Test
    void anUploadedImageIsStoredAndServedBack() {
        // Il file finisce sull'object storage, nel database resta solo il
        // riferimento: e' la ragione per cui esiste MinIO nel progetto.
        Long productId = createProductAs(ADMIN, "SKU-IMG-01");

        ResponseEntity<ProductResponse> upload = restTemplate.exchange(
                "/api/products/" + productId + "/image", HttpMethod.POST,
                imageUpload(ADMIN, PNG, "foto.png", MediaType.IMAGE_PNG), ProductResponse.class);

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Un percorso relativo, non l'indirizzo di MinIO: quell'URL cambia
        // fra locale e cluster e resterebbe congelato nella riga.
        assertThat(upload.getBody().getImageUrl()).isEqualTo("/api/products/" + productId + "/image");

        ResponseEntity<byte[]> served = restTemplate.exchange(
                "/api/products/" + productId + "/image", HttpMethod.GET, as(ANONYMOUS), byte[].class);

        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        // Identici byte per byte: se lo storage li alterasse, l'immagine
        // arriverebbe rotta al browser senza che nulla segnali un errore.
        assertThat(served.getBody()).isEqualTo(PNG);
    }

    @Test
    void aProductWithoutAnUploadedImageReturnsNotFound() {
        // I prodotti con imageUrl esterno (loremflickr) non hanno nulla
        // nell'object storage: la richiesta non deve dare 500.
        Long productId = createProductAs(ADMIN, "SKU-IMG-02");

        assertThat(restTemplate.exchange("/api/products/" + productId + "/image",
                HttpMethod.GET, as(ANONYMOUS), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFileThatIsNotAnImageIsRejected() {
        Long productId = createProductAs(ADMIN, "SKU-IMG-03");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + productId + "/image", HttpMethod.POST,
                imageUpload(ADMIN, "non sono un'immagine".getBytes(), "malware.exe",
                        MediaType.APPLICATION_OCTET_STREAM),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadingAnImageRequiresTheAdminRole() {
        Long productId = createProductAs(ADMIN, "SKU-IMG-04");

        assertThat(restTemplate.exchange("/api/products/" + productId + "/image", HttpMethod.POST,
                imageUpload(CUSTOMER, PNG, "foto.png", MediaType.IMAGE_PNG), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(restTemplate.exchange("/api/products/" + productId + "/image", HttpMethod.POST,
                imageUpload(ANONYMOUS, PNG, "foto.png", MediaType.IMAGE_PNG), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listCategories() {
        ensureCategory();

        ResponseEntity<Object[]> response = restTemplate.getForEntity("/api/categories", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void catalogIsReadableWithoutLogin() {
        // La vetrina resta pubblica: senza questo, il sito non mostrerebbe
        // nulla a chi non ha ancora un account.
        assertThat(restTemplate.exchange("/api/products", HttpMethod.GET, as(ANONYMOUS), ProductResponse[].class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.exchange("/api/categories", HttpMethod.GET, as(ANONYMOUS), Object[].class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void writingToTheCatalogRequiresAuthentication() {
        ProductRequest request = new ProductRequest("Hacked Product", "No token",
                new BigDecimal("1.00"), "SKU-ANON", null, ensureCategory());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, as(ANONYMOUS, request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void writingToTheCatalogRequiresTheAdminRole() {
        ProductRequest request = new ProductRequest("Customer Product", "Wrong role",
                new BigDecimal("1.00"), "SKU-CUST", null, ensureCategory());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, as(CUSTOMER, request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
