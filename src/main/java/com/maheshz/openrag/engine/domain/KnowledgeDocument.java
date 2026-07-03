    package com.maheshz.openrag.engine.domain;

    import jakarta.persistence.Column;
    import jakarta.persistence.Entity;
    import jakarta.persistence.EnumType;
    import jakarta.persistence.Enumerated;
    import jakarta.persistence.Id;
    import jakarta.persistence.GeneratedValue;
    import jakarta.persistence.GenerationType;

    @Entity
    public class KnowledgeDocument extends BaseTenantEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private String id;

        @Column(nullable = false)
        private String folderId;

        @Column(nullable = false)
        private String fileName;

        @Column(nullable = false, unique = true)
        private String jobId;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private JobStatus status;

        // Standard Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getFolderId() { return folderId; }
        public void setFolderId(String folderId) { this.folderId = folderId; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }

        public JobStatus getStatus() { return status; }
        public void setStatus(JobStatus status) { this.status = status; }
    }