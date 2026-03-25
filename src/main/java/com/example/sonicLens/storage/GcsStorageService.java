package com.example.sonicLens.storage;

import com.example.sonicLens.config.GcsConfig;
import com.google.cloud.storage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class GcsStorageService {

    private final GcsConfig config;
    private final Storage storage = StorageOptions.getDefaultInstance().getService();

    /**
     * Uploads bytes to GCS and returns the object name (e.g. "audio/uuid_song.wav").
     */
    public String upload(byte[] bytes, String objectName, String contentType) {
        BlobId blobId = BlobId.of(config.getBucketName(), objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();
        storage.create(blobInfo, bytes);
        return objectName;
    }

    /**
     * Downloads an object from GCS and returns it as an InputStream.
     */
    public InputStream download(String objectName) {
        Blob blob = storage.get(BlobId.of(config.getBucketName(), objectName));
        if (blob == null) {
            throw new IllegalArgumentException("GCS object not found: " + objectName);
        }
        return new ByteArrayInputStream(blob.getContent());
    }

    /**
     * Returns the public GCS URL for an object.
     * Requires the bucket to have uniform public access or the object to be public.
     */
    public String publicUrl(String objectName) {
        return "https://storage.googleapis.com/" + config.getBucketName() + "/" + objectName;
    }
}
