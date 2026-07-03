package com.maheshz.openrag.engine.service.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;

@Service
public class S3StorageServiceImpl implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageServiceImpl.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3StorageServiceImpl(S3Client s3Client, @Value("${cloud.aws.s3.bucket-name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * SENIOR FIX: Self-Healing Infrastructure.
     * This method runs automatically the moment the application starts.
     * It ensures the S3 bucket exists, surviving any Docker volume wipes.
     */
    @PostConstruct
    public void initializeBucket() {
        try {
            log.info("Verifying cloud storage bucket '{}'...", bucketName);
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            log.info("Cloud storage bucket '{}' is ready.", bucketName);
        } catch (NoSuchBucketException e) {
            log.warn("Bucket '{}' missing (likely due to fresh Docker environment). Creating...", bucketName);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            log.info("Cloud storage bucket '{}' successfully provisioned.", bucketName);
        } catch (Exception e) {
            log.error("Fatal error verifying S3 storage bucket. Uploads will fail.", e);
        }
    }

    @Override
    public String uploadFile(MultipartFile file, String tenantId, String documentId) {
        // Create a unique, collision-proof path in the cloud bucket
        String objectKey = tenantId + "/" + documentId + "/" + file.getOriginalFilename();

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .build();

            // Stream the file directly to MinIO/S3 to avoid blowing up the local server's RAM
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("Successfully uploaded evidence to S3 Cloud: {}", objectKey);

            return objectKey;
        } catch (Exception e) {
            log.error("Cloud storage upload failed for file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to upload evidence to S3.", e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to stream file from S3: {}", objectKey, e);
            throw new RuntimeException("Failed to download file from S3.", e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.debug("Successfully purged temporary file {} from S3", objectKey);
        } catch (Exception e) {
            log.warn("Failed to delete file from S3 (might already be deleted): {}", objectKey, e);
        }
    }
}