package com.maheshz.openrag.engine.service.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;

public interface StorageService {
    /** Uploads a file to cloud storage and returns the secure object key/URI */
    String uploadFile(MultipartFile file, String tenantId, String documentId);

    /** Downloads a file from cloud storage as a stream for the AI to process */
    InputStream downloadFile(String objectKey);

    /** Deletes the file from the cloud */
    void deleteFile(String objectKey);
}
