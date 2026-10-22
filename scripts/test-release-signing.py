#!/usr/bin/env python3
"""Exercise the real Gradle signing configuration without using private settings."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIGNING_NAMES = (
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
LOCAL = dict(
    zip(SIGNING_NAMES, ("local.jks", "local-test-only", "local-key", "local-test-only"))
)
ENVIRONMENT = dict(
    zip(SIGNING_NAMES, ("env.jks", "env-test-only", "env-key", "env-test-only"))
)


class ReleaseSigningTest(unittest.TestCase):
    def test_credential_sources_are_selected_as_complete_sets(self) -> None:
        # Partial/blank environment settings must fail even with a complete local
        # set: success would silently borrow credentials from a different key.
        cases = (
            ("unsigned", {}, {}, "NONE", None),
            ("local", LOCAL, {}, "local-key", None),
            ("environment", {}, ENVIRONMENT, "env-key", None),
            ("environment overrides local", LOCAL, ENVIRONMENT, "env-key", None),
            (
                "environment ignores partial local",
                {"RELEASE_KEY_ALIAS": "local-key"},
                ENVIRONMENT,
                "env-key",
                None,
            ),
            (
                "partial environment with local",
                LOCAL,
                {"RELEASE_KEY_ALIAS": "env-key"},
                "NONE",
                "environment",
            ),
            (
                "blank environment with local",
                LOCAL,
                {"RELEASE_KEY_ALIAS": ""},
                "NONE",
                "environment",
            ),
            (
                "partial local",
                {"RELEASE_KEY_ALIAS": "local-key"},
                {},
                "NONE",
                "local.properties",
            ),
            ("blank local", {"RELEASE_KEY_ALIAS": " "}, {}, "NONE", "local.properties"),
        )
        with tempfile.TemporaryDirectory(
            prefix="vesper-signing-regression-"
        ) as directory:
            fixture = Path(directory)
            for name in (
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle.properties",
            ):
                shutil.copy2(ROOT / name, fixture / name)
            if (ROOT / "buildSrc").is_dir():
                shutil.copytree(
                    ROOT / "buildSrc",
                    fixture / "buildSrc",
                    ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"),
                )
            (fixture / "app").mkdir()
            build_script = (ROOT / "app/build.gradle.kts").read_text()
            # Query the configured Android extension, not a copy of the resolver.
            build_script += """
tasks.register("verifySigningSelection") {
    doLast {
        val actual = android.signingConfigs.findByName("release")?.keyAlias ?: "NONE"
        check(actual == providers.gradleProperty("expectedSigningAlias").get()) {
            "Unexpected signing key selection"
        }
    }
}
"""
            (fixture / "app/build.gradle.kts").write_text(build_script)
            clean_env = {
                key: value
                for key, value in os.environ.items()
                if key not in SIGNING_NAMES
            }
            for name, local, environment, alias, failure_source in cases:
                with self.subTest(name=name):
                    (fixture / "local.properties").write_text(
                        "".join(f"{key}={value}\n" for key, value in local.items())
                    )
                    result = subprocess.run(
                        [
                            str(ROOT / "gradlew"),
                            "--no-daemon",
                            "--console=plain",
                            "--project-dir",
                            str(fixture),
                            ":app:verifySigningSelection",
                            f"-PexpectedSigningAlias={alias}",
                        ],
                        env=clean_env | environment,
                        text=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        timeout=180,
                        check=False,
                    )
                    if failure_source is None:
                        self.assertEqual(result.returncode, 0, result.stdout)
                    else:
                        self.assertNotEqual(result.returncode, 0, result.stdout)
                        self.assertIn(
                            f"Incomplete release signing configuration from {failure_source}.",
                            result.stdout,
                        )
                        self.assertIn("RELEASE_STORE_PASSWORD", result.stdout)
                    print(f"PASS: {name}", flush=True)


if __name__ == "__main__":
    unittest.main()
