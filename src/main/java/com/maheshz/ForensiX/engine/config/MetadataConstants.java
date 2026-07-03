package com.maheshz.ForensiX.engine.config;

/**
 * Centralized registry for Vector Database Metadata Keys.
 * <p>
 * In our Retrieval-Augmented Generation (RAG) architecture, metadata attached to vector chunks
 * is the primary mechanism for security, tenant isolation, and targeted hybrid searching.
 * <p>
 * By centralizing these keys here, we establish a strict contract and prevent "magic string"
 * typos across the application—specifically bridging the gap between the Java ingestion pipeline,
 * the Spring AI Document mappers, and the PostgreSQL JSONB queries.
 */
public final class MetadataConstants {

    /**
     * The architectural cornerstone for zero-data-leakage multi-tenancy.
     * Every vector chunk MUST be tagged with this ID during ingestion. The hybrid search
     * function at the database level will strictly filter by this key before performing
     * any vector similarity calculations, mathematically guaranteeing data isolation.
     */
    public static final String TENANT_ID = "tenant_id";

    /**
     * Used to scope vector searches to a specific subset of evidence (e.g., searching
     * only within a "Financials" or "Surveillance" directory). This allows the investigator
     * to narrow the LLM's context window and reduce noise.
     */
    public static final String FOLDER_ID = "folder_id";

    /**
     * The structural link connecting a fragmented vector chunk in the pgvector store back
     * to its parent relational entity (KnowledgeDocument). This is critical for data
     * lifecycle management, enabling us to cleanly wipe all associated vectors from
     * AI memory when a user deletes a physical file.
     */
    public static final String DOCUMENT_ID = "document_id";

    /**
     * Private constructor to enforce the Singleton/Utility pattern.
     * This class acts purely as a constants registry and must never be instantiated.
     */
    private MetadataConstants() {
        // Deliberately left empty to prevent instantiation
    }
}