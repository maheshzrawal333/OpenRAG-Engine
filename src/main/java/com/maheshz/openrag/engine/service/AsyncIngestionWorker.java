package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.config.MetadataConstants;
import com.maheshz.openrag.engine.config.RedisPubSubConfig;
import com.maheshz.openrag.engine.domain.JobStatus;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.repository.VectorCleanupRepository;
import com.maheshz.openrag.engine.service.storage.StorageService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);

    private static final int EMBEDDING_BATCH_SIZE = 5;

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final VectorCleanupRepository vectorCleanupRepository;

    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    public AsyncIngestionWorker(VectorStore vectorStore,
                                DocumentRepository documentRepository,
                                VectorCleanupRepository vectorCleanupRepository,
                                StorageService storageService,
                                StringRedisTemplate redisTemplate) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.vectorCleanupRepository = vectorCleanupRepository;
        this.storageService = storageService;
        this.redisTemplate = redisTemplate;
    }

    @Async("aiTaskExecutor")
    public void processFileAsync(String objectKey, KnowledgeDocument tracker) {
        String jobId = tracker.getJobId();
        String fileName = tracker.getFileName().toLowerCase();
        long processStartTime = System.currentTimeMillis();

        // Use try-with-resources to guarantee the network stream is closed, preventing memory leaks
        try (InputStream cloudStream = storageService.downloadFile(objectKey)) {
            documentRepository.updateJobStatus(tracker.getId(), JobStatus.PROCESSING);

            // ==========================================
            // BRANCH 1: CSV FILES (Self-Managing)
            // ==========================================
            if (fileName.endsWith(".csv")) {
                broadcastProgress(jobId, "Tabular data detected. Executing memory-safe semantic CSV extraction...");

                // FIX 1: Pass the tracker as the second argument
                // FIX 2: We do NOT assign this to 'chunkedDocuments' because this method handles database inserts dynamically
                extractCsvSemantically(cloudStream, tracker);

                broadcastProgress(jobId, "Tabular processing and vector embedding complete.");

                // ==========================================
                // BRANCH 2: UNSTRUCTURED FILES (PDF, Word, TXT)
                // ==========================================
            } else {
                broadcastProgress(jobId, "Downloading stream and parsing structural binary components via Apache Tika...");
                Resource resource = new InputStreamResource(cloudStream);
                TikaDocumentReader documentReader = new TikaDocumentReader(resource);
                List<Document> rawDocuments = documentReader.get();

                broadcastProgress(jobId, "Executing localized token splitting algorithm...");
                TokenTextSplitter textSplitter = new TokenTextSplitter(500, 350, 50, 10000, true);
                List<Document> chunkedDocuments = textSplitter.apply(rawDocuments);

                int totalChunks = chunkedDocuments.size();
                broadcastProgress(jobId, "Target parsed successfully into " + totalChunks + " discrete chunks.");

                // Inject Strict Metadata
                chunkedDocuments.forEach(doc -> {
                    doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                    doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                    doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                    doc.getMetadata().put("file_name", tracker.getFileName());
                });

                log.info("Beginning vectorized partitioning for job {}. Total blocks: {}", jobId, totalChunks);

                // Manual batch-embedding loop for unstructured files
                for (int i = 0; i < totalChunks; i += EMBEDDING_BATCH_SIZE) {
                    int endOffset = Math.min(i + EMBEDDING_BATCH_SIZE, totalChunks);
                    List<Document> subBatch = chunkedDocuments.subList(i, endOffset);

                    vectorStore.add(subBatch);

                    int processedCount = endOffset;
                    int percentComplete = (int) (((double) processedCount / totalChunks) * 100);
                    long elapsedDurationMs = System.currentTimeMillis() - processStartTime;

                    double msPerChunk = (double) elapsedDurationMs / processedCount;
                    long totalRemainingChunks = totalChunks - processedCount;
                    long estimatedTimeRemainingMs = (long) (totalRemainingChunks * msPerChunk);

                    String progressTelemetry = String.format(
                            "Progress: %d/%d vector blocks committed (%d%%) | Elapsed: %s | ETA: %s",
                            processedCount, totalChunks, percentComplete, formatDuration(elapsedDurationMs), formatDuration(estimatedTimeRemainingMs)
                    );
                    broadcastProgress(jobId, progressTelemetry);
                }
            }

            documentRepository.updateJobStatus(tracker.getId(), JobStatus.COMPLETED);
            log.info("Job {} processing successfully finalized.", jobId);

        } catch (Exception e) {
            log.error("Fatal exception caught during execution workflow for job {}", jobId, e);
            documentRepository.updateJobStatus(tracker.getId(), JobStatus.FAILED);
            vectorCleanupRepository.wipeVectorsByDocumentId(tracker.getId(), tracker.getTenantId());
            log.warn("Rolled back partial vectors for failed document: {}", tracker.getId());
            broadcastProgress(jobId, "Pipeline Failure: " + e.getMessage());
        } finally {
            // SENIOR FIX: DO NOT DELETE THE FILE FROM S3!
            // We need to retain the original physical file in the vault for forensic evidence retrieval.
            broadcastComplete(jobId);
        }
    }

    // Batch-Flushing Memory Strategy
    private List<Document> extractCsvSemantically(InputStream inputStream, KnowledgeDocument tracker) {
        List<Document> memoryBuffer = new ArrayList<>();
        int batchSize = 100; // Flush to vector store every 100 documents to save RAM
        int totalProcessed = 0;

        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            List<String> headers = csvParser.getHeaderNames();
            StringBuilder chunkBuilder = new StringBuilder();
            int rowIndex = 1;

            for (CSVRecord record : csvParser) {
                chunkBuilder.append("Record ").append(rowIndex).append(": ");
                for (String header : headers) {
                    if (record.isSet(header)) {
                        chunkBuilder.append(header).append(" is ").append(record.get(header)).append(", ");
                    }
                }
                chunkBuilder.append(". \n");

                // Every 10 rows, create a single Document chunk
                if (rowIndex % 10 == 0) {
                    Document doc = new Document(chunkBuilder.toString());

                    // Inject metadata immediately
                    doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                    doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                    doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                    doc.getMetadata().put("file_name", tracker.getFileName());

                    memoryBuffer.add(doc);
                    chunkBuilder = new StringBuilder();
                }

                // SENIOR MEMORY FIX: Flush to DB and clear RAM every 100 documents
                if (memoryBuffer.size() >= batchSize) {
                    vectorStore.add(memoryBuffer);
                    totalProcessed += memoryBuffer.size();
                    memoryBuffer.clear(); // Free up RAM for garbage collection
                    broadcastProgress(tracker.getJobId(), "Streaming tabular data... embedded " + totalProcessed + " chunks.");
                }
                rowIndex++;
            }

            // Flush remaining trailing data
            if (!chunkBuilder.isEmpty()) {
                Document doc = new Document(chunkBuilder.toString());
                doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                doc.getMetadata().put("file_name", tracker.getFileName());
                memoryBuffer.add(doc);
            }
            if (!memoryBuffer.isEmpty()) {
                vectorStore.add(memoryBuffer);
            }

        } catch(Exception e) {
            log.error("Failed to parse semantic CSV", e);
            throw new RuntimeException("CSV Parsing Failed", e);
        }

        // Return an empty list because we already handled the database inserts dynamically!
        return new ArrayList<>();
    }

    // ==========================================
    // PRIVATE REDIS TELEMETRY HELPERS
    // ==========================================

    private void broadcastProgress(String jobId, String message) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, message);
    }

    private void broadcastComplete(String jobId) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, "Complete");
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long totalSeconds = ms / 1000;
        if (totalSeconds < 1) return ms + "ms";

        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes == 0) return seconds + "s";
        return minutes + "m " + seconds + "s";
    }
}