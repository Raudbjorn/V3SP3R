# On-demand methodology

Selective AGPL-3.0 port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1. Seven bundled playbooks load through the audited `load_skill` command only when requested. The system prompt contains a compact catalog, not full playbook bodies. Unknown IDs fail closed, asset reads are bounded, and methodology never changes risk or transport permissions.

`python mcp-server/scripts/sync_skills.py --write` refreshes MCP copies. `--check` detects drift. The MCP server serves these synchronized reference copies. Donor examples may describe unsupported actions; the current app command catalog remains authoritative.

When adding or changing an action, update the explicit action/argument manifest and run `:app:generateExecuteCommandSchema` followed by `:app:verifyExecuteCommandSchema`. The build intentionally fails on unreviewed schema drift.
