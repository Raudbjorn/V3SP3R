from __future__ import annotations

import os
import stat
from dataclasses import dataclass
from pathlib import Path

from ..runtime import err, knowledge_root, ok


@dataclass(frozen=True)
class LoreFile:
    topic: str
    name: str
    path: Path


def _corpus_dir(root: Path) -> Path:
    return root / "knowledge"


MAX_DOC_BYTES = 512 * 1024


def _read_lore(root: Path, path: Path) -> str:
    """Read a bounded regular file using pinned, no-follow directory handles.

    Directory traversal and the final read use the same handles. A rename or
    symlink substitution cannot redirect an already validated path outside root.
    Fail closed on platforms without the required openat/no-follow primitives.
    """
    if os.open not in os.supports_dir_fd or not hasattr(os, "O_NOFOLLOW"):
        raise OSError("Secure corpus reads require POSIX openat/O_NOFOLLOW support")
    parts = path.relative_to(root).parts
    if not parts or any(part in (".", "..") for part in parts):
        raise ValueError("invalid corpus path")
    descriptors = []
    try:
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        descriptors.append(directory)
        for part in parts[:-1]:
            directory = os.open(
                part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory
            )
            descriptors.append(directory)
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        descriptors.append(fd)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_DOC_BYTES:
            raise ValueError("corpus entry must be a bounded regular file")
        chunks = bytearray()
        while len(chunks) <= MAX_DOC_BYTES:
            chunk = os.read(fd, min(65536, MAX_DOC_BYTES + 1 - len(chunks)))
            if not chunk:
                break
            chunks.extend(chunk)
        if len(chunks) > MAX_DOC_BYTES:
            raise ValueError("corpus entry exceeds size limit")
        return chunks.decode("utf-8")
    finally:
        for fd in reversed(descriptors):
            os.close(fd)


def _iter_lore(root: Path) -> list[LoreFile]:
    corpus = _corpus_dir(root)
    if not corpus.is_dir():
        return []
    out: list[LoreFile] = []
    for md in sorted(corpus.rglob("*.md")):
        try:
            _read_lore(root, md)
        except (OSError, UnicodeError, ValueError):
            continue
        rel = md.relative_to(corpus)
        parts = rel.parts
        if len(parts) == 1:
            # Root-level file (e.g. MANIFEST.md) — surface as topic "_root".
            topic = "_root"
            name = md.stem
        else:
            topic = parts[0]
            name = "/".join(parts[1:])[:-3]  # strip .md
        out.append(LoreFile(topic=topic, name=name, path=md))
    return out


def _find_lore(root: Path, topic: str, name: str) -> LoreFile | None:
    for lf in _iter_lore(root):
        if lf.topic == topic and lf.name == name:
            return lf
    return None


async def list_topics() -> dict:
    """List every topic in the corpus and the files under each.

    Corpus lives under `knowledge/` at the repo root. Each subdirectory is
    a topic (e.g. `subghz`, `marauder`, `firmware`), and each `.md` file
    inside is a named page under that topic (e.g. `subghz/protocols`).
    Read one with `read_doc(topic, name)`.
    """
    try:
        root = knowledge_root()
    except FileNotFoundError as e:
        return err(str(e), code="corpus_missing")
    topics: dict[str, list[str]] = {}
    for lf in _iter_lore(root):
        topics.setdefault(lf.topic, []).append(lf.name)
    return ok(
        {
            "root": str(_corpus_dir(root)),
            "topic_count": len(topics),
            "file_count": sum(len(v) for v in topics.values()),
            "topics": topics,
        }
    )


async def read_doc(topic: str, name: str = "README") -> dict:
    """Return the full body of one lore file.

    `topic` is a topic directory (e.g. `subghz`, `marauder`). `name` is a
    file name inside that directory without `.md` (defaults to `README`).
    Underscore and hyphen are treated interchangeably.
    """
    try:
        root = knowledge_root()
    except FileNotFoundError as e:
        return err(str(e), code="corpus_missing")
    t = "_root" if topic == "_root" else topic.lower().replace("_", "-")
    n = name.replace("_", "-")
    lf = _find_lore(root, t, n)
    if lf is None:
        return err(
            f"unknown lore file topic={topic!r} name={name!r}; try list_topics()",
            code="unknown_topic",
        )
    try:
        content = _read_lore(root, lf.path)
    except (OSError, UnicodeError, ValueError):
        return err("corpus entry changed or is unreadable", code="unreadable_doc")
    return ok(
        {
            "topic": lf.topic,
            "name": lf.name,
            "path": str(lf.path.relative_to(root)),
            "content": content,
        }
    )


async def search_docs(query: str, limit: int = 20) -> dict:
    """Substring search across the corpus (case-insensitive).

    Returns up to `limit` hits. Each hit carries topic, name, 1-indexed
    line number, and the matching line stripped of trailing whitespace.
    """
    if not query.strip() or len(query) > 1024:
        return err("query must be non-empty", code="bad_query")
    try:
        root = knowledge_root()
    except FileNotFoundError as e:
        return err(str(e), code="corpus_missing")
    if not 1 <= limit <= 100:
        return err("limit must be between 1 and 100", code="bad_limit")
    needle = query.lower()
    hits: list[dict] = []
    for lf in _iter_lore(root):
        try:
            text = _read_lore(root, lf.path)
        except (OSError, UnicodeError, ValueError):
            continue
        for i, line in enumerate(text.splitlines(), start=1):
            if needle in line.lower():
                hits.append(
                    {
                        "topic": lf.topic,
                        "name": lf.name,
                        "line": i,
                        "text": line.rstrip(),
                    }
                )
                if len(hits) >= limit:
                    return ok(hits)
    return ok(hits)
