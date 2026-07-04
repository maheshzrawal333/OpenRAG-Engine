package com.maheshz.ForensiX.engine.service;

import com.maheshz.ForensiX.engine.domain.Folder;
import com.maheshz.ForensiX.engine.repository.DocumentRepository;
import com.maheshz.ForensiX.engine.repository.FolderRepository;
import com.maheshz.ForensiX.engine.repository.VectorCleanupRepository;
import com.maheshz.ForensiX.engine.security.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Enterprise Business Logic Facade for Hierarchical Case Organization.
 * <p>
 * This service orchestrates the CRUD operations for the virtual directory structure.
 * In the ForensiX RAG architecture, these folders serve a dual purpose: they provide
 * visual organization for the UI, and they act as strict boundary boxes for Vector Similarity Searches.
 * <p>
 * ARCHITECTURAL INVARIANTS:
 * 1. Implicit Multi-Tenancy: Every public method extracts the `tenantId` directly from the
 * active ThreadLocal context. This guarantees that business logic cannot accidentally cross
 * investigative boundaries, even if the frontend sends a malicious payload.
 * 2. Adjacency List Traversal: Folder hierarchies are managed via `parentFolderId`.
 */
@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final VectorCleanupRepository vectorCleanupRepository;

    /**
     * Constructor injection ensures core repository dependencies are immutable and thread-safe.
     */
    public FolderService(FolderRepository folderRepository, DocumentRepository documentRepository, VectorCleanupRepository vectorCleanupRepository) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.vectorCleanupRepository = vectorCleanupRepository;
    }

    /**
     * Provisions a new logical directory within the investigative case.
     * <p>
     * SECURITY BOUNDARY (IDOR Prevention):
     * If a client attempts to nest this new folder under an existing `parentFolderId`,
     * we do not blindly trust that ID. We explicitly query the database to verify that
     * the requested parent folder actually belongs to the active tenant. This prevents
     * an attacker from attaching their folders to someone else's case.
     *
     * @param name The human-readable name of the directory.
     * @param parentFolderId The UUID of the parent directory, or "root"/null for a top-level folder.
     * @return The fully hydrated and persisted Folder entity.
     */
    @Transactional
    public Folder createFolder(String name, String parentFolderId) {
        String tenantId = getValidatedTenantId();

        // Validate Parent Ownership
        if (parentFolderId != null && !parentFolderId.equals("root")) {
            boolean ownsParent = folderRepository.findById(parentFolderId)
                    .map(f -> f.getTenantId().equals(tenantId))
                    .orElse(false);
            if (!ownsParent) {
                throw new IllegalArgumentException("Invalid Parent Folder. Access Denied.");
            }
        }

        Folder folder = new Folder();
        folder.setId(UUID.randomUUID().toString());
        folder.setTenantId(tenantId);
        folder.setName(name);
        folder.setParentFolderId(parentFolderId);

        return folderRepository.save(folder);
    }

    /**
     * Retrieves the immediate child directories for a given scope.
     * <p>
     * PERFORMANCE NOTE:
     * This explicitly executes a lazy, single-level fetch (depth = 1) rather than pulling
     * the entire case tree into JVM memory. This prevents recursive N+1 query blowouts on massive cases.
     *
     * @param parentFolderId The UUID of the parent, or null/"root" for top-level directories.
     * @return A list of strictly isolated, immediate sub-folders.
     */
    @Transactional(readOnly = true)
    public List<Folder> getFolders(String parentFolderId) {
        String tenantId = getValidatedTenantId();
        return folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);
    }

    /**
     * Updates the human-readable display name of a directory.
     *
     * @param folderId The target directory UUID.
     * @param newName The sanitized new name.
     * @return The updated Folder entity.
     * @throws IllegalArgumentException if the folder does not exist or belongs to another tenant.
     */
    @Transactional
    public Folder renameFolder(String folderId, String newName) {
        String tenantId = getValidatedTenantId();

        // Enforce implicit tenant perimeter check during the fetch
        Folder folder = folderRepository.findById(folderId)
                .filter(f -> f.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found or access denied."));

        folder.setName(newName);
        return folderRepository.save(folder); // Relies on JPA dirty-checking for the flush
    }

    /**
     * Initiates a highly destructive, recursive cascade delete.
     * <p>
     * ARCHITECTURAL CRITICAL:
     * This method acts as the entry point for obliterating an entire branch of an investigation.
     * Protected by `@Transactional`, meaning if any part of the recursive deletion fails
     * (e.g., database timeout or vector connection drop), the ENTIRE tree rolls back,
     * preventing orphaned directories or "Ghost Vectors".
     *
     * @param folderId The root of the directory branch to be destroyed.
     */
    @Transactional
    public void deleteFolder(String folderId) {
        String tenantId = getValidatedTenantId();

        // 1. Verify ownership of the root deletion target
        Folder targetFolder = folderRepository.findById(folderId)
                .filter(f -> f.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found or access denied."));

        // 2. Execute recursive destruction of children
        deleteFolderContentsRecursively(folderId, tenantId);

        // 3. Drop the parent node
        folderRepository.delete(targetFolder);
    }

    /**
     * Internal recursive worker for deep-tree obliteration.
     * <p>
     * DISTRIBUTED DATA CONSISTENCY:
     * This method doesn't just delete relational rows; it orchestrates cleanup across the
     * relational DB and the AI Vector Store. We MUST wipe the vectors via the abstracted
     * `vectorCleanupRepository` before deleting the relational file tracker, ensuring the
     * LLM cannot hallucinate on deleted evidence.
     *
     * @param parentFolderId The current node in the recursive traversal.
     * @param tenantId The case boundary, passed down to ensure isolated query execution.
     */
    private void deleteFolderContentsRecursively(String parentFolderId, String tenantId) {

        // Step A: Obliterate all physical evidence trackers and their semantic vectors in this specific folder
        documentRepository.findByFolderIdAndTenantId(parentFolderId, tenantId)
                .forEach(doc -> {
                    vectorCleanupRepository.wipeVectorsByDocumentId(doc.getId(), tenantId); // Wipe AI Memory
                    documentRepository.delete(doc); // Wipe Relational Tracker
                });

        // Step B: Fetch immediate sub-directories
        List<Folder> subFolders = folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);

        // Step C: Recurse downwards
        for (Folder subFolder : subFolders) {
            deleteFolderContentsRecursively(subFolder.getId(), tenantId);
            folderRepository.delete(subFolder);
        }
    }

    /**
     * Centralized Context Extraction & Security Gateway.
     * <p>
     * Extracts the active Case ID injected by the Web Interceptors. Fails fast if the context
     * was dropped or bypassed, providing a final layer of defense before database execution.
     *
     * @return The active, validated Tenant UUID string.
     * @throws IllegalStateException if the ThreadLocal context is empty.
     */
    private String getValidatedTenantId() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalStateException("CRITICAL: Operation denied. No active Case ID (Tenant) found in context.");
        }
        return tenantId;
    }
}