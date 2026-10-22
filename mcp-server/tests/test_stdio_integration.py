"""Exercise the real stdio process using the MCP SDK client."""
import sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_real_stdio_client_lists_and_calls_read_only_tools():
    async with stdio_client(StdioServerParameters(command=sys.executable,args=["-m","vesper_mcp"])) as (read,write):
        async with ClientSession(read,write) as session:
            await session.initialize()
            names={tool.name for tool in (await session.list_tools()).tools}
            assert names == {"list_topics","read_doc","search_docs","list_actions","describe_action"}
            response=await session.call_tool("read_doc",{"topic":"_root","name":"MANIFEST"})
            assert not response.isError
            assert "MANIFEST" in str(response.content) or "manifest" in str(response.content).lower()
