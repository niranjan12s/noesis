/* Neosis - D3.js Knowledge Graph Canvas Explorer */

class GraphExplorer {
    constructor() {
        this.svg = d3.select("#graph-svg");
        this.container = d3.select("#canvas-container");
        this.zoom = null;
        this.g = null;
        this.simulation = null;
        this.nodes = [];
        this.links = [];
        
        this.init();
    }

    init() {
        const svgElement = document.getElementById("graph-svg");
        if (!svgElement) return;

        // Create main graphics container group for zooming
        this.g = this.svg.append("g");

        // Configure Zoom and Pan behavior
        this.zoom = d3.zoom()
            .scaleExtent([0.1, 4])
            .on("zoom", (event) => {
                this.g.attr("transform", event.transform);
            });
        
        this.svg.call(this.zoom);

        // Click canvas to clear active inspector selection
        this.svg.on("click", (event) => {
            if (event.target.tagName === 'svg') {
                if (window.vueApp) {
                    window.vueApp.closeInspector();
                }
            }
        });

        // Add standard marker arrow definition for directional graphs
        this.svg.append("defs").append("marker")
            .attr("id", "arrow")
            .attr("viewBox", "0 -5 10 10")
            .attr("refX", 18) // position offset from node circle
            .attr("refY", 0)
            .attr("markerWidth", 6)
            .attr("markerHeight", 6)
            .attr("orient", "auto")
            .append("path")
            .attr("d", "M0,-5L10,0L0,5")
            .attr("fill", "rgba(255, 255, 255, 0.3)");

        this.loadGraph();
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
                this.highlightNeighborhood(d.id);
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
                .attr("x1", d => d.source.x)
                .attr("y1", d => d.source.y)
                .attr("x2", d => d.target.x)
                .attr("y2", d => d.target.y);

            linkLabel
                .attr("x", d => (d.source.x + d.target.x) / 2)
                .attr("y", d => (d.source.y + d.target.y) / 2 - 4);

            node
                .attr("cx", d => d.x)
                .attr("cy", d => d.y);

            label
                .attr("x", d => d.x)
                .attr("y", d => d.y);
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
        const link = this.g.selectAll(".bulk-link").data(
            this.links.filter(l => l.isBulkPlaceholder), d => d.source.id + '-' + d.target.id
        );
        link.enter().append("line")
            .attr("class", "bulk-link")
            .attr("stroke", "#45a29e")
            .attr("stroke-width", 1)
            .attr("stroke-opacity", 0.3)
            .merge(link)
            .attr("x1", d => d.source.x)
            .attr("y1", d => d.source.y)
            .attr("x2", d => d.target.x)
            .attr("y2", d => d.target.y);
        link.exit().remove();

        const node = this.g.selectAll(".bulk-node").data(
            this.nodes.filter(n => n.isBulkPlaceholder), d => d.id
        );
        node.enter().append("circle")
            .attr("class", "bulk-node")
            .attr("r", 3)
            .attr("fill", "#66fcf1")
            .attr("opacity", 0.4)
            .merge(node)
            .attr("cx", d => d.x)
            .attr("cy", d => d.y);
        node.exit().remove();
    }

    resetZoom() {
        const svgElement = document.getElementById("graph-svg");
        if (!svgElement) return;

        const width = svgElement.clientWidth || 800;
        const height = svgElement.clientHeight || 600;

        this.svg.transition().duration(750).call(
            this.zoom.transform,
            d3.zoomIdentity.translate(0, 0).scale(1)
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
