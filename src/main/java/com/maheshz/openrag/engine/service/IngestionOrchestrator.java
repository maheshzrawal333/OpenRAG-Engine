package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.domain.JobStatus;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.UUID;

@Service
public class IngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private final AsyncIngestionWorker asyncIngestionWorker;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;

    public IngestionOrchestrator(AsyncIngestionWorker asyncIngestionWorker,
                                 DocumentRepository documentRepository,
                                 StorageService storageService) {
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
    }

    /**
     * SENIOR FIX: Removed @Transactional.
     * We cannot block a database connection while streaming 50MB files to S3/MinIO.
     */
    public String initiateFileUpload(MultipartFile file, String tenantId, String folderId) {

        String rawFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown_file";
        String sanitizedFilename = Paths.get(rawFilename).getFileName().toString();

        if (documentRepository.existsByFileNameAndFolderIdAndTenantId(sanitizedFilename, folderId, tenantId)) {
            log.warn("Upload rejected: Document '{}' already exists in case '{}'.", sanitizedFilename, tenantId);
            throw new IllegalArgumentException("File '" + sanitizedFilename + "' already exists in this case.");
        }

        String jobId = UUID.randomUUID().toString();

        // 1. Create the persistent tracker in the database
        KnowledgeDocument tracker = new KnowledgeDocument();
        tracker.setJobId(jobId);
        tracker.setTenantId(tenantId);
        tracker.setFolderId(folderId);
        tracker.setFileName(sanitizedFilename);
        tracker.setStatus(JobStatus.PENDING);

        // Micro-transaction: Opens a connection, saves, and releases the connection instantly.
        KnowledgeDocument savedTracker = documentRepository.saveAndFlush(tracker);

        try {
            // 2. Network I/O: Upload to Cloud Storage
            // This takes time, but no database connections are being held hostage!
            String objectKey = storageService.uploadFile(file, tenantId, savedTracker.getId());

            // 3. Dispatch the async ingestion job
            log.info("Dispatched distributed async ingestion job: {} for cloud file: {}", jobId, objectKey);
            asyncIngestionWorker.processFileAsync(objectKey, savedTracker);

        } catch (Exception e) {
            // Compensating Transaction: If S3 fails, delete the tracker so we don't have zombie records
            log.error("S3 upload failed. Rolling back database tracker for job: {}", jobId);
            documentRepository.delete(savedTracker);
            throw new RuntimeException("Failed to initiate file upload pipeline.", e);
        }

        return jobId;
    }
}