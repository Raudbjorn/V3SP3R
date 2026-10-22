"""Real local HTTP transports, without exposing the server externally."""
import asyncio
import socket
import subprocess
import sys

import pytest
from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.client.streamable_http import streamable_http_client

@pytest.mark.parametrize("transport,path", [("sse","/sse"),("streamable-http","/mcp")])
async def test_http_client_can_inspect_actions(transport,path):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1",0))
        port=listener.getsockname()[1]
    process=subprocess.Popen([sys.executable,"-m","vesper_mcp","--transport",transport,"--host","127.0.0.1","--port",str(port)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            try:
                reader,writer=await asyncio.open_connection("127.0.0.1",port)
                writer.close()
                await writer.wait_closed()
                break
            except OSError:
                assert process.poll() is None, "server exited before accepting connections"
                await asyncio.sleep(0.05)
        else:
            pytest.fail("local HTTP server did not start")
        url=f"http://127.0.0.1:{port}{path}"
        client=sse_client(url) if transport=="sse" else streamable_http_client(url)
        async with client as streams:
            async with ClientSession(streams[0],streams[1]) as session:
                await session.initialize()
                response=await session.call_tool("list_actions",{})
                assert not response.isError
                assert "list_directory" in str(response.content)
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
