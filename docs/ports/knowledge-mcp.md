# Read-only knowledge MCP

Selective MIT port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1. Corpus content is reference material, including donor-only capabilities; `list_actions` describes only this branch's supported command schema. No executor, BLE connection, command submission, or database is exposed.

Install `pip install -e 'mcp-server[dev]'` in a virtual environment. Run `vesper-mcp` for stdio, or `vesper-mcp --transport sse` / `vesper-mcp --transport streamable-http` for local HTTP. HTTP defaults to loopback; use an authenticated reverse proxy before deliberate external exposure.

Tests: `cd mcp-server && pytest`. Tests cover all transport dispatches, registered read-only tools, packaged corpus resolution, corpus links, search, traversal and symlink confinement. No hardware required.

The on-demand Android skill branch supplies the canonical assets and sync check; the MCP corpus includes pinned standalone copies so this server remains useful independently.
