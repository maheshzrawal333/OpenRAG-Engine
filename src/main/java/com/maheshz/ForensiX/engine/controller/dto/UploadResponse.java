package com.maheshz.ForensiX.engine.controller.dto;

/**
 * Data Transfer Object (DTO) for Asynchronous Upload Handoffs.
 * <p>
 * In the ForensiX architecture, ingesting forensic evidence (like massive CSVs or PDFs)
 * is an extremely CPU-intensive and time-consuming process. If we processed the file
 * on the main HTTP thread, the client's browser would time out, and our Tomcat
 * connection pool would quickly exhaust, bringing the server down.
 * <p>
 * Instead, we use the "Asynchronous Handoff" pattern. The server accepts the file,
 * immediately dispatches it to a background AI worker thread, and returns this
 * lightweight DTO.
 */
public record UploadResponse(

        /**
         * The unique correlation identifier (UUID) for the background ingestion task.
         * <p>
         * The client UI must use this ID as a "claim ticket" to subscribe to the
         * Server-Sent Events (SSE) telemetry stream (e.g., GET /api/jobs/{jobId}/stream).
         * This allows the frontend to render a real-time progress bar without holding
         * the original upload request hostage.
         */
        String jobId
) {}