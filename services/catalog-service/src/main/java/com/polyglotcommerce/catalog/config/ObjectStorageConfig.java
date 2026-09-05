package com.polyglotcommerce.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Client verso l'object storage delle immagini (MinIO in locale).
 *
 * Si parla S3 e non il dialetto proprietario di MinIO: cambiare storage
 * significa cambiare endpoint e credenziali, non riscrivere il codice.
 */
@Configuration
@ConfigurationProperties(prefix = "storage")
public class ObjectStorageConfig {

    /** Endpoint S3 come lo raggiunge <b>questo servizio</b> (non il browser). */
    private String endpoint = "http://localhost:9000";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private String bucket = "product-images";
    /** MinIO non ha regioni: ne serve una qualunque perche' l'SDK firmi la richiesta. */
    private String region = "us-east-1";

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // Indirizzamento per percorso (host/bucket/oggetto) invece che
                // per sottodominio (bucket.host/oggetto): MinIO non ha
                // wildcard DNS, e con lo stile virtual-host le richieste non
                // arriverebbero.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    public String getBucket() {
        return bucket;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
