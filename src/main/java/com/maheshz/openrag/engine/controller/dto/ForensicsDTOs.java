package com.maheshz.openrag.engine.controller.dto;

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

public final class ForensicsDTOs {

    private ForensicsDTOs() {
        // SENIOR FIX: Prevent instantiation of a utility class
    }

    // ==========================================
    // 1. ANALYSIS ENDPOINT DTOs
    // ==========================================

    @Schema(description = "Payload for initiating a forensic analysis against specific folders")
    public record ForensicAnalysisRequest(
            @Schema(description = "The investigator's question", example = "Who is the Muscle?")
            @NotBlank(message = "Question cannot be blank")
            @Size(max = 2000, message = "Question is too long (max 2000 chars)")
            String question,

            @Schema(description = "Target folder IDs to narrow search. Leave empty for case-wide search.")
            List<String> folderIds,

            @Schema(description = "The specific LLM model to use", example = "deepseek-r1:8b")
            @NotBlank(message = "Model selection is required")
            String model
    ) {
        // SENIOR FIX: Compact constructor ensures the List is strictly immutable and never null
        public ForensicAnalysisRequest {
            folderIds = (folderIds == null) ? List.of() : List.copyOf(folderIds);
        }
    }

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

    @Schema(description = "Payload for generating a final case report from validated facts")
    public record ReportRequest(
            @Schema(description = "List of investigator-validated facts to synthesize")
            @NotEmpty(message = "At least one piece of evidence is required to generate a report")
            List<@NotBlank(message = "Evidence entry cannot be blank") String> evidence,

            @Schema(description = "The specific LLM model to use", example = "deepseek-coder-v2:16b")
            @NotBlank(message = "Model selection is required")
            String model
    ) {
        // SENIOR FIX: Compact constructor for immutability
        public ReportRequest {
            evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
        }
    }

    @Schema(description = "Response containing the final generated narrative")
    public record ReportResponse(
            String report
    ) {}

    // ==========================================
    // 3. THE AI "SELF-HEALING" DESERIALIZER
    // ==========================================

    /**
     * Postel's Law implementation for unpredictable LLM JSON schema drift.
     * Hardened to survive Arrays, Nulls, and unexpected Nested Objects.
     */
    public static class FlexibleReasoningDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);

            // 1. Handle Null AI Output
            if (node == null || node.isNull()) {
                return "No reasoning provided by the AI.";
            }

            // 2. Handle Standard String (Ideal Scenario)
            if (node.isTextual()) {
                return node.asText();
            }

            // 3. Handle Rogue Array (e.g., [ "point 1", "point 2" ])
            if (node.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode element : node) {
                    sb.append("- ").append(element.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 4. Handle Rogue Nested Object (e.g., { "details": "..." })
            if (node.isObject()) {
                return node.toString(); // Fallback to raw JSON string to prevent crashing
            }

            return node.asText();
        }
    }
}