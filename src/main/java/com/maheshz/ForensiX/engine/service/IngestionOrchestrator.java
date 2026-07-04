package com.maheshz.ForensiX.engine.service;

import com.maheshz.ForensiX.engine.domain.JobStatus;
import com.maheshz.ForensiX.engine.domain.KnowledgeDocument;
import com.maheshz.ForensiX.engine.repository.DocumentRepository;
import com.maheshz.ForensiX.engine.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.UUID;

/**
 * Enterprise Saga Choreographer for Evidence Ingestion.
 * <p>
 * This orchestrator acts as the "Traffic Controller" for the entire RAG ingestion pipeline.
 * It coordinates three highly distinct architectural tiers: Relational State (PostgreSQL),
 * Binary Object Storage (S3/MinIO), and the Asynchronous Compute Pool.
 * <p>
 * ARCHITECTURAL DESIGN (The Saga Pattern):
 * Because this workflow spans external network systems, we cannot rely on standard ACID
 * database transactions. Instead, this service implements a localized Saga, utilizing
 * micro-transactions and manual compensating rollbacks to guarantee distributed data consistency.
 */
@Service
public class IngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private final AsyncIngestionWorker asyncIngestionWorker;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;

    /**
     * Constructor injection ensures core orchestration dependencies are immutable and thread-safe.
     */
    public IngestionOrchestrator(AsyncIngestionWorker asyncIngestionWorker,
                                 DocumentRepository documentRepository,
                                 StorageService storageService) {
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
    }

    /**
     * Safely initiates the multi-step file ingestion pipeline.
     * <p>
     * 🚨 ARCHITECTURAL CRITICAL: ABSENCE OF @Transactional 🚨
     * Notice this method does NOT have a @Transactional annotation. This is intentional.
     * If we opened a database transaction at the start of this method, the Tomcat thread would
     * hold a lock on a HikariCP database connection for the ENTIRE duration of the S3 upload.
     * If 50 investigators upload 500MB disk images simultaneously, the S3 network I/O would
     * take minutes, instantly exhausting the connection pool and crashing the entire application.
     *
     * @param file The multi-part binary payload streamed from the API controller.
     * @param tenantId The validated boundary ID for the active investigative case.
     * @param folderId The target directory UUID where the evidence will reside.
     * @return The unique Job ID (Correlation ID) for the frontend to track SSE progress.
     */
    public String initiateFileUpload(MultipartFile file, String tenantId, String folderId) {

        // -----------------------------------------------------------
        // 1. SECURITY: Path Traversal Prevention
        // -----------------------------------------------------------
        // A malicious user could send a payload with a filename like "../../../etc/passwd".
        // Paths.get(...).getFileName() mathematically strips out any directory traversal
        // characters, reducing the string strictly to the base filename.
        String rawFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown_file";
        String sanitizedFilename = Paths.get(rawFilename).getFileName().toString();

        // -----------------------------------------------------------
        // 2. IDEMPOTENCY: Deduplication Check
        // -----------------------------------------------------------
        if (documentRepository.existsByFileNameAndFolderIdAndTenantId(sanitizedFilename, folderId, tenantId)) {
            log.warn("Upload rejected: Document '{}' already exists in case '{}'.", sanitizedFilename, tenantId);
            throw new IllegalArgumentException("File '" + sanitizedFilename + "' already exists in this case.");
        }

        String jobId = UUID.randomUUID().toString();

        // -----------------------------------------------------------
        // 3. RELATIONAL MICRO-TRANSACTION
        // -----------------------------------------------------------
        KnowledgeDocument tracker = new KnowledgeDocument();
        tracker.setJobId(jobId);
        tracker.setTenantId(tenantId);
        tracker.setFolderId(folderId);
        tracker.setFileName(sanitizedFilename);
        tracker.setStatus(JobStatus.PENDING);

        // We use saveAndFlush() to execute an immediate micro-transaction.
        // Spring opens a DB connection, inserts the row, commits, and instantly returns the
        // connection to the pool BEFORE we start the heavy network I/O.
        KnowledgeDocument savedTracker = documentRepository.saveAndFlush(tracker);

        try {
            // -----------------------------------------------------------
            // 4. HEAVY NETWORK I/O (Database is free!)
            // -----------------------------------------------------------
            // This streams the bytes over TCP to AWS S3. It might take 1 second, or it might
            // take 5 minutes. Because we aren't in a transaction, the database is completely unaffected.
            String objectKey = storageService.uploadFile(file, tenantId, savedTracker.getId());

            // -----------------------------------------------------------
            // 5. ASYNC HANDOFF
            // -----------------------------------------------------------
            log.info("Dispatched distributed async ingestion job: {} for cloud file: {}", jobId, objectKey);
            asyncIngestionWorker.processFileAsync(objectKey, savedTracker);

        } catch (Exception e) {
            // -----------------------------------------------------------
            // 6. COMPENSATING TRANSACTION (Manual Rollback)
            // -----------------------------------------------------------
            // Because we don't have @Transactional, if the S3 upload drops due to a network
            // timeout, the database won't automatically roll back. We must manually execute
            // a compensating delete to purge the "Zombie" tracker record, ensuring the user
            // can safely retry the upload.
            log.error("S3 upload failed. Rolling back database tracker for job: {}", jobId);
            documentRepository.delete(savedTracker);
            throw new RuntimeException("Failed to initiate file upload pipeline.", e);
        }

        return jobId; // Return the claim ticket to the frontend instantly
    }
}