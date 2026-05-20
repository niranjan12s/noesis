/* Neosis - Observability Dashboard Logic */

const { createApp } = Vue;

const app = createApp({
    data() {
        return {
            currentTab: 'dashboard',
            metrics: {
                totalDocuments: 0,
                totalChunks: 0,
                totalAssertions: 0,
                totalNodes: 0,
                totalEdges: 0,
                averageLatencyMs: 0.0,
                assertionsPerDoc: 0.0,
                assertionsPerChunk: 0.0,
                failedExtractions: 0,
                estimatedTokens: 0
            },
            failedPredicates: [],
            recentEvents: [],
            selectedNode: null,
            selectedEdge: null,
            traceList: [],
            scaleChart: null,
            hoveredPredicate: null,
            activePredicatesList: [],
            showTimelineTable: false,
            documents: [],
            // Bulk mode
            bulkMode: false,
            bulkJobActive: false,
            bulkDirectoryInput: '',
            showModeModal: false,
            bulkProgress: {},
            workers: [],
            graphGrowth: { nodesPerSec: 0, edgesPerSec: 0 }
        };
    },
    methods: {
        async fetchMetrics() {
            try {
                const res = await fetch('/api/dashboard/metrics');
                if (res.ok) {
                    const data = await res.json();
                    this.metrics = data;
                    this.recentEvents = data.recentEvents || [];
                    this.renderChart(data.throughputHistory || []);
                }
            } catch (err) {
                console.error("Failed to fetch pipeline metrics:", err);
            }
        },
        async fetchFailedPredicates() {
            try {
                const res = await fetch('/api/predicates/failed');
                if (res.ok) {
                    this.failedPredicates = await res.json();
                }
            } catch (err) {
                console.error("Failed to fetch failed predicates list:", err);
            }
        },
        async fetchActivePredicates() {
            try {
                const res = await fetch('/api/predicates/active');
                if (res.ok) {
                    this.activePredicatesList = await res.json();
                }
            } catch (err) {
                console.error("Failed to fetch active predicates list:", err);
            }
        },
        async approvePredicate(name) {
            try {
                const res = await fetch(`/api/predicates/approve?name=${encodeURIComponent(name)}`, {
                    method: 'POST'
                });
                if (res.ok) {
                    console.log(`Approved predicate: ${name}`);
                    this.fetchFailedPredicates();
                    this.fetchActivePredicates();
                    this.fetchMetrics();
                    // If explorer is active, reload graph too
                    if (window.explorer && typeof window.explorer.loadGraph === 'function') {
                        window.explorer.loadGraph();
                    }
                } else {
                    alert("Approval failed: " + await res.text());
                }
            } catch (err) {
                console.error("Error approving predicate:", err);
            }
        },
        async rejectPredicate(name) {
            try {
                const res = await fetch(`/api/predicates/reject?name=${encodeURIComponent(name)}`, {
                    method: 'POST'
                });
                if (res.ok) {
                    console.log(`Rejected predicate: ${name}`);
                    this.fetchFailedPredicates();
                }
            } catch (err) {
                console.error("Error rejecting predicate:", err);
            }
        },
        async selectNode(node) {
            this.selectedNode = node;
            this.selectedEdge = null;
            this.traceList = [];
            try {
                const res = await fetch(`/api/dashboard/node/${node.id}/trace`);
                if (res.ok) {
                    this.traceList = await res.json();
                }
            } catch (err) {
                console.error("Failed to trace node ID:", node.id, err);
            }
        },
        async selectEdge(edge) {
            this.selectedEdge = edge;
            this.selectedNode = null;
            this.traceList = [];
            try {
                const res = await fetch(`/api/dashboard/edge/${edge.id}/trace`);
                if (res.ok) {
                    this.traceList = await res.json();
                }
            } catch (err) {
                console.error("Failed to trace edge ID:", edge.id, err);
            }
        },
        closeInspector() {
            this.selectedNode = null;
            this.selectedEdge = null;
            this.traceList = [];
            // Remove highlighted states on D3 Explorer if active
            if (window.explorer && typeof window.explorer.clearSelection === 'function') {
                window.explorer.clearSelection();
            }
        },
        resetExplorer() {
            if (window.explorer && typeof window.explorer.resetZoom === 'function') {
                window.explorer.resetZoom();
            }
        },
        // ── Bulk Mode Methods ──
        async toggleMode() {
            if (this.bulkMode && this.bulkJobActive) {
                alert("Cannot switch mode while bulk job is active. Stop the job first.");
                return;
            }
            this.showModeModal = true;
        },
        async confirmModeSwitch() {
            this.showModeModal = false;
            this.showWaveEffect();
            if (this.bulkMode) {
                // Switch to realtime
                const res = await fetch('/api/bulk/mode', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({mode: 'realtime'})
                });
                if (res.ok) {
                    this.bulkMode = false;
                    this.bulkJobActive = false;
                }
            } else {
                // Switch to bulk
                const res = await fetch('/api/bulk/mode', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({mode: 'bulk'})
                });
                if (res.ok) {
                    this.bulkMode = true;
                    if (this.bulkDirectoryInput) {
                        await this.startBulkJob(this.bulkDirectoryInput);
                    }
                }
            }
            this.fetchDocuments();
        },
        async startBulkJob(directory) {
            const res = await fetch('/api/bulk/start', {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({directory})
            });
            if (res.ok) {
                this.bulkJobActive = true;
                this.connectBulkSSE();
                this.fetchWorkers();
            }
        },
        async stopBulkJob() {
            const res = await fetch('/api/bulk/stop', { method: 'POST' });
            if (res.ok) {
                this.bulkJobActive = false;
                this.bulkProgress = {};
                this.workers = [];
            }
        },
        async fetchMode() {
            try {
                const res = await fetch('/api/bulk/mode');
                if (res.ok) {
                    const data = await res.json();
                    this.bulkMode = data.mode === 'bulk';
                    this.bulkJobActive = data.bulkJobActive;
                    this.bulkDirectoryInput = data.bulkDirectory || '';
                    if (this.bulkJobActive) {
                        this.connectBulkSSE();
                    }
                }
            } catch (e) {}
        },
        async fetchWorkers() {
            try {
                const res = await fetch('/api/bulk/workers');
                if (res.ok) this.workers = await res.json();
            } catch (e) {}
        },
        connectBulkSSE() {
            if (this._bulkSSE) this._bulkSSE.close();
            try {
                const es = new EventSource('/api/bulk/progress');
                es.addEventListener('bulk-progress', (e) => {
                    try { this.bulkProgress = JSON.parse(e.data); } catch (ex) {}
                });
                es.addEventListener('worker-update', (e) => {
                    try { this.workers = JSON.parse(e.data); } catch (ex) {}
                });
                es.addEventListener('graph-update', (e) => {
                    try {
                        const g = JSON.parse(e.data);
                        // Calculate per-second rates from delta
                        if (this._lastGraphUpdate) {
                            const dt = (Date.now() - this._lastGraphUpdate.time) / 1000;
                            if (dt > 0) {
                                this.graphGrowth.nodesPerSec = ((g.nodesAdded || 0) - (this._lastGraphUpdate.nodes || 0)) / dt;
                                this.graphGrowth.edgesPerSec = ((g.edgesAdded || 0) - (this._lastGraphUpdate.edges || 0)) / dt;
                            }
                        }
                        this._lastGraphUpdate = { time: Date.now(), nodes: g.nodesAdded || 0, edges: g.edgesAdded || 0 };
                        // Update D3 graph if explorer is active
                        if (window.explorer && typeof window.explorer.addNodes === 'function') {
                            window.explorer.addNodes(g.totalNodes, g.totalEdges);
                        }
                    } catch (ex) {}
                });
                es.onerror = () => { es.close(); setTimeout(() => this.connectBulkSSE(), 3000); };
                this._bulkSSE = es;
            } catch (e) {}
        },
        showWaveEffect() {
            const btn = document.querySelector('.relative button');
            if (!btn) return;
            const rect = btn.getBoundingClientRect();
            const wave = document.createElement('div');
            wave.className = 'wave-effect';
            wave.style.left = (rect.left + rect.width / 2 - 20) + 'px';
            wave.style.top = (rect.top + rect.height / 2 - 20) + 'px';
            document.body.appendChild(wave);
            setTimeout(() => wave.remove(), 800);
        },
        async uploadFiles(event) {
            const files = Array.from(event.target.files).slice(0, 5);
            if (files.length === 0) return;
            // Copy files to watch directory to trigger pipeline
            for (const file of files) {
                try {
                    const formData = new FormData();
                    formData.append('file', file);
                    await fetch('/api/documents/upload', { method: 'POST', body: formData });
                } catch (e) {
                    console.error("Upload failed:", file.name, e);
                }
            }
            this.fetchDocuments();
        },
        formatETA(seconds) {
            if (seconds <= 0) return 'Completed';
            const m = Math.floor(seconds / 60);
            const s = seconds % 60;
            return `ETA ${m}:${s.toString().padStart(2, '0')}`;
        },
        formatDecimal(val) {
            if (val === undefined || val === null || isNaN(val)) return '0.00';
            return parseFloat(val).toFixed(2);
        },
        formatTime(timestampStr) {
            if (!timestampStr) return '';
            try {
                const d = new Date(timestampStr);
                return d.toLocaleTimeString();
            } catch (e) {
                return timestampStr;
            }
        },
        getEventTextClass(type) {
            if (!type) return 'text-gray-400';
            if (type.includes('COMPLETED') || type.includes('READY') || type.includes('CREATED')) return 'text-emerald-400';
            if (type.includes('FAILED')) return 'text-rose-400';
            return 'text-blue-400';
        },
        getEventBorderClass(type) {
            if (!type) return 'border-gray-800';
            if (type.includes('COMPLETED') || type.includes('READY') || type.includes('CREATED')) return 'border-emerald-500/50';
            if (type.includes('FAILED')) return 'border-rose-500/50';
            return 'border-blue-500/50';
        },
        renderChart(history) {
            if (!history || history.length === 0) return;
            try {
                const el = document.getElementById('scaleChart');
                if (!el) return;
                // Destroy any existing chart
                if (this.scaleChart) { this.scaleChart.destroy(); this.scaleChart = null; }
                const ctx = el.getContext('2d');
                if (!ctx) return;
                if (typeof Chart === 'undefined') return;
                const labels = history.map(h => this.formatTime(h.timestamp));
                const nodes = history.map(h => h.nodes);
                const edges = history.map(h => h.edges);
                this.scaleChart = new Chart(ctx, {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [
                            { label: 'Nodes', data: nodes, borderColor: '#66fcf1', backgroundColor: 'rgba(102,252,241,0.1)', borderWidth: 2, tension: 0.3, fill: true },
                            { label: 'Edges', data: edges, borderColor: '#45a29e', borderWidth: 2, tension: 0.3, fill: false }
                        ]
                    },
                    options: {
                        responsive: true, maintainAspectRatio: false, animation: false,
                        scales: {
                            x: { ticks: { color: '#a0aec0', maxTicksLimit: 6 } },
                            y: { beginAtZero: true, ticks: { color: '#a0aec0' } }
                        },
                        plugins: { legend: { labels: { color: '#c5c6c7', font: { size: 10 } } } }
                    }
                });
            } catch (e) {
                console.error('Chart render failed:', e);
            }
        },
        async fetchDocuments() {
            try {
                const res = await fetch('/api/documents');
                if (res.ok) {
                    this.documents = await res.json();
                }
            } catch (err) {
                console.error("Failed to fetch documents:", err);
            }
        },
        async deleteDocument(id, name) {
            if (!confirm(`Mark "${name}" for deletion? It will be permanently removed after 5 minutes.`)) return;
            try {
                const res = await fetch(`/api/documents/${id}/delete`, { method: 'POST' });
                if (res.ok) {
                    this.fetchDocuments();
                } else {
                    alert("Failed: " + await res.text());
                }
            } catch (err) {
                console.error("Error marking document for deletion:", err);
            }
        },
        async restoreDocument(id, name) {
            if (!confirm(`Restore "${name}" and cancel its deletion?`)) return;
            try {
                const res = await fetch(`/api/documents/${id}/restore`, { method: 'POST' });
                if (res.ok) {
                    this.fetchDocuments();
                } else {
                    alert("Failed: " + await res.text());
                }
            } catch (err) {
                console.error("Error restoring document:", err);
            }
        },
        statusClass(status) {
            if (!status) return 'bg-gray-500/20 text-gray-400';
            if (status === 'QUERYABLE') return 'bg-emerald-500/20 text-emerald-400';
            if (status === 'FAILED_FATAL' || status === 'FAILED_RETRYABLE') return 'bg-rose-500/20 text-rose-400';
            if (status.includes('PROCESSING') || status.includes('INDEXING')) return 'bg-amber-500/20 text-amber-400';
            return 'bg-blue-500/20 text-blue-400';
        },
        documentDeleteLabel(doc) {
            if (!doc.markedForDeletionAt) return null;
            const remaining = Math.max(0, Math.floor((new Date(doc.markedForDeletionAt) - Date.now()) / 1000));
            if (remaining <= 0) return 'Deleting...';
            const m = Math.floor(remaining / 60);
            const s = remaining % 60;
            return `Restore (${m}:${s.toString().padStart(2, '0')})`;
        },
        documentTimeRemaining(doc) {
            if (!doc.markedForDeletionAt) return null;
            const remaining = Math.max(0, Math.floor((new Date(doc.markedForDeletionAt) - Date.now()) / 1000));
            const m = Math.floor(remaining / 60);
            const s = remaining % 60;
            return `Deleting in ${m}:${s.toString().padStart(2, '0')}`;
        },
        classifyPredicate(name) {
            if (!name) return 'BUSINESS_RULES';
            const upper = name.toUpperCase();
            if (upper.includes("WRITE") || upper.includes("SAVE") || upper.includes("STORE") ||
                upper.includes("PERSIST") || upper.includes("DELETE") || upper.includes("REMOVE") ||
                upper.includes("CREATE") || upper.includes("ADD") || upper.includes("UPDATE") ||
                upper.includes("READ") || upper.includes("LOAD") || upper.includes("GET") ||
                upper.includes("FETCH") || upper.includes("RETRIEVE") || upper.includes("RECORD") ||
                upper.includes("KEEP") || upper.includes("HOLD") || upper.includes("CONTAIN") ||
                upper.includes("INCLUDE") || upper.includes("INGEST")) {
                return 'DATA_LIFECYCLE';
            }
            if (upper.includes("SEND") || upper.includes("RECEIVE") || upper.includes("PUBLISH") ||
                upper.includes("CONSUME") || upper.includes("DELIVER") || upper.includes("TRANSMIT") ||
                upper.includes("NOTIFY") || upper.includes("ALERT") || upper.includes("MAIL") ||
                upper.includes("POST") || upper.includes("CONTACT") || upper.includes("SIGNAL") ||
                upper.includes("SUBSCRIBE")) {
                return 'COMMUNICATION';
            }
            if (upper.includes("RUN") || upper.includes("EXECUTE") || upper.includes("START") ||
                upper.includes("STOP") || upper.includes("LAUNCH") || upper.includes("PROCESS") ||
                upper.includes("TRIGGER") || upper.includes("PERFORM") || upper.includes("DELEGATE") ||
                upper.includes("ORCHESTRATE") || upper.includes("COORDINATE") || upper.includes("CALL") ||
                upper.includes("INVOKE") || upper.includes("ROUTE") || upper.includes("FORWARD")) {
                return 'EXECUTION_FLOW';
            }
            if (upper.includes("SECURE") || upper.includes("ENCRYPT") || upper.includes("DECRYPT") ||
                upper.includes("VALIDATE") || upper.includes("CHECK") || upper.includes("VERIFY") ||
                upper.includes("AUTHENTICATE") || upper.includes("AUTHORIZE") || upper.includes("LOG") ||
                upper.includes("MONITOR") || upper.includes("TRACK") || upper.includes("AUDIT")) {
                return 'SECURITY_AUDIT';
            }
            if (upper.includes("PARSE") || upper.includes("TRANSFORM") || upper.includes("CONVERT") ||
                upper.includes("EXTRACT") || upper.includes("INDEX") || upper.includes("NORMALISE") ||
                upper.includes("NORMALIZE") || upper.includes("MAP") || upper.includes("FORMAT") ||
                upper.includes("GENERATE") || upper.includes("PRODUCE") || upper.includes("FILTER") ||
                upper.includes("AGGREGATE") || upper.includes("SPLIT") || upper.includes("BUILD") ||
                upper.includes("DEDUPLICATE") || upper.includes("CORRELATE") || upper.includes("ANALYZE") ||
                upper.includes("DETECT") || upper.includes("EVALUATE") || upper.includes("PROPAGATE") ||
                upper.includes("VISUALIZE") || upper.includes("RENDER") || upper.includes("SCRAPE")) {
                return 'DATA_PROCESSING';
            }
            if (upper.includes("INHERIT") || upper.includes("DEPEND") || upper.includes("EXTEND") ||
                upper.includes("IMPLEMENT") || upper.includes("IMPORT") || upper.includes("EXPORT") ||
                upper.includes("LINK") || upper.includes("ATTACH") || upper.includes("MOUNT") ||
                upper.includes("BIND") || upper.includes("CONNECT") || upper.includes("INTEGRATE") ||
                upper.includes("SUPPORT") || upper.includes("USE") || upper.includes("PART_OF") ||
                upper.includes("ASSIGNED_TO") || upper.includes("BASED_ON")) {
                return 'SYSTEM_INTEGRATION';
            }
            return 'BUSINESS_RULES';
        },
        getActivePredicatesInGroup(group) {
            if (!this.activePredicatesList) return [];
            return this.activePredicatesList.filter(name => this.classifyPredicate(name) === group);
        },
        getFriendlyGroupName(group) {
            if (!group) return 'Business Rules';
            return group.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()).join(' ');
        },
        getGroupBadgeClass(group) {
            switch(group) {
                case 'DATA_LIFECYCLE':
                    return 'bg-amber-500/10 text-amber-300 border-amber-400/20';
                case 'COMMUNICATION':
                    return 'bg-sky-500/10 text-sky-300 border-sky-400/20';
                case 'EXECUTION_FLOW':
                    return 'bg-pink-500/10 text-pink-300 border-pink-400/20';
                case 'SECURITY_AUDIT':
                    return 'bg-rose-500/10 text-rose-300 border-rose-400/20';
                case 'DATA_PROCESSING':
                    return 'bg-purple-500/10 text-purple-300 border-purple-400/20';
                case 'SYSTEM_INTEGRATION':
                    return 'bg-emerald-500/10 text-emerald-300 border-emerald-400/20';
                default:
                    return 'bg-indigo-500/10 text-indigo-300 border-indigo-400/20';
            }
        }
    },
    watch: {
        currentTab(newVal) {
            if (newVal === 'dashboard' && this.scaleChart) {
                this.$nextTick(() => this.scaleChart.resize());
            }
        }
    },
    mounted() {
        this.fetchMetrics();
        this.fetchFailedPredicates();
        this.fetchActivePredicates();
        this.fetchMode();

        // Start real-time polling every 3 seconds
        setInterval(() => {
            this.fetchMetrics();
            this.fetchFailedPredicates();
            if (this.bulkMode && this.bulkJobActive) this.fetchWorkers();
        }, 3000);
    },
    computed: {
        bulkProgressPercent() {
            const d = this.bulkProgress.filesDiscovered || 0;
            const p = this.bulkProgress.filesProcessed || 0;
            if (d === 0) return 0;
            return Math.min(100, Math.round((p / d) * 100));
        }
    }
});

// Expose Vue Root instance to window for global coordination
window.vueApp = app.mount('#app');
