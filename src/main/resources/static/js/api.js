/**
 * Enterprise API Service
 * Implements centralized network interception, timeout management, and JWT Auth injection.
 */
const ApiService = {

    // SENIOR FIX #9: Default timeout reduced to 15 seconds. Only long-running tasks get more.
    _fetch: async (endpoint, options = {}, timeoutMs = 15000) => {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

        // ARCHITECTURAL DEBT NOTE (#2): In a production environment, this token should be
        // managed via an HttpOnly cookie set by the backend, not localStorage, to prevent XSS exfiltration.
        const token = localStorage.getItem('auth_token');
        const headers = {
            ...options.headers,
            ...(token && { 'Authorization': `Bearer ${token}` })
        };

        try {
            const response = await fetch(endpoint, {
                ...options,
                headers,
                signal: controller.signal
            });

            if (!response.ok) {
                // Safely parse Spring Boot's ProblemDetail JSON if it exists
                const errorData = await response.json().catch(() => ({}));
                throw new Error(errorData.detail || errorData.message || `HTTP ${response.status} - ${response.statusText}`);
            }

            // HTTP 204 No Content does not have a JSON body to parse
            if (response.status === 204) return true;

            return await response.json();
        } catch (error) {
            if (error.name === 'AbortError') {
                throw new Error(`Request timed out after ${timeoutMs / 1000} seconds.`);
            }
            throw error;
        } finally {
            clearTimeout(timeoutId);
        }
    },

    // ==========================================
    // INGESTION & EVIDENCE API
    // ==========================================
    uploadEvidence: async (file, folderId, caseId) => {
        const formData = new FormData();
        formData.append("file", file);
        formData.append("folderId", folderId);

        // Uploads might take longer, but the SSE stream handles the bulk of the wait.
        // We'll give the initial upload 30 seconds.
        return await ApiService._fetch('/api/knowledge/upload', {
            method: 'POST',
            headers: { 'X-Tenant-ID': caseId }, // Browser automatically sets multipart boundary
            body: formData
        }, 30000);
    },

    getFiles: async (folderId, caseId) => {
        // SENIOR FIX #13: Encode URI Component for folderId
        const queryFolder = folderId === "root" ? "root" : encodeURIComponent(folderId);
        return await ApiService._fetch(`/api/knowledge/documents?folderId=${queryFolder}`, {
            method: 'GET',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    deleteFile: async (documentId, caseId) => {
        return await ApiService._fetch(`/api/knowledge/documents/${encodeURIComponent(documentId)}`, {
            method: 'DELETE',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    // ==========================================
    // FOLDER HIERARCHY API
    // ==========================================
    getFolders: async (parentFolderId, caseId) => {
        const url = `/api/folders` + (parentFolderId && parentFolderId !== "root" ? `?parentFolderId=${encodeURIComponent(parentFolderId)}` : "");
        return await ApiService._fetch(url, {
            method: 'GET',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    createFolder: async (name, parentFolderId, caseId) => {
        const payload = {
            name: name,
            parentFolderId: parentFolderId === "root" ? null : parentFolderId
        };

        return await ApiService._fetch('/api/folders', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Tenant-ID': caseId
            },
            body: JSON.stringify(payload)
        });
    },

    renameFolder: async (folderId, caseId, newName) => {
        return await ApiService._fetch(`/api/folders/${encodeURIComponent(folderId)}/rename`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ name: newName })
        });
    },

    deleteFolder: async (folderId, caseId) => {
        return await ApiService._fetch(`/api/folders/${encodeURIComponent(folderId)}`, {
            method: 'DELETE',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    // ==========================================
    // AI FORENSICS API
    // ==========================================
    askStructuredQuestion: async (question, folderIds, caseId, modelName) => {
        // SENIOR FIX #9: LLM inference needs a longer timeout (120 seconds)
        return await ApiService._fetch('/api/forensics/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ question, folderIds, model: modelName })
        }, 120000);
    },

    generateReport: async (validatedEvidenceList, caseId, modelName) => {
        // SENIOR FIX #9: Report synthesis needs a longer timeout (120 seconds)
        return await ApiService._fetch('/api/forensics/report', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ evidence: validatedEvidenceList, model: modelName })
        }, 120000);
    },

    // ==========================================
    // SYSTEM & TENANT ADMIN API
    // ==========================================
    getTenants: async () => {
        return await ApiService._fetch('/api/admin/tenants', { method: 'GET' });
    },

    createTenant: async (id, name) => {
        return await ApiService._fetch(`/api/admin/tenants?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}`, {
            method: 'POST',
            headers: { 'X-Tenant-ID': id }
        });
    },

    getModels: async () => {
        try {
            // Very short timeout for pinging local models
            return await ApiService._fetch('/api/admin/models', { method: 'GET' }, 3000);
        } catch (e) {
            console.warn("Backend model endpoint unavailable. Failing over to local offline inventory.", e);
            return [
                "llama3.2:3b",
                "qwen2.5-coder:7b",
                "deepseek-r1:8b",
                "deepseek-coder-v2:16b"
            ];
        }
    }
};