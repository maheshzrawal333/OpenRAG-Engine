package com.maheshz.ForensiX.engine.service;

import com.maheshz.ForensiX.engine.domain.KnowledgeDocument;
import com.maheshz.ForensiX.engine.repository.DocumentRepository;
import com.maheshz.ForensiX.engine.repository.VectorCleanupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Enterprise Business Logic Facade for Evidence Lifecycle Management.
 * <p>
 * This service orchestrates the CRUD operations for forensic evidence metadata.
 * It acts as the critical bridge between the relational database (PostgreSQL) and
 * the high-dimensional vector store (pgvector), ensuring that operations remain
 * strictly isolated within multi-tenant boundaries.
 * <p>
 * ARCHITECTURAL DESIGN:
 * Every method in this service explicitly requires a {@code tenantId}. We never rely
 * on implicit context here; forcing the ID through the method signature mathematically
 * guarantees that business operations cannot accidentally leak across investigative cases.
 */
@Service
public class EvidenceManagementService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceManagementService.class);

    private final DocumentRepository documentRepository;
    private final VectorCleanupRepository vectorCleanupRepository;

    /**
     * Constructor injection ensures core repository dependencies are immutable and thread-safe.
     */
    public EvidenceManagementService(DocumentRepository documentRepository, VectorCleanupRepository vectorCleanupRepository) {
        this.documentRepository = documentRepository;
        this.vectorCleanupRepository = vectorCleanupRepository;
    }

    /**
     * Retrieves all evidence metadata scoped strictly to a specific folder and case.
     * <p>
     * PERFORMANCE NOTE:
     * Annotated with {@code @Transactional(readOnly = true)}. This optimizes the Hibernate
     * session by disabling dirty-checking and flushing, which significantly reduces JVM
     * memory overhead and database lock contention during heavy read operations.
     *
     * @param folderId The target directory UUID.
     * @param tenantId The validated boundary ID for the active investigative case.
     * @return A list of KnowledgeDocument entities belonging to the requested scope.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocument> getEvidenceForCase(String folderId, String tenantId) {
        return documentRepository.findByFolderIdAndTenantId(folderId, tenantId);
    }

    /**
     * Executes a Distributed Cascade Delete to permanently purge evidence.
     * <p>
     * ARCHITECTURAL CRITICAL: In a Retrieval-Augmented Generation (RAG) system, deleting
     * the relational metadata is not enough. We MUST obliterate the high-dimensional vectors
     * first. If we fail to do this, we leave behind "Ghost Vectors"—where the LLM cites
     * evidence that legally no longer exists in the system.
     * <p>
     * Note on S3 Storage: This method deliberately handles only the Database/Vector tiers.
     * Depending on forensic compliance requirements, the physical binary in S3 may need to
     * be retained in a "Cold Vault" rather than hard-deleted.
     *
     * @param documentId The UUID of the original uploaded file.
     * @param tenantId   The validated boundary ID (prevents IDOR attacks).
     */
    @Transactional
    public void deleteEvidence(String documentId, String tenantId) {
        // 1. Security Check: Ensure the document exists AND belongs to the requesting tenant.
        KnowledgeDocument doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found or access denied."));

        // 2. Distributed Vector Cleanup (SENIOR MANEUVER)
        // We wipe the AI memory via a direct JDBC push-down operation before deleting the JPA entity.
        // This ensures that if the transaction rolls back, we don't end up with orphaned vectors.
        int deletedChunks = vectorCleanupRepository.wipeVectorsByDocumentId(documentId, tenantId);
        log.info("Wiped {} vector chunks from AI memory for document {}", deletedChunks, documentId);

        // 3. Relational Cleanup
        // Finally, drop the relational tracker.
        documentRepository.delete(doc);
    }

    /**
     * Safely updates the human-readable display name of an evidence file.
     * <p>
     * SECURITY BOUNDARY:
     * Similar to the delete method, we explicitly validate ownership via {@code tenantId}
     * before applying any updates. We rely on Hibernate's dirty-checking mechanism inside
     * the transaction to automatically flush the UPDATE statement to the database.
     *
     * @param documentId The primary key of the document.
     * @param tenantId   The validated boundary ID (prevents cross-tenant naming modifications).
     * @param newName    The sanitized new filename.
     * @return The updated and persisted KnowledgeDocument.
     */
    @Transactional
    public KnowledgeDocument renameEvidence(String documentId, String tenantId, String newName) {
        KnowledgeDocument doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found or access denied."));

        doc.setFileName(newName);

        // Return the saved entity to update the frontend state
        return documentRepository.save(doc);
    }
}