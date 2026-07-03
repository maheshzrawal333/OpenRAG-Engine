/**
 * ForensiX - Enterprise Human-In-The-Loop Architecture
 */

let chatHistory = {};
let activeMessageId = null;
let verifiedFacts = [];
let selectedContexts = new Map();

// SENIOR FIX #5: Generation counter to prevent out-of-order async race conditions when switching cases
let caseGeneration = 0;

const elements = {
    caseId: document.getElementById('caseId'),
    fileTreeContainer: document.getElementById('fileTreeContainer'),
    chatWindow: document.getElementById('chatWindow'),
    questionInput: document.getElementById('questionInput'),
    askBtn: document.getElementById('askBtn'),
    modelSelector: document.getElementById('modelSelector'),
    reasonContainer: document.getElementById('reasonContainer'),
    validBtn: document.getElementById('validBtn'),
    reportContainer: document.getElementById('reportContainer'),
    generateReportBtn: document.getElementById('generateReportBtn'),
    verifiedCount: document.getElementById('verifiedCount')
};

function swapClass(el, oldCls, newCls) {
    if (!el) return;
    if (oldCls) el.classList.remove(oldCls);
    if (newCls) el.classList.add(newCls);
}

function escapeHTML(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

const CustomModal = {
    _timeoutId: null,
    _lastActiveElement: null,

    show: (title, message, type, confirmBtnText, confirmBtnColorClass, onConfirmCallback) => {
        if (CustomModal._timeoutId) clearTimeout(CustomModal._timeoutId);

        // SENIOR FIX #15: Store the element that had focus before opening the modal
        CustomModal._lastActiveElement = document.activeElement;

        const overlay = document.getElementById('modalOverlay');
        const content = document.getElementById('modalContent');
        const input = document.getElementById('modalInput');
        const confirmBtn = document.getElementById('modalConfirm');

        document.getElementById('modalTitle').innerText = title;
        document.getElementById('modalBody').innerText = message;

        confirmBtn.className = `px-4 py-2 rounded text-white font-bold transition-colors shadow ${confirmBtnColorClass}`;
        confirmBtn.innerText = confirmBtnText;

        if (type === 'prompt') {
            input.classList.remove('hidden');
            input.value = '';
            setTimeout(() => input.focus(), 50);
        } else {
            input.classList.add('hidden');
            confirmBtn.focus();
        }

        overlay.classList.remove('hidden');
        overlay.classList.add('flex');
        setTimeout(() => swapClass(content, 'scale-95', 'scale-100'), 10);

        const newConfirmBtn = confirmBtn.cloneNode(true);
        confirmBtn.parentNode.replaceChild(newConfirmBtn, confirmBtn);

        newConfirmBtn.addEventListener('click', () => {
            const val = input.value;
            CustomModal.close();
            if(type === 'prompt') onConfirmCallback(val);
            else onConfirmCallback();
        });

        document.getElementById('modalCancel').onclick = CustomModal.close;

        // SENIOR FIX #15: Escape to close & Backdrop click to close
        const keyHandler = (e) => {
            if (e.key === 'Escape') {
                CustomModal.close();
                document.removeEventListener('keydown', keyHandler);
            }
        };
        document.addEventListener('keydown', keyHandler);

        overlay.onclick = (e) => {
            if (e.target === overlay) {
                CustomModal.close();
                overlay.onclick = null;
            }
        };
    },

    close: () => {
        if (CustomModal._timeoutId) clearTimeout(CustomModal._timeoutId);

        const overlay = document.getElementById('modalOverlay');
        const content = document.getElementById('modalContent');

        swapClass(content, 'scale-100', 'scale-95');

        CustomModal._timeoutId = setTimeout(() => {
            overlay.classList.remove('flex');
            overlay.classList.add('hidden');

            // SENIOR FIX #15: Return focus
            if (CustomModal._lastActiveElement) {
                CustomModal._lastActiveElement.focus();
                CustomModal._lastActiveElement = null;
            }
        }, 150);
    }
};

document.addEventListener("DOMContentLoaded", async () => {
    elements.askBtn.addEventListener("click", handleAsk);
    // SENIOR FIX #12: Changed 'keypress' to 'keydown'
    elements.questionInput.addEventListener("keydown", (e) => { if (e.key === 'Enter') handleAsk(); });
    elements.validBtn.addEventListener("click", handleValidateReason);
    elements.generateReportBtn.addEventListener("click", handleGenerateReport);

    document.getElementById('refreshBtn').addEventListener("click", refreshTreeState);
    document.getElementById('newFolderBtn').addEventListener("click", handleCreateFolder);
    document.getElementById('newCaseBtn').addEventListener("click", handleCreateCase);

    // SENIOR FIX #7: Guarding case switches if there is unsaved validated work
    let previousCaseValue = elements.caseId.value;
    elements.caseId.addEventListener("change", (e) => {
        const newCaseValue = e.target.value;
        if (verifiedFacts.length > 0) {
            CustomModal.show(
                "Unsaved Evidence",
                "You have validated facts that haven't been synthesized into a report. Switching cases will discard them. Proceed?",
                "confirm",
                "Discard & Switch",
                "bg-red-600 hover:bg-red-500",
                () => {
                    previousCaseValue = newCaseValue;
                    resetCaseContext();
                }
            );
            // Revert the dropdown if they cancel or close the modal
            document.getElementById('modalCancel').addEventListener('click', () => { e.target.value = previousCaseValue; }, { once: true });
            document.getElementById('modalOverlay').addEventListener('click', (ev) => { if(ev.target.id === 'modalOverlay') e.target.value = previousCaseValue; }, { once: true });
            document.addEventListener('keydown', (ev) => { if(ev.key === 'Escape') e.target.value = previousCaseValue; }, { once: true });
        } else {
            previousCaseValue = newCaseValue;
            resetCaseContext();
        }
    });

    document.getElementById('globalUploadBtn').addEventListener('click', handleGlobalUpload);
    document.getElementById('globalRenameBtn').addEventListener('click', handleGlobalRename);
    document.getElementById('globalDeleteBtn').addEventListener('click', handleGlobalDelete);
    document.getElementById('fileInput').addEventListener('change', window.handleUpload);

    // SENIOR FIX #8: Event Delegation for dynamic tree clicks and checkboxes
    elements.fileTreeContainer.addEventListener('click', (event) => {
        // 1. Handle Checkboxes
        if (event.target.matches('.context-checkbox')) {
            const id = event.target.dataset.id;
            const name = event.target.dataset.name;
            const type = event.target.dataset.type;

            if (event.target.checked) {
                selectedContexts.set(id, { name: name, type: type });
            } else {
                selectedContexts.delete(id);
            }
            updateContextBanner();
            return;
        }

        // 2. Handle Folder Expanders
        const expanderBtn = event.target.closest('[data-role="expander"]');
        const folderRow = event.target.closest('[data-role="folder-row"]');

        if (expanderBtn || (folderRow && !event.target.matches('input[type="checkbox"]'))) {
            const targetId = expanderBtn ? expanderBtn.dataset.folderId : folderRow.dataset.folderId;
            toggleFolderExpander(targetId);
        }
    });

    // Handle space/enter on rows for accessibility
    elements.fileTreeContainer.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
            const fileRow = event.target.closest('[data-role="file-row"]');
            if (fileRow) {
                event.preventDefault();
                const cb = fileRow.querySelector('input');
                cb.checked = !cb.checked;
                // Trigger the click logic defined in the delegate
                cb.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                return;
            }

            const folderRow = event.target.closest('[data-role="folder-row"]');
            if (folderRow) {
                event.preventDefault();
                toggleFolderExpander(folderRow.dataset.folderId);
            }
        }
    });

    initViewControllers();
    await loadModels();
    await loadCases();
    resetCaseContext();
});

function initViewControllers() {
    function toggleColumn(colId, btnId) {
        const column = document.getElementById(colId);
        const btn = document.getElementById(btnId);
        if(!column || !btn) return;

        column.classList.toggle('hidden');
        if (column.classList.contains('hidden')) {
            swapClass(btn, 'bg-blue-600', 'bg-slate-700');
            swapClass(btn, 'text-white', 'text-slate-400');
            btn.classList.add('opacity-50');
        } else {
            swapClass(btn, 'bg-slate-700', 'bg-blue-600');
            swapClass(btn, 'text-slate-400', 'text-white');
            btn.classList.remove('opacity-50');
        }
    }
    document.getElementById('toggleCol1')?.addEventListener('click', () => toggleColumn('col-1', 'toggleCol1'));
    document.getElementById('toggleCol2')?.addEventListener('click', () => toggleColumn('col-2', 'toggleCol2'));
    document.getElementById('toggleCol3')?.addEventListener('click', () => toggleColumn('col-3', 'toggleCol3'));
}

async function loadModels() {
    const models = await ApiService.getModels();
    elements.modelSelector.innerHTML = models.map(m => `<option value="${escapeHTML(m)}">${escapeHTML(m)}</option>`).join('');
}

function resetCaseContext() {
    caseGeneration++; // SENIOR FIX #5: Increment generation
    const myGen = caseGeneration;

    chatHistory = {};
    activeMessageId = null;
    verifiedFacts = [];
    selectedContexts.clear();

    updateContextBanner();
    updateVerifiedCounter();

    elements.chatWindow.innerHTML = `<div class="text-slate-500 text-sm italic text-center mt-2">Initialize query...</div>`;
    elements.reasonContainer.innerHTML = `<p class="text-slate-500 italic text-center mt-4">Select an AI response to view its forensic reasoning.</p>`;
    elements.reportContainer.innerHTML = `<p class="text-slate-500 italic">Click 'Go' to synthesize a complete case narrative based ONLY on validated evidence.</p>`;
    elements.validBtn.disabled = true;

    loadRootDirectory(myGen);
}

function updateContextBanner() {
    const displayElement = document.getElementById('activeContextDisplay');
    if (selectedContexts.size === 0) {
        displayElement.innerHTML = `<span class="bg-slate-800 px-2 py-1 rounded text-blue-300 shadow-inner">/Root (Entire Case)</span>`;
    } else {
        let tagsHtml = Array.from(selectedContexts.values()).map(item => {
            const icon = item.type === 'folder' ? '📁' : getFileIcon(item.name);
            return `<span class="bg-blue-900/80 border border-blue-500 text-blue-100 px-2 py-0.5 rounded text-[11px] mr-1 mb-1 inline-flex items-center shadow-sm whitespace-nowrap">${icon} <span class="ml-1 truncate max-w-[150px]">${escapeHTML(item.name)}</span></span>`;
        }).join('');
        displayElement.innerHTML = `<div class="flex flex-wrap gap-1 w-full overflow-hidden">${tagsHtml}</div>`;
    }
}

async function loadCases(selectTargetId = null) {
    if (!elements.caseId) return;
    try {
        const cases = await ApiService.getTenants();
        window.activeCases = cases;

        if (cases.length === 0) {
            elements.caseId.innerHTML = `<option value="DEFAULT-CASE">No Cases Found</option>`;
            return;
        }

        elements.caseId.innerHTML = cases.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.id)} - ${escapeHTML(c.name)}</option>`).join('');
        if (selectTargetId) elements.caseId.value = selectTargetId;
    } catch (e) {
        // SENIOR FIX #14: Distinguish offline mode from zero cases
        console.error("Failed to load cases:", e);
        elements.caseId.innerHTML = `<option value="DEFAULT-CASE" class="text-red-500">Offline Mode (Local Default)</option>`;
        window.activeCases = [];
    }
}

async function handleCreateCase() {
    CustomModal.show(
        "New Investigation Case",
        "Enter a secure name for the new investigation case:",
        "prompt",
        "Create Case",
        "bg-blue-600 hover:bg-blue-500",
        async (caseName) => {
            if (!caseName) return;

            // SENIOR FIX #4: Generate UUIDs for strong, collision-resistant identity
            const caseId = `CASE-${crypto.randomUUID()}`;

            try {
                await ApiService.createTenant(caseId, caseName);
                await loadCases(caseId);
                resetCaseContext();
            } catch (e) {
                console.error("Create Case Failed:", e);
                CustomModal.show("Error", e.message || "Failed to create new case.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

async function refreshTreeState() {
    const openFolders = Array.from(document.querySelectorAll('.tree-line:not(.hidden)'))
        .map(el => el.id.replace('children-', ''));

    const myGen = caseGeneration;
    await loadRootDirectory(myGen);

    for (const folderId of openFolders) {
        if (folderId === 'root') continue;
        const childrenContainer = document.getElementById(`children-${folderId}`);
        const expanderIcon = document.querySelector(`[data-role="expander"][data-folder-id="${folderId}"]`);

        if (childrenContainer && childrenContainer.classList.contains('hidden')) {
            childrenContainer.classList.remove('hidden');
            if (expanderIcon) expanderIcon.innerText = '▼';
            await expandFolder(folderId, childrenContainer, myGen);
        }
    }
}

async function loadRootDirectory(gen) {
    elements.fileTreeContainer.innerHTML = `
        <div data-role="folder-row" data-folder-id="root" class="tree-node p-1.5 rounded flex items-center gap-2 border border-transparent">
            <span class="text-slate-400">🗄️</span> 
            <span class="font-bold text-blue-300 cursor-default">Case Root</span>
        </div>
        <div id="children-root" class="tree-line">
            <div class="text-center text-slate-500 text-xs mt-2 animate-pulse">Loading Tree...</div>
        </div>
    `;
    await expandFolder("root", document.getElementById("children-root"), gen);
}

function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    if (['pdf'].includes(ext)) return '📕';
    if (['csv', 'xlsx', 'xls'].includes(ext)) return '📊';
    if (['png', 'jpg', 'jpeg'].includes(ext)) return '🖼️';
    if (['doc', 'docx', 'txt'].includes(ext)) return '📝';
    return '📄';
}

async function expandFolder(folderId, containerElement, gen = caseGeneration) {
    const caseId = elements.caseId.value;

    try {
        const [folders, files] = await Promise.all([
            ApiService.getFolders(folderId, caseId),
            ApiService.getFiles(folderId, caseId)
        ]);

        // SENIOR FIX #5: Check if the user swapped cases while we were fetching
        if (gen !== caseGeneration) return;

        containerElement.innerHTML = '';
        if (folders.length === 0 && files.length === 0) {
            containerElement.innerHTML = '<div class="text-slate-600 text-xs italic py-1">Empty</div>';
            return;
        }

        // SENIOR FIX #8: Use data attributes for event delegation instead of inline handlers
        folders.forEach(folder => {
            const isChecked = selectedContexts.has(folder.id) ? 'checked' : '';
            const fId = escapeHTML(folder.id);
            const fName = escapeHTML(folder.name);

            const folderHtml = `
                <div class="mt-1">
                    <div data-role="folder-row" data-folder-id="${fId}" tabindex="0" role="button" class="tree-node p-1 rounded flex items-center border border-transparent hover:bg-slate-700/50 focus:bg-slate-700/50 focus:outline-none transition-colors cursor-pointer">
                        <span data-role="expander" data-folder-id="${fId}" class="text-slate-400 hover:text-white px-1 w-4 text-center shrink-0">▶</span>
                        <input type="checkbox" ${isChecked} tabindex="-1" class="w-3 h-3 cursor-pointer shrink-0 accent-blue-500 mr-2 ml-1 context-checkbox" data-id="${fId}" data-name="${fName}" data-type="folder">
                        <span class="text-yellow-500 shrink-0 text-sm mr-1">📁</span> 
                        <span class="text-sm text-slate-200 select-none pr-4 truncate" title="${fName}">${fName}</span>
                    </div>
                    <div id="children-${fId}" class="tree-line hidden"></div>
                </div>
            `;
            containerElement.insertAdjacentHTML('beforeend', folderHtml);
        });

        files.forEach(file => {
            const isChecked = selectedContexts.has(file.id) ? 'checked' : '';
            const icon = getFileIcon(file.fileName);
            const fId = escapeHTML(file.id);
            const fName = escapeHTML(file.fileName);

            const fileHtml = `
                <div data-role="file-row" tabindex="0" role="button" class="py-1 px-2 flex items-center hover:bg-slate-700/30 focus:bg-slate-700/50 focus:outline-none rounded mt-1 transition-colors cursor-pointer">
                    <input type="checkbox" ${isChecked} tabindex="-1" class="w-3 h-3 cursor-pointer shrink-0 accent-blue-500 mr-2 ml-5 context-checkbox" data-id="${fId}" data-name="${fName}" data-type="file">
                    <span class="text-xs shrink-0 mr-1 opacity-90">${icon}</span>
                    <span class="text-xs select-text pr-4 break-words" title="${fName}">${fName}</span>
                </div>
            `;
            containerElement.insertAdjacentHTML('beforeend', fileHtml);
        });
    } catch (error) {
        console.error(`Failed to expand folder ${folderId}:`, error);
        if (gen === caseGeneration) {
            containerElement.innerHTML = `<div class="text-red-400 text-xs py-1">Error loading contents</div>`;
        }
    }
}

async function toggleFolderExpander(folderId) {
    const childrenContainer = document.getElementById(`children-${folderId}`);
    const expanderIcon = document.querySelector(`[data-role="expander"][data-folder-id="${folderId}"]`);

    if (!childrenContainer) return;

    if (childrenContainer.classList.contains('hidden')) {
        childrenContainer.classList.remove('hidden');
        if (expanderIcon) expanderIcon.innerText = '▼';
        if (childrenContainer.innerHTML.trim() === '') await expandFolder(folderId, childrenContainer);
    } else {
        childrenContainer.classList.add('hidden');
        if (expanderIcon) expanderIcon.innerText = '▶';
    }
};

async function handleCreateFolder() {
    let targetFolderId = "root";
    let targetFolderName = "Root";

    const selected = Array.from(selectedContexts.entries());
    if (selected.length > 1) {
        return CustomModal.show("Action Blocked", "Please select only ONE folder to create a new folder inside.", "alert", "OK", "bg-blue-600", () => {});
    } else if (selected.length === 1) {
        if (selected[0][1].type !== 'folder') {
            return CustomModal.show("Action Blocked", "You cannot create a folder inside a file. Select a folder instead.", "alert", "OK", "bg-blue-600", () => {});
        }
        targetFolderId = selected[0][0];
        targetFolderName = selected[0][1].name;
    }

    CustomModal.show(
        "Create Directory",
        `Creating a new folder inside: /${targetFolderName}`,
        "prompt",
        "Create Folder",
        "bg-blue-600 hover:bg-blue-500",
        async (folderName) => {
            if (!folderName || folderName.trim() === "") return;
            try {
                await ApiService.createFolder(folderName.trim(), targetFolderId, elements.caseId.value);
                await refreshTreeState();
            } catch (e) {
                console.error("Folder creation failed:", e);
                CustomModal.show("Error", e.message || "Failed to create folder.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

function handleGlobalUpload() {
    let targetFolderId = "root";
    const selected = Array.from(selectedContexts.entries());

    if (selected.length > 1) {
        return CustomModal.show("Notice", "Please check only ONE folder to upload evidence into.", "alert", "OK", "bg-blue-600", ()=>{});
    } else if (selected.length === 1) {
        if (selected[0][1].type !== 'folder') {
            return CustomModal.show("Notice", "You cannot upload evidence directly into a file. Check a folder instead.", "alert", "OK", "bg-blue-600", ()=>{});
        }
        targetFolderId = selected[0][0];
    }

    window.targetUploadFolderId = targetFolderId;
    document.getElementById('fileInput').click();
}

window.handleUpload = async (event) => {
    const files = event.target.files;
    if (files.length === 0) return;
    const targetFolderId = window.targetUploadFolderId || "root";

    const originalText = document.getElementById('activeContextDisplay').innerHTML;
    document.getElementById('activeContextDisplay').innerHTML = `<span class="text-yellow-400 font-bold bg-slate-800 px-2 py-1 rounded animate-pulse">Uploading ${files.length} items...</span>`;

    // SENIOR FIX #3: Collect failures instead of overwriting the modal
    const failures = [];

    for (let i = 0; i < files.length; i++) {
        try {
            const data = await ApiService.uploadEvidence(files[i], targetFolderId, elements.caseId.value);

            await new Promise((resolve, reject) => {
                const eventSource = new EventSource(`/api/jobs/${data.jobId}/stream`);

                const streamTimeout = setTimeout(() => {
                    eventSource.close();
                    reject(new Error("Stream timed out from backend."));
                }, 600000);

                eventSource.addEventListener('progress', (e) => {
                    if (e.data === 'Complete') {
                        clearTimeout(streamTimeout);
                        eventSource.close();
                        resolve();
                    }
                });

                // SENIOR FIX #3: Reject properly on stream loss
                eventSource.onerror = () => {
                    clearTimeout(streamTimeout);
                    eventSource.close();
                    reject(new Error("Connection to ingestion stream lost before completion."));
                };
            });
        } catch (error) {
            console.error("Upload failed for", files[i].name, error);
            failures.push({ name: files[i].name, message: error.message || "Unknown error" });
        }
    }

    event.target.value = '';
    document.getElementById('activeContextDisplay').innerHTML = originalText;
    await refreshTreeState();

    if (failures.length > 0) {
        const list = failures.map(f => `• ${escapeHTML(f.name)}: ${escapeHTML(f.message)}`).join('\n');
        CustomModal.show(
            failures.length === files.length ? "Upload Failed" : "Some Files Failed",
            `${failures.length} of ${files.length} file(s) could not be processed:\n\n${list}`,
            "alert", "OK", "bg-red-600", () => {}
        );
    }
};

async function handleGlobalRename() {
    const selected = Array.from(selectedContexts.entries());
    if (selected.length !== 1) {
        return CustomModal.show("Notice", "Please select exactly ONE folder to rename.", "alert", "OK", "bg-blue-600", ()=>{});
    }

    const [id, data] = selected[0];
    if (data.type !== 'folder') {
        return CustomModal.show("Notice", "Currently, only folders can be renamed.", "alert", "OK", "bg-blue-600", ()=>{});
    }

    CustomModal.show(
        "Rename Folder",
        `Enter a new name for "${data.name}":`,
        "prompt",
        "Rename",
        "bg-yellow-600 hover:bg-yellow-500",
        async (newName) => {
            if (!newName || newName === data.name) return;
            try {
                await ApiService.renameFolder(id, elements.caseId.value, newName);
                selectedContexts.set(id, { name: newName, type: 'folder' });
                updateContextBanner();
                await refreshTreeState();
            } catch (e) {
                console.error("Rename failed:", e);
                CustomModal.show("Error", e.message || "Failed to rename folder.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

async function handleGlobalDelete() {
    if (selectedContexts.size === 0) {
        return CustomModal.show("Notice", "Please select at least one item to delete.", "alert", "OK", "bg-blue-600", ()=>{});
    }

    CustomModal.show(
        "CRITICAL ACTION",
        `Are you sure you want to permanently wipe ${selectedContexts.size} selected item(s) and their AI vectors? This cannot be undone.`,
        "confirm",
        "Wipe Data",
        "bg-red-600 hover:bg-red-500 text-white",
        async () => {
            let hasError = false;
            let successIds = [];

            for (const [id, data] of selectedContexts.entries()) {
                try {
                    if (data.type === 'folder') {
                        await ApiService.deleteFolder(id, elements.caseId.value);
                    } else {
                        await ApiService.deleteFile(id, elements.caseId.value);
                    }
                    successIds.push(id);
                } catch (e) {
                    console.error("Delete failed for item:", id, e);
                    hasError = true;
                }
            }

            if (hasError) {
                CustomModal.show("Partial Success", "Some items could not be deleted. Ensure folders are completely empty before deleting them.", "alert", "OK", "bg-yellow-600", ()=>{});
            }

            successIds.forEach(id => selectedContexts.delete(id));
            updateContextBanner();
            await refreshTreeState();
        }
    );
}

// --- FORENSIX CHAT & REASONING PIPELINE ---
async function handleAsk() {
    const question = elements.questionInput.value.trim();
    if (!question) return;

    const model = elements.modelSelector.value;
    elements.questionInput.value = '';
    elements.questionInput.disabled = true;

    elements.askBtn.disabled = true;
    const originalBtnText = elements.askBtn.innerText;
    elements.askBtn.innerText = "⏳...";

    const targetFolderIds = Array.from(selectedContexts.keys());

    appendToChat('Detective', question, null);
    const loadingId = appendToChat('AI', 'Analyzing evidence vectors...', null);

    try {
        const data = await ApiService.askStructuredQuestion(question, targetFolderIds, elements.caseId.value, model);
        const msgId = "msg-" + Date.now();
        chatHistory[msgId] = {
            question: question, // Keep track of the question asked
            answer: data.answer || "No conclusion drawn.",
            reasoning: data.reasoning || "No specific evidence cited.",
            isValidated: false
        };

        updateChatBubble(loadingId, data.answer, msgId);
        selectMessage(msgId);
    } catch (error) {
        console.error("AI Query Failed:", error);
        updateChatBubble(loadingId, "Connection lost to AI core: " + (error.message || "Unknown Error"), null);
    } finally {
        elements.questionInput.disabled = false;
        elements.askBtn.disabled = false;
        elements.askBtn.innerText = originalBtnText;
        elements.questionInput.focus();
    }
}

function selectMessage(msgId) {
    if (!chatHistory[msgId]) return;
    activeMessageId = msgId;
    const msgData = chatHistory[msgId];

    document.querySelectorAll('.chat-bubble').forEach(el => el.classList.remove('msg-active'));
    document.getElementById(msgId).classList.add('msg-active');

    // FIX 5: Apply the same aggressive wrapping to the Evidence Trace panel
    elements.reasonContainer.innerHTML = `
        <div class="font-mono text-blue-300 mb-2 border-b border-slate-600 pb-2">EVIDENCE TRACE:</div>
        <div class="leading-relaxed whitespace-pre-wrap min-w-0" style="word-break: break-word; overflow-wrap: anywhere;">${escapeHTML(msgData.reasoning)}</div>
    `;

    if (msgData.isValidated) {
        elements.validBtn.disabled = false;
        elements.validBtn.innerText = "✓ Validated (Click to Remove)";
        swapClass(elements.validBtn, 'bg-slate-300', 'bg-green-500');
        swapClass(elements.validBtn, 'hover:bg-green-400', 'hover:bg-red-400');
    } else {
        elements.validBtn.disabled = false;
        elements.validBtn.innerText = "Valid";
        swapClass(elements.validBtn, 'bg-green-500', 'bg-slate-300');
        swapClass(elements.validBtn, 'hover:bg-red-400', 'hover:bg-green-400');
    }
}

function handleValidateReason() {
    if (!activeMessageId || !chatHistory[activeMessageId]) return;

    const msgData = chatHistory[activeMessageId];

    // SENIOR FIX #6: Maintain provenance and allow toggling Validation status
    if (msgData.isValidated) {
        // Un-validate
        msgData.isValidated = false;
        verifiedFacts = verifiedFacts.filter(fact => fact.messageId !== activeMessageId);
    } else {
        // Validate
        msgData.isValidated = true;
        verifiedFacts.push({
            messageId: activeMessageId,
            question: msgData.question,
            answer: msgData.answer,
            reasoning: msgData.reasoning,
            validatedAt: new Date().toISOString()
        });
    }

    updateVerifiedCounter();
    selectMessage(activeMessageId); // Re-render the button state
}

function updateVerifiedCounter() {
    elements.verifiedCount.innerText = `${verifiedFacts.length} Verified Facts`;
    if (verifiedFacts.length > 0) {
        swapClass(elements.verifiedCount, 'bg-slate-400', 'bg-green-400');
    } else {
        swapClass(elements.verifiedCount, 'bg-green-400', 'bg-slate-400');
    }
}

async function handleGenerateReport() {
    if (verifiedFacts.length === 0) return CustomModal.show("Action Required", "You must validate at least one piece of evidence before generating a report.", "alert", "OK", "bg-blue-600", ()=>{});

    elements.generateReportBtn.disabled = true;
    elements.generateReportBtn.innerText = "Synthesizing...";
    elements.reportContainer.innerHTML = `<div class="animate-pulse text-blue-400 text-center mt-10">Cross-referencing verified facts to generate narrative...</div>`;

    try {
        const model = elements.modelSelector.value;
        const evidenceStrings = verifiedFacts.map(fact => `Q: ${fact.question} | A: ${fact.answer} | Cite: ${fact.reasoning}`);
        const data = await ApiService.generateReport(evidenceStrings, elements.caseId.value, model);

        // FIX 6: Apply aggressive wrapping to the Report Container
        elements.reportContainer.innerHTML = `<div class="leading-relaxed whitespace-pre-wrap min-w-0" style="word-break: break-word; overflow-wrap: anywhere;">${escapeHTML(data.report)}</div>`;
    } catch (e) {
        console.error("Report generation failed:", e);
        elements.reportContainer.innerHTML = `<div class="text-red-400">Failed to generate report: ${escapeHTML(e.message)}</div>`;
    } finally {
        elements.generateReportBtn.disabled = false;
        elements.generateReportBtn.innerText = "Go (Generate)";
    }
}

function appendToChat(role, message, msgId) {
    const isAI = role === 'AI';
    const tempId = msgId || "temp-" + Date.now();
    const msgDiv = document.createElement('div');

    // Row wrapper ensures the bubble aligns left or right
    msgDiv.className = `flex ${isAI ? 'justify-start' : 'justify-end'} w-full mb-3 px-1`;

    const clickHandler = isAI ? `onclick="selectMessage('${tempId}')" style="cursor: pointer;"` : "";

    // Generate an inline timestamp (e.g., "7:27 PM")
    const timeString = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    // WhatsApp Dark Mode Styling Logic
    // isAI (Receiver): Slate-700 background, rounded with a sharp top-left corner
    // Detective (Sender): WhatsApp teal (#005c4b), rounded with a sharp top-right corner
    const bubbleStyle = isAI
        ? "bg-slate-700 text-slate-200 rounded-2xl rounded-tl-sm border border-slate-600 hover:border-blue-400 transition-colors shadow-sm"
        : "bg-[#005c4b] text-[#e9edef] rounded-2xl rounded-tr-sm shadow-sm";

    // AI gets a colored name tag at the top of the bubble
    const nameTag = isAI
        ? `<span class="text-[11px] font-bold text-[#53bdeb] mb-0.5 tracking-wide">AI CORE</span>`
        : ``;

    // Time and Read Receipts inline at the bottom right
    const timeTag = isAI
        ? `<span class="text-[10px] text-slate-400">${timeString}</span>`
        : `<span class="text-[10px] text-[#8696a0]">${timeString}</span><span class="text-[#53bdeb] text-xs ml-1 tracking-tighter leading-none">✓✓</span>`;

    // THE CORE FIX: 'w-fit' shrinks the box to perfectly hug the text. 'max-w-[85%]' stops it from growing off-screen.
    msgDiv.innerHTML = `
        <div id="${tempId}" ${clickHandler} class="chat-bubble flex flex-col w-fit max-w-[85%] px-3 pt-2 pb-1.5 ${bubbleStyle}">
            ${nameTag}
            <div class="chat-text text-[14px] leading-relaxed whitespace-pre-wrap" style="word-break: break-word; overflow-wrap: anywhere;">${escapeHTML(message)}</div>
            <div class="flex justify-end items-end gap-1 mt-1 h-3 shrink-0">
                ${timeTag}
            </div>
        </div>
    `;

    elements.chatWindow.appendChild(msgDiv);
    elements.chatWindow.scrollTop = elements.chatWindow.scrollHeight;
    return tempId;
}

function updateChatBubble(elementId, newText, newRealId) {
    const el = document.getElementById(elementId);
    if (!el) return;
    if (newRealId) {
        el.id = newRealId;
        el.setAttribute('onclick', `selectMessage('${newRealId}')`);
    }
    // Update the text while preserving the WhatsApp timestamps
    el.querySelector('.chat-text').innerText = newText;
    elements.chatWindow.scrollTop = elements.chatWindow.scrollHeight;
}