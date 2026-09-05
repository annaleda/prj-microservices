package com.polyglotcommerce.catalog.storage;

/** Un'immagine letta dall'object storage: i byte e il loro tipo. */
public class StoredImage {

    private final byte[] content;
    private final String contentType;

    public StoredImage(byte[] content, String contentType) {
        this.content = content;
        this.contentType = contentType;
    }

    public byte[] getContent() {
        return content;
    }

    public String getContentType() {
        return contentType;
    }
}
