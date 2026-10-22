from pathlib import Path
import pytest
from vesper_mcp.tools import knowledge

async def test_root_manifest_is_readable(tmp_path, monkeypatch):
    (tmp_path / "knowledge").mkdir()
    (tmp_path / "knowledge/MANIFEST.md").write_text("root manifest")
    monkeypatch.setenv("VESPER_MCP_KNOWLEDGE", str(tmp_path))
    assert (await knowledge.read_doc("_root", "MANIFEST"))["data"]["content"] == "root manifest"

async def test_symlink_cannot_expose_outside_corpus(tmp_path, monkeypatch):
    (tmp_path / "knowledge").mkdir()
    (tmp_path / "secret.md").write_text("private secret")
    (tmp_path / "knowledge/leak.md").symlink_to(tmp_path / "secret.md")
    monkeypatch.setenv("VESPER_MCP_KNOWLEDGE", str(tmp_path))
    assert (await knowledge.search_docs("secret"))["data"] == []
    assert not (await knowledge.read_doc("_root", "leak"))["ok"]

@pytest.mark.parametrize("limit", [-1, 0, 101])
async def test_search_limits_are_bounded(limit):
    assert not (await knowledge.search_docs("test", limit))["ok"]

def test_tool_surface_is_read_only():
    from vesper_mcp.server import _TOOLS
    assert {tool.__name__ for tool in _TOOLS} == {"list_topics", "read_doc", "search_docs", "list_actions", "describe_action"}
