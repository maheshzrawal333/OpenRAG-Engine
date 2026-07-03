package com.maheshz.openrag.engine.repository;

import com.maheshz.openrag.engine.domain.JobStatus;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<KnowledgeDocument, String> {
    Optional<KnowledgeDocument> findByJobId(String jobId);
    List<KnowledgeDocument> findByFolderIdAndTenantId(String folderId, String tenantId);
    Optional<KnowledgeDocument> findByIdAndTenantId(String id, String tenantId);
    boolean existsByFileNameAndFolderIdAndTenantId(String fileName, String folderId, String tenantId);

    // SENIOR FIX: Added @Transactional directly to the Modifying repository method
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeDocument d SET d.status = :status WHERE d.id = :id")
    void updateJobStatus(@Param("id") String id, @Param("status") JobStatus status);
}