package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.domain.Folder;
import com.maheshz.openrag.engine.service.FolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/folders")
@Tag(name = "2. Folder Management", description = "Endpoints for managing the hierarchical case folder structure")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    @Operation(summary = "Create Folder", description = "Creates a new folder within the specified parent directory.")
    public ResponseEntity<Folder> createFolder(@Valid @RequestBody CreateFolderRequest request) {
        // SENIOR FIX: Return HTTP 201 (Created) instead of HTTP 200 (OK) for resource creation
        Folder createdFolder = folderService.createFolder(request.name(), request.parentFolderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdFolder);
    }

    @GetMapping
    @Operation(summary = "List Folders", description = "Retrieves all sub-folders for a given parent directory.")
    public ResponseEntity<List<Folder>> getFolders(@RequestParam(required = false) String parentFolderId) {
        return ResponseEntity.ok(folderService.getFolders(parentFolderId));
    }

    @PatchMapping("/{id}/rename")
    @Operation(summary = "Rename Folder", description = "Updates the name of an existing folder.")
    public ResponseEntity<Folder> renameFolder(@PathVariable String id, @Valid @RequestBody RenameFolderRequest request) {
        return ResponseEntity.ok(folderService.renameFolder(id, request.name()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Folder", description = "Recursively deletes a folder, its sub-folders, and wipes associated AI vectors.")
    public ResponseEntity<Void> deleteFolder(@PathVariable String id) {
        folderService.deleteFolder(id);
        return ResponseEntity.noContent().build();
    }

    // ==========================================
    // DATA TRANSFER OBJECTS (DTOs)
    // ==========================================

    public record CreateFolderRequest(
            @NotBlank(message = "Folder name cannot be blank")
            @Size(max = 100, message = "Folder name must be under 100 characters")
            String name,

            String parentFolderId
    ) {}

    public record RenameFolderRequest(
            @NotBlank(message = "New folder name cannot be blank")
            @Size(max = 100, message = "Folder name must be under 100 characters")
            String name
    ) {}
}