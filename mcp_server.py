from mcp.server.fastmcp import FastMCP
import httpx
import json

# Initialize the MCP Server
mcp = FastMCP("SGMS-Graph-MCP")

# The URL where our Spring Boot application is running
BASE_URL = "http://localhost:8081/api/tools"

@mcp.tool()
def query_graph(text: str, depth: int = 1) -> str:
    """
    Query the semantic graph using text and expansion depth.
    Retrieves BM25 matched seed assertions and traverses the graph to the specified depth.
    
    Args:
        text: The search query text.
        depth: Graph traversal depth (default: 1)
    """
    try:
        with httpx.Client() as client:
            response = client.get(f"{BASE_URL}/query_graph", params={"text": text, "depth": depth})
            response.raise_for_status()
            return json.dumps(response.json(), indent=2)
    except Exception as e:
        return f"Error executing query_graph: {str(e)}"

@mcp.tool()
def explain_path_by_assertion_id(assertionId: str) -> str:
    """
    Get explanation of the graph path from a seed assertion.
    Returns seed nodes, traversed edges, neighbor nodes, and related assertions.
    
    Args:
        assertionId: The UUID of the starting assertion.
    """
    try:
        with httpx.Client() as client:
            response = client.get(f"{BASE_URL}/explain_path_by_assertion_id", params={"assertionId": assertionId})
            response.raise_for_status()
            return json.dumps(response.json(), indent=2)
    except Exception as e:
        return f"Error executing explain_path_by_assertion_id: {str(e)}"

@mcp.tool()
def get_node_neighbour(nodeId: str, depth: int = 1) -> str:
    """
    Get neighboring nodes and edges for a specific node ID up to the specified depth.
    
    Args:
        nodeId: The UUID of the starting node.
        depth: The hop distance to traverse (default: 1).
    """
    try:
        with httpx.Client() as client:
            response = client.get(f"{BASE_URL}/get_node_neighbour", params={"nodeId": nodeId, "depth": depth})
            response.raise_for_status()
            return json.dumps(response.json(), indent=2)
    except Exception as e:
        return f"Error executing get_node_neighbour: {str(e)}"

@mcp.tool()
def get_assertion_by_id(assertionId: str) -> str:
    """
    Get a specific assertion by its deterministic ID. Bypasses caching for traceability.
    
    Args:
        assertionId: The UUID of the assertion.
    """
    try:
        with httpx.Client() as client:
            response = client.get(f"{BASE_URL}/get_assertion_by_id", params={"assertionId": assertionId})
            response.raise_for_status()
            return json.dumps(response.json(), indent=2)
    except Exception as e:
        return f"Error executing get_assertion_by_id: {str(e)}"

if __name__ == "__main__":
    # Runs the MCP server listening on STDIO
    mcp.run()
