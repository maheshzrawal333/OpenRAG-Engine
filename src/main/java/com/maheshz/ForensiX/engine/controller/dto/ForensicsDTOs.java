package com.maheshz.ForensiX.engine.controller.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.util.List;

/**
 * Global Registry for Forensic Data Transfer Objects (DTOs).
 * <p>
 * This utility class encapsulates all API payload contracts for the AI analysis and reporting endpoints.
 * By heavily utilizing Java 16+ `record` types, we guarantee shallow immutability and thread safety
 * for all incoming requests and outgoing responses.
 * <p>
 * We enforce "Fail-Fast" validation (JSR-380) directly at the perimeter to prevent malformed queries
 * from wasting expensive LLM compute cycles or crashing the application layer.
 */
public final class ForensicsDTOs {

    /**
     * Prevents instantiation of this registry. DTOs should be accessed statically.
     */
    private ForensicsDTOs() {
    }

    // ==========================================
    // 1. ANALYSIS ENDPOINT DTOs
    // ==========================================

    /**
     * API Request Payload: Triggers a hybrid-search RAG analysis.
     * Maps to: {@code POST /api/forensics/analyze}
     */
    @Schema(description = "Payload for initiating a forensic analysis against specific folders")
    public record ForensicAnalysisRequest(
            @Schema(description = "The investigator's question", example = "Who is the Muscle?")
            @NotBlank(message = "Question cannot be blank")
            @Size(max = 2000, message = "Question is too long (max 2000 chars)") // Prevents intentional prompt-bombing attacks
            String question,

            @Schema(description = "Target folder IDs to narrow search. Leave empty for case-wide search.")
            List<String> folderIds,

            @Schema(description = "The specific LLM model to use", example = "deepseek-r1:8b")
            @NotBlank(message = "Model selection is required")
            String model
    ) {
        /**
         * Compact Constructor: Enforces collection null-safety and deep immutability.
         * If the frontend omits the folder array, it safely defaults to an empty, unmodifiable list,
         * preventing NullPointerExceptions downstream in the Vector Database query execution.
         */
        public ForensicAnalysisRequest {
            folderIds = (folderIds == null) ? List.of() : List.copyOf(folderIds);
        }
    }

    /**
     * API Response Payload: The structured JSON response retrieved from the LLM.
     * <p>
     * Note: Getting a local LLM to output perfect JSON 100% of the time is inherently unstable.
     * We attach a custom deserializer to the `reasoning` field to gracefully handle schema drift.
     */
    @Schema(description = "Structured JSON Response forced out of the LLM")
    public record ForensicAnalysisResponse(
            @Schema(description = "The direct answer to the query")
            String answer,

            @Schema(description = "The citation and logical trace")
            @JsonDeserialize(using = FlexibleReasoningDeserializer.class)
            String reasoning
    ) {}

    // ==========================================
    // 2. REPORT GENERATION ENDPOINT DTOs
    // ==========================================

    /**
     * API Request Payload: Triggers final case synthesis.
     * Maps to: {@code POST /api/forensics/report}
     */
    @Schema(description = "Payload for generating a final case report from validated facts")
    public record ReportRequest(
            @Schema(description = "List of investigator-validated facts to synthesize")
            @NotEmpty(message = "At least one piece of evidence is required to generate a report")
            List<@NotBlank(message = "Evidence entry cannot be blank") String> evidence,

            @Schema(description = "The specific LLM model to use", example = "deepseek-coder-v2:16b")
            @NotBlank(message = "Model selection is required")
            String model
    ) {
        /**
         * Compact Constructor: Enforces immutability for the evidence array, protecting
         * against side-effect mutations while the LLM request is executing on a separate thread.
         */
        public ReportRequest {
            evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * API Response Payload: Returns the final compiled Markdown/Text report.
     */
    @Schema(description = "Response containing the final generated narrative")
    public record ReportResponse(
            String report
    ) {}

    // ==========================================
    // 3. THE AI "SELF-HEALING" DESERIALIZER
    // ==========================================

    /**
     * Postel's Law ("Be conservative in what you do, be liberal in what you accept from others")
     * implementation for unpredictable LLM JSON schema drift.
     * <p>
     * Even with strict prompt engineering, smaller LLMs may randomly change the expected JSON schema.
     * For example, instead of returning {"reasoning": "text..."}, an AI might panic and return
     * {"reasoning": ["text 1", "text 2"]} or {"reasoning": {"details": "text"}}.
     * <p>
     * Rather than throwing a hard 500 Jackson `MismatchedInputException` and dropping the entire
     * analysis, this deserializer gracefully flattens rogue arrays or nested objects back into
     * a safe String format so the UI doesn't crash.
     */
    public static class FlexibleReasoningDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);

            // 1. Handle Null AI Output (Protects against the AI emitting empty fields)
            if (node == null || node.isNull()) {
                return "No reasoning provided by the AI.";
            }

            // 2. Handle Standard String (Ideal Scenario - 99% of requests)
            if (node.isTextual()) {
                return node.asText();
            }

            // 3. Handle Rogue Array (e.g., [ "point 1", "point 2" ])
            // Flattens the unexpected array into a readable Markdown bulleted list.
            if (node.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode element : node) {
                    sb.append("- ").append(element.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 4. Handle Rogue Nested Object (e.g., { "details": "..." })
            // Converts the stray object back to a raw JSON string so the data isn't lost.
            if (node.isObject()) {
                return node.toString();
            }

            // 5. Fallback for primitives (ints, booleans accidentally injected)
            return node.asText();
        }
    }
}