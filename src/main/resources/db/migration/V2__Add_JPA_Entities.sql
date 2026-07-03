-- V2__Add_JPA_Entities.sql
-- Synchronizes the relational schema precisely with the compiled Spring Data JPA domain models

-- 1. TENANT MASTER TABLE
CREATE TABLE IF NOT EXISTS tenant (
                                      id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE
    );

-- 2. TENANT CONFIGURATION TABLE
CREATE TABLE IF NOT EXISTS tenant_config (
                                             tenant_id VARCHAR(255) PRIMARY KEY, -- Matches Java @Id private String tenantId;
    ai_model_name VARCHAR(255),
    temperature FLOAT8,
    system_prompt TEXT
    );

-- 3. HIERARCHICAL DIRECTORY SCHEMA
CREATE TABLE IF NOT EXISTS folder (
                                      id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    parent_folder_id VARCHAR(255),
    tenant_id VARCHAR(255) NOT NULL,     -- Inherited from BaseTenantEntity
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL -- Inherited from BaseTenantEntity
    );

-- 4. KNOWLEDGE TRACKING ARCHIVE
CREATE TABLE IF NOT EXISTS knowledge_document (
                                                  id VARCHAR(255) PRIMARY KEY,
    job_id VARCHAR(255) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    folder_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,     -- Inherited from BaseTenantEntity
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL -- Inherited from BaseTenantEntity
    );

-- 5. PERFORMANCE INDEXES FOR DISTRIBUTED ARCHITECTURE
CREATE INDEX IF NOT EXISTS idx_folder_tenant ON folder(tenant_id);
CREATE INDEX IF NOT EXISTS idx_document_tenant_folder ON knowledge_document(tenant_id, folder_id);