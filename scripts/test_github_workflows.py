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
        self.assertIn("mkdir -p build/ci-logs", text)
        self.assertIn("set -o pipefail", text)
        self.assertIn("./gradlew build --console=plain --no-daemon 2>&1 | tee build/ci-logs/build.log", text)
        self.assertIn("./gradlew runGameTestServer --console=plain --no-daemon 2>&1 | tee build/ci-logs/gametest.log", text)
        self.assertIn("./gradlew runRecipeAddonGameTestServer --console=plain --no-daemon 2>&1 | tee build/ci-logs/recipe-addon-gametest.log", text)
        self.assertIn("./gradlew runMekanismGameTestServer --console=plain --no-daemon 2>&1 | tee build/ci-logs/mekanism-gametest.log", text)
        self.assertIn("PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts 2>&1 | tee build/ci-logs/python-unittest.log", text)
        self.assertIn("./gradlew runData --console=plain --no-daemon 2>&1 | tee build/ci-logs/datagen.log", text)
        self.assertIn("git status --porcelain -- src/generated/resources src/main/resources", text)
        self.assertIn("actions/upload-artifact@v7", text)
        self.assertIn("name: magic-storage-ci-logs", text)
        self.assertIn("${{ always() }}", text)
        self.assertIn("build/ci-logs/**", text)
        self.assertIn("run/logs/**", text)
        self.assertIn("build/reports/**", text)
        self.assertIn("build/libs/magic_storage-*.jar", text)
        self.assertIn("contents: read", text)
        self.assertIn("Verify minimum and latest compatible EMI releases", text)
        self.assertIn('MIN_EMI="$(sed -n \'s/^emi_version=//p\' gradle.properties)"', text)
        self.assertIn('MIN_EMI_RUNTIME="$(sed -n \'s/^emi_runtime_version=//p\' gradle.properties)"', text)
        self.assertIn('LATEST_EMI="$(python3 scripts/resolve_emi_version.py)"', text)
        self.assertIn('LATEST_EMI_RUNTIME="$(python3 scripts/resolve_emi_runtime.py "$LATEST_EMI")"', text)
        self.assertIn('-Pemi_version="$MIN_EMI"', text)
        self.assertIn('-Pemi_runtime_version="$MIN_EMI_RUNTIME"', text)
        self.assertIn('-Pemi_version="$LATEST_EMI"', text)
        self.assertIn('-Pemi_runtime_version="$LATEST_EMI_RUNTIME"', text)
        self.assertIn("build/ci-logs/emi-minimum.log", text)
        self.assertIn("build/ci-logs/emi-latest.log", text)
        self.assertIn('bash scripts/stage_emi_runtime.sh "$MIN_EMI"', text)
        self.assertIn('MIN_EMI_RUNTIME="$(python3 scripts/resolve_emi_runtime.py "$MIN_EMI")"', text)
        self.assertIn('bash scripts/stage_emi_runtime.sh "$MIN_EMI" "$MIN_EMI_RUNTIME"', text)
        self.assertIn("build/ci-logs/emi-runtime.log", text)
        self.assertNotIn("runClient", text)
        self.assertNotIn("xvfb-run", text)

    def test_release_workflow_builds_tests_checks_tag_and_publishes_jar(self):
        text = self.read_required(".github/workflows/release.yml")
        self.assertIn("name: Release", text)
        self.assertIn("'v*.*.*'", text)
        self.assertIn("contents: write", text)
        self.assertIn("actions/checkout@v7", text)
        self.assertIn("fetch-depth: 0", text)
        self.assertIn("actions/setup-java@v5", text)
        self.assertIn("gradle/actions/setup-gradle@v6", text)
        self.assertIn("grep '^mod_version=' gradle.properties", text)
        self.assertIn('"v${VERSION}" != "$TAG"', text)
        self.assertIn("./gradlew build --console=plain --no-daemon 2>&1 | tee build/ci-logs/build.log", text)
        self.assertIn("./gradlew runGameTestServer --console=plain --no-daemon 2>&1 | tee build/ci-logs/gametest.log", text)
        self.assertIn("./gradlew runRecipeAddonGameTestServer --console=plain --no-daemon 2>&1 | tee build/ci-logs/recipe-addon-gametest.log", text)
        self.assertIn("./gradlew runMekanismGameTestServer --console=plain --no-daemon 2>&1 | tee build/ci-logs/mekanism-gametest.log", text)
        self.assertIn("PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts 2>&1 | tee build/ci-logs/python-unittest.log", text)
        self.assertIn("./gradlew runData --console=plain --no-daemon 2>&1 | tee build/ci-logs/datagen.log", text)
        self.assertIn("git status --porcelain -- src/generated/resources src/main/resources", text)
        self.assertIn("Generate release notes", text)
        self.assertIn("git log --pretty='- %s (%h)'", text)
        self.assertIn("gh release create", text)
        self.assertIn("--notes-file build/release-notes.md", text)
        self.assertIn("name: magic-storage-release-logs", text)
        self.assertIn("build/libs/magic_storage-*.jar", text)
        self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", text)
        self.assertIn("Verify minimum and latest compatible EMI releases", text)
        self.assertIn('MIN_EMI="$(sed -n \'s/^emi_version=//p\' gradle.properties)"', text)
        self.assertIn('MIN_EMI_RUNTIME="$(sed -n \'s/^emi_runtime_version=//p\' gradle.properties)"', text)
        self.assertIn('LATEST_EMI="$(python3 scripts/resolve_emi_version.py)"', text)
        self.assertIn('LATEST_EMI_RUNTIME="$(python3 scripts/resolve_emi_runtime.py "$LATEST_EMI")"', text)
        self.assertIn('-Pemi_version="$MIN_EMI"', text)
        self.assertIn('-Pemi_runtime_version="$MIN_EMI_RUNTIME"', text)
        self.assertIn('-Pemi_version="$LATEST_EMI"', text)
        self.assertIn('-Pemi_runtime_version="$LATEST_EMI_RUNTIME"', text)
        self.assertIn('bash scripts/stage_emi_runtime.sh "$MIN_EMI"', text)
        self.assertIn('MIN_EMI_RUNTIME="$(python3 scripts/resolve_emi_runtime.py "$MIN_EMI")"', text)
        self.assertIn('bash scripts/stage_emi_runtime.sh "$MIN_EMI" "$MIN_EMI_RUNTIME"', text)
        self.assertIn("build/ci-logs/emi-runtime.log", text)
        self.assertNotIn("modrinth-publish", text.lower())
        self.assertNotIn("curseforge", text.lower())
        self.assertNotIn("runClient", text)
        self.assertNotIn("xvfb-run", text)

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
        self.assertIn("Prism dev / manual handoff", combined)
        self.assertIn("Visual verification owner: user", combined)
        self.assertIn("datagen drift", combined)
        self.assertIn("release notes", combined)
        self.assertIn("-o MagicStorageBot", combined)
        self.assertIn("scripts/stage_emi_runtime.sh", combined)
        self.assertIn("same Gradle command once", combined)
        self.assertIn("Modrinth / CurseForge", self.read_required("docs/roadmap.md"))
        self.assertIn("All Rights Reserved", readme)
        self.assertIn(".github/workflows/", structure)

    def test_release_examples_derive_current_mod_version(self):
        readme = self.read_required("README.md")
        notes = self.read_required("docs/notes.md")
        self.assertIn('version="$(sed -n \'s/^mod_version=//p\' gradle.properties)"', readme)
        self.assertIn('git tag "v${version}"', readme)
        self.assertIn('git push origin main "v${version}"', readme)
        self.assertIn("目前版本以 `gradle.properties` 的唯一 `mod_version` 為準", notes)

    def test_current_manual_gui_log_check_does_not_pin_historical_version_or_selftest_total(self):
        notes = self.read_required("docs/notes.md")
        manual_section = notes.split("GUI 測試項目仍由當次變更動態決定", 1)[1].split("## Reference Source", 1)[0]
        self.assertIn("SelfTest: [0-9]+ passed, 0 failed, [0-9]+ total", manual_section)
        self.assertNotRegex(manual_section, r"SelfTest: \d+ passed")
        self.assertNotRegex(manual_section, r"0\.1\.\d+ 必須重新產生 current-run artifact")

    def test_client_smoke_workflow_is_manual_only_and_uses_neoforge_runtime_client(self):
        text = self.read_required(".github/workflows/client-smoke.yml")
        self.assertIn("name: Client Smoke", text)
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("pull_request", text)
        self.assertNotIn("branches:", text)
        self.assertIn("actions/checkout@v7", text)
        self.assertIn("actions/setup-java@v5", text)
        self.assertIn("gradle/actions/setup-gradle@v6", text)
        self.assertIn("./gradlew build --console=plain --no-daemon", text)
        self.assertIn("cp build/libs/magic_storage-*.jar run/mods/", text)
        self.assertIn(
            "./gradlew stageClientSmokeSupportMods --console=plain --no-daemon",
            text,
        )
        self.assertIn(
            "cp build/client-smoke-mods/patchouli-neoforge.jar run/mods/",
            text,
        )
        self.assertIn(
            "cp build/client-smoke-mods/fusion-connected-textures.jar run/mods/",
            text,
        )
        self.assertIn(
            'python3 -m zipfile -t "run/mods/patchouli-neoforge.jar"',
            text,
        )
        self.assertIn(
            'python3 -m zipfile -t "run/mods/fusion-connected-textures.jar"',
            text,
        )
        self.assertIn('EMI_VERSION="$(python3 scripts/resolve_emi_version.py)"', text)
        self.assertIn('EMI_RUNTIME_VERSION="$(python3 scripts/resolve_emi_runtime.py "$EMI_VERSION")"', text)
        self.assertIn('bash scripts/stage_emi_runtime.sh "$EMI_VERSION" "$EMI_RUNTIME_VERSION"', text)
        self.assertNotIn('./gradlew stageEmiRuntime -Pemi_version="$EMI_VERSION"', text)
        self.assertIn('cp "build/client-smoke-mods/emi-neoforge-${EMI_VERSION}.jar" run/mods/', text)
        self.assertIn("python3 -m zipfile -t", text)
        self.assertNotIn("curl ", text)
        self.assertIn("headlesshq/mc-runtime-test@4.4.0", text)
        self.assertIn("timeout-minutes: 10", text)
        self.assertIn("mc: '1.21.1'", text)
        self.assertIn("modloader: neoforge", text)
        self.assertIn("regex: '.*neoforge.*'", text)
        self.assertIn("mc-runtime-test: neoforge", text)
        self.assertIn("xvfb: 'true'", text)
        self.assertIn("dummy-assets: 'true'", text)
        self.assertIn("headlessmc-command: '--jvm \"-Djava.awt.headless=true\"'", text)
        self.assertIn("Client Smoke is a boot/resource smoke test, not GUI layout approval", text)


if __name__ == "__main__":
    unittest.main()
