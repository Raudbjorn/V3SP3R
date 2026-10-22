"""Bundle the same corpus when building from a checkout or extracted sdist."""

from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface


class CustomBuildHook(BuildHookInterface):
    def initialize(self, version, build_data):
        project = Path(self.root)
        source = project / "_bundled"
        if not source.is_dir():
            source = project.parent
        for relative in ("knowledge", "docs/execute_command_schema.json", "README.md"):
            path = source / relative
            if not path.exists():
                raise FileNotFoundError(f"Missing bundled corpus input: {path}")
            build_data["force_include"][str(path)] = f"vesper_mcp/_knowledge/{relative}"
