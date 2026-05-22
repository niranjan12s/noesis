from mcp.server.fastmcp import FastMCP
import httpx
import json

mcp = FastMCP("Noesis-MCP")

BASE_URL = "http://localhost:8081/api/tools"


def _clean(text: str) -> str:
    """Collapse whitespace, remove pipe chars for DSL safety."""
    return text.replace('\n', ' ').replace('\r', ' ').replace('|', ',')


def _fmt_score(s: float) -> str:
    return f"{s:.1f}"


# ---------------------------------------------------------------------------
# query_graph  –  JSON array of CandidateAssertion → DSL lines
# ---------------------------------------------------------------------------
@mcp.tool()
def query_graph(text: str, depth: int = 1) -> str:
    """
    Query the semantic graph using text and expansion depth.
    Returns ranked matches as compact pipe-delimited lines:
      score|depth|raw_text
    Higher score = better match. Depth 0 = BM25 text hit, depth 1+ = graph neighbour.

    Args:
        text: The search query text.
        depth: Graph traversal depth (default: 1)
    """
    try:
        with httpx.Client() as client:
            resp = client.get(f"{BASE_URL}/query_graph", params={"text": text, "depth": depth})
            resp.raise_for_status()
            results = resp.json()
    except Exception as e:
        return f"Error: {e}"

    if not results:
        return "(no results)"

    lines = []
    for r in results:
        lines.append(f"{_fmt_score(r['finalScore'])}|{r['depth']}|{_clean(r['rawText'])}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# explain_path_by_assertion_id  –  JSON object → compact multi-line DSL
# ---------------------------------------------------------------------------
@mcp.tool()
def explain_path_by_assertion_id(assertionId: str) -> str:
    """
    Trace the graph path starting from a seed assertion.
    Returns seed triple, neighbouring nodes, traversed edges, and related assertions.

    Args:
        assertionId: UUID of the starting assertion.
    """
    try:
        with httpx.Client() as client:
            resp = client.get(f"{BASE_URL}/explain_path_by_assertion_id",
                              params={"assertionId": assertionId})
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        return f"Error: {e}"

    seed = data.get("seedAssertion", {})
    sbj = _clean(seed.get("subjectText", "?"))
    pred = _clean(seed.get("predicate", "?"))
    obj = _clean(seed.get("objectText", "?"))
    raw = _clean(seed.get("rawText", ""))

    lines = [f"SEED: {sbj} | {pred} | {obj}"]

    if raw:
        lines.append(f"TEXT: {raw}")

    seed_nodes = data.get("seedNodes", [])
    if seed_nodes:
        lines.append(f"SEED_NODES: {', '.join(seed_nodes)}")

    edges = data.get("traversedEdges", [])
    if edges:
        lines.append(f"EDGES ({len(edges)}): {', '.join(edges)}")

    neighbors = data.get("neighborNodes", [])
    if neighbors:
        lines.append(f"NEIGHBORS ({len(neighbors)}): {', '.join(neighbors)}")

    related = data.get("relatedAssertions", [])
    if related:
        lines.append(f"RELATED ({len(related)}):")
        for ra in related:
            txt = _clean(ra.get("rawText", ""))
            if txt:
                lines.append(f"  {txt}")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# get_node_neighbour  –  JSON object → compact DSL
# ---------------------------------------------------------------------------
@mcp.tool()
def get_node_neighbour(nodeId: str, depth: int = 1) -> str:
    """
    Get neighbouring nodes and edges for a specific node ID.

    Args:
        nodeId: UUID of the starting node.
        depth: Hop distance to traverse (default: 1).
    """
    try:
        with httpx.Client() as client:
            resp = client.get(f"{BASE_URL}/get_node_neighbour",
                              params={"nodeId": nodeId, "depth": depth})
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        return f"Error: {e}"

    nodes = data.get("nodes", [])
    edges = data.get("edges", [])

    lines = [f"NODES ({len(nodes)}): {', '.join(nodes)}" if nodes else "NODES: (none)"]

    if edges:
        lines.append(f"EDGES ({len(edges)}):")
        for e in edges:
            lines.append(f"  {e.get('edgeId','?')} [{e.get('fromNodeId','?')}->{e.get('toNodeId','?')}]")
    else:
        lines.append("EDGES: (none)")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# get_assertion_by_id  –  JSON object → compact single-line DSL
# ---------------------------------------------------------------------------
@mcp.tool()
def get_assertion_by_id(assertionId: str) -> str:
    """
    Get a specific assertion by its deterministic ID.

    Args:
        assertionId: UUID of the assertion.
    """
    try:
        with httpx.Client() as client:
            resp = client.get(f"{BASE_URL}/get_assertion_by_id",
                              params={"assertionId": assertionId})
            resp.raise_for_status()
            a = resp.json()
    except Exception as e:
        return f"Error: {e}"

    sbj = _clean(a.get("subjectText", "?"))
    pred = _clean(a.get("predicate", "?"))
    obj = _clean(a.get("objectText", "?"))
    raw = _clean(a.get("rawText", ""))
    doc = a.get("documentId", "?")

    line = f"{sbj} | {pred} | {obj}"
    if raw:
        line += f" | {raw}"
    line += f" | doc: {doc}"
    return line


# ---------------------------------------------------------------------------
# trigger_ingest  –  plain-text passthrough (full file path)
# ---------------------------------------------------------------------------
@mcp.tool()
def trigger_ingest(path: str) -> str:
    """
    Trigger synchronous ingestion of a file by its full path.
    Registers the document, then runs chunking, assertion extraction,
    and graph compilation immediately.

    Args:
        path: Absolute filesystem path to the document (e.g. "/home/user/docs/arch.md").
    """
    try:
        with httpx.Client() as client:
            resp = client.get(f"{BASE_URL}/trigger_ingest",
                              params={"path": path})
            resp.raise_for_status()
            return resp.text
    except Exception as e:
        return f"Error: {e}"


if __name__ == "__main__":
    mcp.run()
