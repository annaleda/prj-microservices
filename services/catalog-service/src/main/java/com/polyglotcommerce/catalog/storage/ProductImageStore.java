package com.polyglotcommerce.catalog.storage;

import com.polyglotcommerce.catalog.config.ObjectStorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * Le immagini dei prodotti sull'object storage.
 *
 * I binari non stanno nel database del catalogo, che ne conserva solo il
 * riferimento: un file di qualche centinaio di kilobyte per riga
 * appesantirebbe ogni backup e ogni lettura della tabella, e le immagini
 * non hanno nulla di transazionale.
 *
 * La chiave e' derivata dall'id del prodotto, quindi ogni prodotto ha al
 * massimo un'immagine e ricaricarla sostituisce la precedente senza
 * lasciare file orfani.
 */
@Component
public class ProductImageStore {

    private static final Logger log = LoggerFactory.getLogger(ProductImageStore.class);

    private final S3Client s3;
    private final ObjectStorageConfig config;

    public ProductImageStore(S3Client s3, ObjectStorageConfig config) {
        this.s3 = s3;
        this.config = config;
    }

    public void put(Long productId, byte[] content, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(config.getBucket())
                        .key(key(productId))
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
        log.info("Stored image for product {} ({} bytes, {})", productId, content.length, contentType);
    }

    public Optional<StoredImage> find(Long productId) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(config.getBucket()).key(key(productId)).build());
            return Optional.of(new StoredImage(object.asByteArray(), object.response().contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * Rimuove l'immagine di un prodotto eliminato.
     *
     * Non solleva eccezioni: l'eliminazione del prodotto e' gia' avvenuta e
     * ha successo comunque. Un file rimasto indietro e' spazio sprecato,
     * non un errore che valga la pena mostrare a chi ha premuto "Elimina".
     */
    public void deleteQuietly(Long productId) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.getBucket())
                    .key(key(productId))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Unable to delete image of product {}: {}", productId, e.toString());
        }
    }

    private String key(Long productId) {
        return "products/" + productId;
    }
}
