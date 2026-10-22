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

    assert {tool.__name__ for tool in _TOOLS} == {
        "list_topics",
        "read_doc",
        "search_docs",
        "list_actions",
        "describe_action",
    }


@pytest.mark.parametrize("replacement", ["symlink", "directory", "oversize"])
async def test_changed_entry_cannot_escape_or_exceed_limit(tmp_path, monkeypatch, replacement):
    corpus = tmp_path / "knowledge" / "topic"
    corpus.mkdir(parents=True)
    doc = corpus / "README.md"
    doc.write_text("safe")
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "README.md").write_text("private secret")
    monkeypatch.setenv("VESPER_MCP_KNOWLEDGE", str(tmp_path))
    original = knowledge._find_lore

    def find_then_swap(*args):
        found = original(*args)
        if replacement == "directory":
            corpus.rename(tmp_path / "old")
            corpus.symlink_to(outside, target_is_directory=True)
        else:
            doc.unlink()
            if replacement == "symlink":
                doc.symlink_to(outside / "README.md")
            else:
                doc.write_bytes(b"x" * (knowledge.MAX_DOC_BYTES + 1))
        return found

    monkeypatch.setattr(knowledge, "_find_lore", find_then_swap)
    result = await knowledge.read_doc("topic")
    assert not result["ok"]
    assert result["error"]["code"] == "unreadable_doc"


async def test_corpus_only_override_keeps_bundled_schema(tmp_path, monkeypatch):
    from vesper_mcp.tools.schema import list_actions

    (tmp_path / "knowledge").mkdir()
    monkeypatch.setenv("VESPER_MCP_KNOWLEDGE", str(tmp_path))
    result = await list_actions()
    assert result["ok"]
    assert "list_directory" in str(result)
