/* Noesis - D3.js Knowledge Graph Canvas Explorer */

class GraphExplorer {
    constructor() {
        this.svg = d3.select("#graph-svg");
        this.container = d3.select("#canvas-container");
        this.zoom = null;
        this.g = null;
        this.simulation = null;
        this.nodes = [];
        this.links = [];

        this.expansionLevel = 1;
        this.selectedNodeId = null;
        this.levelControl = null;
        
        this.init();
    }

    init() {
        const svgElement = document.getElementById("graph-svg");
        if (!svgElement) return;

        // Verify D3 found the SVG element in its selection
        if (!this.svg.node()) {
            console.warn("GraphExplorer: SVG element not found in D3 selection");
            return;
        }

        // Create main graphics container group for zooming
        this.g = this.svg.append("g");

        // Configure Zoom and Pan behavior
        this.zoom = d3.zoom()
            .scaleExtent([0.1, 4])
            .on("zoom", (event) => {
                const t = event.transform;
                if (t && isFinite(t.x) && isFinite(t.y) && isFinite(t.k)) {
                    this.g.attr("transform", t);
                }
            });
        
        this.svg.call(this.zoom);

        // Click canvas to clear active inspector selection
        this.svg.on("click", (event) => {
            if (event.target.tagName === 'svg') {
                this.clearLevelExpansion();
                if (window.vueApp) {
                    window.vueApp.closeInspector();
                }
            }
        });

        // Add standard marker arrow definition for directional graphs
        this.svg.append("defs").append("marker")
            .attr("id", "arrow")
            .attr("viewBox", "0 -5 10 10")
            .attr("refX", 18)
            .attr("refY", 0)
            .attr("markerWidth", 6)
            .attr("markerHeight", 6)
            .attr("orient", "auto")
            .append("path")
            .attr("d", "M0,-5L10,0L0,5")
            .attr("fill", "rgba(255, 255, 255, 0.3)");

        this.createLevelControl();
        this.loadGraph();
    }

    createLevelControl() {
        const container = document.getElementById("canvas-container");
        if (!container) return;

        this.levelControl = document.createElement("div");
        this.levelControl.id = "graph-level-control";
        this.levelControl.style.cssText = "position:absolute;bottom:24px;right:24px;display:flex;align-items:center;gap:8px;background:rgba(11,12,16,0.85);border:1px solid #45a29e;border-radius:6px;padding:8px 14px;z-index:100;";

        const label = document.createElement("span");
        label.textContent = "Level";
        label.style.cssText = "color:#c5c6c7;font-size:12px;text-transform:uppercase;letter-spacing:1px;";

        const minus = document.createElement("button");
        minus.textContent = "\u2212";
        minus.style.cssText = "background:transparent;border:1px solid #45a29e;color:#45a29e;border-radius:4px;width:28px;height:28px;cursor:pointer;font-size:16px;display:flex;align-items:center;justify-content:center;";
        minus.addEventListener("click", () => this.changeLevel(-1));

        const value = document.createElement("span");
        value.id = "graph-level-value";
        value.textContent = "1";
        value.style.cssText = "color:#66fcf1;font-size:16px;font-weight:bold;min-width:20px;text-align:center;";

        const plus = document.createElement("button");
        plus.textContent = "+";
        plus.style.cssText = "background:transparent;border:1px solid #45a29e;color:#45a29e;border-radius:4px;width:28px;height:28px;cursor:pointer;font-size:16px;display:flex;align-items:center;justify-content:center;";
        plus.addEventListener("click", () => this.changeLevel(1));

        this.levelControl.appendChild(label);
        this.levelControl.appendChild(minus);
        this.levelControl.appendChild(value);
        this.levelControl.appendChild(plus);
        container.appendChild(this.levelControl);
    }

    changeLevel(direction) {
        if (!this.selectedNodeId) return;
        const newLevel = Math.max(1, Math.min(10, this.expansionLevel + direction));
        if (newLevel === this.expansionLevel) return;
        this.expansionLevel = newLevel;
        const valueEl = document.getElementById("graph-level-value");
        if (valueEl) valueEl.textContent = newLevel;
        this.expandToLevel(this.selectedNodeId, this.expansionLevel);
    }

    expandToLevel(rootNodeId, level) {
        this.clearSelection();
        console.log("expandToLevel called", { rootNodeId, level, totalNodes: this.nodes.length, totalLinks: this.links.length });

        // Build adjacency list from current links
        const adj = new Map();
        const resolveId = (x) => (x && x.id) || x;
        this.links.forEach(link => {
            const src = resolveId(link.source);
            const tgt = resolveId(link.target);
            if (!src || !tgt) return;
            if (!adj.has(src)) adj.set(src, []);
            if (!adj.has(tgt)) adj.set(tgt, []);
            adj.get(src).push(tgt);
            adj.get(tgt).push(src);
        });

        const visited = new Set();
        let currentLevel = [rootNodeId];
        const levelNodes = new Set([rootNodeId]);
        visited.add(rootNodeId);

        for (let l = 0; l < level; l++) {
            const nextLevel = [];
            for (const nodeId of currentLevel) {
                const neighbors = adj.get(nodeId) || [];
                for (const nid of neighbors) {
                    if (!visited.has(nid)) {
                        visited.add(nid);
                        levelNodes.add(nid);
                        nextLevel.push(nid);
                    }
                }
            }
            currentLevel = nextLevel;
            console.log("expandToLevel iteration", { l, foundAtThisLevel: nextLevel.length });
        }

        console.log("expandToLevel result", { nodeCount: levelNodes.size });

        d3.select(`#node-${rootNodeId}`).classed("highlighted", true);

        this.g.selectAll(".link").each(function(l) {
            const sourceId = resolveId(l.source);
            const targetId = resolveId(l.target);
            if (levelNodes.has(sourceId) && levelNodes.has(targetId)) {
                d3.select(this).classed("highlighted", true);
            }
        });

        this.g.selectAll(".node").style("opacity", d => levelNodes.has(d.id) ? 1.0 : 0.2);
        this.g.selectAll(".link").style("opacity", function(l) {
            const sourceId = resolveId(l.source);
            const targetId = resolveId(l.target);
            return (levelNodes.has(sourceId) && levelNodes.has(targetId)) ? 1.0 : 0.12;
        });
        this.g.selectAll(".edge-label").style("opacity", function(l) {
            const sourceId = resolveId(l.source);
            const targetId = resolveId(l.target);
            return (levelNodes.has(sourceId) && levelNodes.has(targetId)) ? 1.0 : 0.08;
        });
        this.g.selectAll(".node-label").style("opacity", d => levelNodes.has(d.id) ? 1.0 : 0.15);
    }

    clearLevelExpansion() {
        this.selectedNodeId = null;
        this.expansionLevel = 1;
        const valueEl = document.getElementById("graph-level-value");
        if (valueEl) valueEl.textContent = "1";
        this.clearSelection();
        this.g.selectAll(".node").style("opacity", 1.0);
        this.g.selectAll(".link").style("opacity", 1.0);
        this.g.selectAll(".edge-label").style("opacity", 1.0);
        this.g.selectAll(".node-label").style("opacity", 1.0);
        this.g.selectAll(".node").classed("highlighted", false);
        this.g.selectAll(".link").classed("highlighted", false);
    }

    async loadGraph() {
        try {
            const res = await fetch("/api/dashboard/graph");
            if (!res.ok) throw new Error("Failed to fetch graph data");
            const data = await res.json();
            
            this.nodes = data.nodes || [];
            this.links = data.links || [];

            this.render();
        } catch (err) {
            console.error("D3 graph data loading failed:", err);
        }
    }

    render() {
        const svgElement = document.getElementById("graph-svg");
        if (!svgElement) return;
        
        const width = svgElement.clientWidth || 800;
        const height = svgElement.clientHeight || 600;

        // Clear previous SVG content except markers/defs
        this.g.selectAll("*").remove();

        // Initialize Force Simulation
        this.simulation = d3.forceSimulation(this.nodes)
            .force("link", d3.forceLink(this.links).id(d => d.id).distance(d => {
                // If either node is a highly connected hub, give it more space!
                const sourceDegree = this.links.filter(l => l.source === d.source.id || l.target === d.source.id || l.source.id === d.source.id || l.target.id === d.source.id).length;
                const targetDegree = this.links.filter(l => l.source === d.target.id || l.target === d.target.id || l.source.id === d.target.id || l.target.id === d.target.id).length;
                return 140 + Math.max(sourceDegree, targetDegree) * 15;
            }))
            .force("charge", d3.forceManyBody().strength(d => {
                // Highly connected nodes repel more to prevent tight clusters
                const degree = this.links.filter(l => l.source === d.id || l.target === d.id || l.source.id === d.id || l.target.id === d.id).length;
                return -250 - degree * 40;
            }))
            .force("center", d3.forceCenter(width / 2, height / 2))
            .force("x", d3.forceX(width / 2).strength(0.08))
            .force("y", d3.forceY(height / 2).strength(0.08))
            .force("collide", d3.forceCollide(40));

        // 1. Draw Edges (Links)
        const link = this.g.append("g")
            .selectAll(".link")
            .data(this.links)
            .enter().append("line")
            .attr("class", "link")
            .attr("id", d => `link-${d.id}`)
            .attr("marker-end", "url(#arrow)")
            .on("click", (event, d) => {
                event.stopPropagation();
                this.highlightEdge(d.id);
                if (window.vueApp) {
                    window.vueApp.selectEdge(d);
                }
            });

        // 2. Draw Edge Labels (relationship type)
        const linkLabel = this.g.append("g")
            .selectAll(".edge-label")
            .data(this.links)
            .enter().append("text")
            .attr("class", "edge-label")
            .text(d => d.label);

        // 3. Draw Nodes Group
        const node = this.g.append("g")
            .selectAll(".node")
            .data(this.nodes)
            .enter().append("circle")
            .attr("class", "node")
            .attr("id", d => `node-${d.id}`)
            .attr("r", 10)
            .attr("fill", "#0b0c10")
            .attr("stroke", "#45a29e")
            .call(this.drag(this.simulation))
            .on("click", (event, d) => {
                event.stopPropagation();
                this.selectedNodeId = d.id;
                this.expansionLevel = 1;
                const valueEl = document.getElementById("graph-level-value");
                if (valueEl) valueEl.textContent = "1";
                this.expandToLevel(d.id, 1);
                if (window.vueApp) {
                    window.vueApp.selectNode(d);
                }
            });

        // 4. Draw Node Labels (canonicalName)
        const label = this.g.append("g")
            .selectAll(".node-label")
            .data(this.nodes)
            .enter().append("text")
            .attr("class", "node-label")
            .attr("dx", 14)
            .attr("dy", 4)
            .text(d => d.name);

        // Update coordinates on simulation tick
        this.simulation.on("tick", () => {
            link
                .attr("x1", d => d.source ? d.source.x : 0)
                .attr("y1", d => d.source ? d.source.y : 0)
                .attr("x2", d => d.target ? d.target.x : 0)
                .attr("y2", d => d.target ? d.target.y : 0);

            linkLabel
                .attr("x", d => d.source && d.target ? (d.source.x + d.target.x) / 2 : 0)
                .attr("y", d => d.source && d.target ? (d.source.y + d.target.y) / 2 - 4 : 0);

            node
                .attr("cx", d => d.x || 0)
                .attr("cy", d => d.y || 0);

            label
                .attr("x", d => d.x || 0)
                .attr("y", d => d.y || 0);
        });

        // Center on render completion
        this.resetZoom();
    }

    highlightNeighborhood(nodeId) {
        this.clearSelection();

        // Highlight selected node
        d3.select(`#node-${nodeId}`).classed("highlighted", true);

        // Highlight connected links and nodes
        const connectedNodeIds = new Set([nodeId]);
        
        this.g.selectAll(".link").each(function(l) {
            if (l.source.id === nodeId || l.target.id === nodeId) {
                d3.select(this).classed("highlighted", true);
                connectedNodeIds.add(l.source.id);
                connectedNodeIds.add(l.target.id);
            }
        });

        // Dim unconnected nodes & edges
        this.g.selectAll(".node").style("opacity", d => connectedNodeIds.has(d.id) ? 1.0 : 0.25);
        this.g.selectAll(".link").style("opacity", function(l) {
            return (l.source.id === nodeId || l.target.id === nodeId) ? 1.0 : 0.15;
        });
        this.g.selectAll(".edge-label").style("opacity", function(l) {
            return (l.source.id === nodeId || l.target.id === nodeId) ? 1.0 : 0.1;
        });
        this.g.selectAll(".node-label").style("opacity", d => connectedNodeIds.has(d.id) ? 1.0 : 0.2);
    }

    highlightEdge(edgeId) {
        this.clearSelection();

        // Highlight selected edge
        const activeLink = d3.select(`#link-${edgeId}`).classed("highlighted", true);
        const sourceNodeId = activeLink.datum().source.id;
        const targetNodeId = activeLink.datum().target.id;

        // Highlight connecting nodes
        d3.select(`#node-${sourceNodeId}`).classed("highlighted", true);
        d3.select(`#node-${targetNodeId}`).classed("highlighted", true);

        // Dim everything else
        this.g.selectAll(".node").style("opacity", d => (d.id === sourceNodeId || d.id === targetNodeId) ? 1.0 : 0.25);
        this.g.selectAll(".link").style("opacity", function(l) {
            return l.id === edgeId ? 1.0 : 0.15;
        });
        this.g.selectAll(".edge-label").style("opacity", function(l) {
            return l.id === edgeId ? 1.0 : 0.1;
        });
        this.g.selectAll(".node-label").style("opacity", d => (d.id === sourceNodeId || d.id === targetNodeId) ? 1.0 : 0.2);
    }

    clearSelection() {
        this.selectedNode = window.vueApp ? window.vueApp.$data.selectedNode : null;
    }

    addNodes(totalNodes, totalEdges) {
        const currentNodes = this.nodes.length;
        if (totalNodes <= currentNodes) return;
        const toAdd = totalNodes - currentNodes;
        for (let i = 0; i < Math.min(toAdd, 5); i++) {
            this.nodes.push({
                id: 'bulk-' + (currentNodes + i),
                name: '',
                isBulkPlaceholder: true,
                x: Math.random() * 600 + 100,
                y: Math.random() * 400 + 100
            });
        }
        // Add placeholder edges to match scale
        const currentEdges = this.links.length;
        if (totalEdges > currentEdges) {
            const edgeAdd = Math.min(totalEdges - currentEdges, 3);
            for (let i = 0; i < edgeAdd; i++) {
                const s = this.nodes[Math.floor(Math.random() * this.nodes.length)];
                const t = this.nodes[Math.floor(Math.random() * this.nodes.length)];
                if (s && t && s.id !== t.id) {
                    this.links.push({ source: s.id, target: t.id, isBulkPlaceholder: true });
                }
            }
        }
        if (this.simulation) {
            this.simulation.nodes(this.nodes);
            this.simulation.force("link").links(this.links);
            this.simulation.alpha(0.5).restart();
        }
        this.renderBulkOnly();
    }

    renderBulkOnly() {
        if (!this.g) return;
        const bulkLinks = this.links.filter(l => l.isBulkPlaceholder);
        const link = this.g.selectAll(".bulk-link").data(
            bulkLinks, d => (d.source && d.source.id || '') + '-' + (d.target && d.target.id || '')
        );
        link.enter().append("line")
            .attr("class", "bulk-link")
            .attr("stroke", "#45a29e")
            .attr("stroke-width", 1)
            .attr("stroke-opacity", 0.3)
            .merge(link)
            .attr("x1", d => d.source ? d.source.x || 0 : 0)
            .attr("y1", d => d.source ? d.source.y || 0 : 0)
            .attr("x2", d => d.target ? d.target.x || 0 : 0)
            .attr("y2", d => d.target ? d.target.y || 0 : 0);
        link.exit().remove();

        const bulkNodes = this.nodes.filter(n => n.isBulkPlaceholder);
        const node = this.g.selectAll(".bulk-node").data(
            bulkNodes, d => d.id
        );
        node.enter().append("circle")
            .attr("class", "bulk-node")
            .attr("r", 3)
            .attr("fill", "#66fcf1")
            .attr("opacity", 0.4)
            .merge(node)
            .attr("cx", d => d.x || 0)
            .attr("cy", d => d.y || 0);
        node.exit().remove();
    }

    resetZoom() {
        const svgElement = document.getElementById("graph-svg");
        if (!svgElement || !this.svg.node() || !this.zoom) return;

        const width = svgElement.clientWidth;
        const height = svgElement.clientHeight;
        if (!width || !height) return;

        this.svg.transition().duration(750).call(
            this.zoom.transform,
            d3.zoomIdentity.translate(width / 2, height / 2).scale(1)
        );
    }

    drag(simulation) {
        return d3.drag()
            .on("start", (event, d) => {
                if (!event.active) simulation.alphaTarget(0.3).restart();
                d.fx = d.x;
                d.fy = d.y;
            })
            .on("drag", (event, d) => {
                d.fx = event.x;
                d.fy = event.y;
            })
            .on("end", (event, d) => {
                if (!event.active) simulation.alphaTarget(0);
                d.fx = null;
                d.fy = null;
            });
    }
}

// Initialise explorer when DOM loads
document.addEventListener("DOMContentLoaded", () => {
    window.explorer = new GraphExplorer();
});
