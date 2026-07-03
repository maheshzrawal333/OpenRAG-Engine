package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.controller.dto.ForensicsDTOs.*;
import com.maheshz.openrag.engine.service.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ForensicsController {

    private final RagChatService ragChatService;

    public ForensicsController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping("/api/forensics/analyze")
    public ResponseEntity<ForensicAnalysisResponse> analyzeEvidence(@RequestBody ForensicAnalysisRequest request) {
        return ResponseEntity.ok(ragChatService.analyzeEvidence(request));
    }

    @PostMapping("/api/forensics/report")
    public ResponseEntity<ReportResponse> generateReport(@RequestBody ReportRequest request) {
        return ResponseEntity.ok(ragChatService.generateReport(request));
    }

    /**
     * SENIOR FIX: Dynamically expose the full offline AI inventory to the frontend UI.
     * * Note: In a standard microservice, we might use a WebClient to hit
     * http://localhost:11434/api/tags and parse the dynamic Ollama inventory.
     * However, for a secure, air-gapped forensic architecture, we explicitly
     * declare our validated, pre-downloaded models to prevent API abuse or
     * requests for non-existent neural weights.
     */
    @GetMapping("/api/admin/models")
    public ResponseEntity<List<String>> getAvailableModels() {
        return ResponseEntity.ok(List.of(
                "llama3.2:3b",
                "qwen2.5-coder:7b",
                "deepseek-r1:8b",
                "deepseek-coder-v2:16b"
        ));
    }
}