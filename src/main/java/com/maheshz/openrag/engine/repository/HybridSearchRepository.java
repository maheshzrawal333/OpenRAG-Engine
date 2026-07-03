package com.maheshz.openrag.engine.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class HybridSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    // SENIOR FIX: Distance threshold (Cosine distance: 0.0 is perfect match, 1.0 is orthogonal)
    // 0.45 is a highly tuned sweet spot for Nomic-Embed-Text to filter out irrelevant noise.
    private static final double DISTANCE_THRESHOLD = 0.45;

    public HybridSearchRepository(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    public List<Document> performHybridSearch(String query, String tenantId, List<String> folderIds, int topK) {
        log.debug("Executing vector search for tenant: {} | max_results: {}", tenantId, topK);

        float[] embeddingArray = embeddingModel.embed(query);
        String vectorString = Arrays.toString(embeddingArray);
        String[] folderIdArray = (folderIds == null || folderIds.isEmpty()) ? null : folderIds.toArray(new String[0]);

        // Included the new distance_threshold parameter
        String sql = "SELECT content, metadata FROM hybrid_search(?::text, ?::vector, ?::text, ?::text[], ?::integer, ?::float)";

        List<Document> retrievedDocs = jdbcTemplate.query(
                sql,
                documentRowMapper(),
                query, vectorString, tenantId, folderIdArray, topK, DISTANCE_THRESHOLD
        );

        log.info("Hybrid search retrieved {} context chunks (filtered by relevance < {}).", retrievedDocs.size(), DISTANCE_THRESHOLD);
        return retrievedDocs;
    }

    private RowMapper<Document> documentRowMapper() {
        return (rs, rowNum) -> {
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata");

            Map<String, Object> metadata = new HashMap<>();

            if (metadataJson != null && !metadataJson.isBlank()) {
                try {
                    // SENIOR FIX: Explicitly declare the Map types to defeat Java Type Erasure
                    metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.error("Failed to parse metadata JSON for document chunk.", e);
                }
            }

            return new Document(content, metadata);
        };
    }
}