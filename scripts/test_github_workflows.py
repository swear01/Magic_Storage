from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class GitHubWorkflowTests(unittest.TestCase):
    def read_required(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.exists(), f"missing {relative_path}")
        return path.read_text()

    def test_ci_workflow_runs_full_project_verification_and_uploads_jar(self):
        text = self.read_required(".github/workflows/ci.yml")
        self.assertIn("name: CI", text)
        self.assertIn("actions/checkout@v7", text)
        self.assertIn("actions/setup-java@v5", text)
        self.assertIn("distribution: temurin", text)
        self.assertIn("java-version: '21'", text)
        self.assertIn("gradle/actions/setup-gradle@v6", text)
        self.assertIn("cache-provider: basic", text)
        self.assertIn("./gradlew build --console=plain --no-daemon", text)
        self.assertIn("./gradlew runGameTestServer --console=plain --no-daemon", text)
        self.assertIn("PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts", text)
        self.assertIn("actions/upload-artifact@v7", text)
        self.assertIn("build/libs/magic_storage-*.jar", text)
        self.assertIn("contents: read", text)

    def test_release_workflow_builds_tests_checks_tag_and_publishes_jar(self):
        text = self.read_required(".github/workflows/release.yml")
        self.assertIn("name: Release", text)
        self.assertIn("'v*.*.*'", text)
        self.assertIn("contents: write", text)
        self.assertIn("actions/checkout@v7", text)
        self.assertIn("actions/setup-java@v5", text)
        self.assertIn("gradle/actions/setup-gradle@v6", text)
        self.assertIn("grep '^mod_version=' gradle.properties", text)
        self.assertIn('"v${VERSION}" != "$TAG"', text)
        self.assertIn("./gradlew build --console=plain --no-daemon", text)
        self.assertIn("./gradlew runGameTestServer --console=plain --no-daemon", text)
        self.assertIn("PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts", text)
        self.assertIn("gh release create", text)
        self.assertIn("build/libs/magic_storage-*.jar", text)
        self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", text)

    def test_public_repo_docs_explain_ci_cd_and_manual_gui_gate(self):
        readme = self.read_required("README.md")
        notes = self.read_required("docs/notes.md")
        agents = self.read_required("AGENTS.md")
        structure = self.read_required("docs/structure.md")
        combined = "\n".join([readme, notes, agents, structure])
        self.assertIn("https://github.com/swear01/Magic_Storage", combined)
        self.assertIn("GitHub Actions", combined)
        self.assertIn("./gradlew runGameTestServer", combined)
        self.assertIn("tag `v<mod_version>`", combined)
        self.assertIn("Prism dev / Computer Use", combined)
        self.assertIn("All Rights Reserved", readme)
        self.assertIn(".github/workflows/", structure)


if __name__ == "__main__":
    unittest.main()
