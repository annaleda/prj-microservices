package com.polyglotcommerce.catalog.exception;

/** Il file caricato non e' un'immagine utilizzabile. */
public class InvalidImageException extends RuntimeException {

    public InvalidImageException(String message) {
        super(message);
    }
}
