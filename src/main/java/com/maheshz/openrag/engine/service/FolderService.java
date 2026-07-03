package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.domain.Folder;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.repository.FolderRepository;
import com.maheshz.openrag.engine.repository.VectorCleanupRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final VectorCleanupRepository vectorCleanupRepository;

    public FolderService(FolderRepository folderRepository, DocumentRepository documentRepository, VectorCleanupRepository vectorCleanupRepository) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.vectorCleanupRepository = vectorCleanupRepository;
    }

    @Transactional
    public Folder createFolder(String name, String parentFolderId) {
        String tenantId = getValidatedTenantId();

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

    @Transactional(readOnly = true)
    public List<Folder> getFolders(String parentFolderId) {
        String tenantId = getValidatedTenantId();
        return folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);
    }

    @Transactional
    public Folder renameFolder(String folderId, String newName) {
        String tenantId = getValidatedTenantId();

        Folder folder = folderRepository.findById(folderId)
                .filter(f -> f.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found or access denied."));

        folder.setName(newName);
        return folderRepository.save(folder);
    }

    @Transactional
    public void deleteFolder(String folderId) {
        String tenantId = getValidatedTenantId();

        Folder targetFolder = folderRepository.findById(folderId)
                .filter(f -> f.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found or access denied."));

        deleteFolderContentsRecursively(folderId, tenantId);
        folderRepository.delete(targetFolder);
    }

    private void deleteFolderContentsRecursively(String parentFolderId, String tenantId) {
        // SENIOR FIX: Wipe AI Vectors before deleting the relational file tracker
        documentRepository.findByFolderIdAndTenantId(parentFolderId, tenantId)
                .forEach(doc -> {
                    vectorCleanupRepository.wipeVectorsByDocumentId(doc.getId(), tenantId);
                    documentRepository.delete(doc);
                });

        List<Folder> subFolders = folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);

        for (Folder subFolder : subFolders) {
            deleteFolderContentsRecursively(subFolder.getId(), tenantId);
            folderRepository.delete(subFolder);
        }
    }

    private String getValidatedTenantId() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalStateException("CRITICAL: Operation denied. No active Case ID (Tenant) found in context.");
        }
        return tenantId;
    }
}