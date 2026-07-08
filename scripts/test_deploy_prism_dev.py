import tempfile
import unittest
from pathlib import Path

import deploy_prism_dev

class DeployPrismDevTests(unittest.TestCase):
    def test_deploy_bumps_patch_builds_new_jar_and_leaves_one_magic_storage_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            build_libs = project / "build" / "libs"
            project.mkdir()
            mods.mkdir(parents=True)
            build_libs.mkdir(parents=True)
            (project / "gradle.properties").write_text("mod_id=magic_storage\nmod_version=0.1.0\n")
            (mods / "magic_storage-0.1.0.jar").write_text("old")
            (mods / "Patchouli.jar").write_text("patchouli")

            def fake_build(project_dir: Path, version: str) -> None:
                self.assertEqual((project_dir / "gradle.properties").read_text(), "mod_id=magic_storage\nmod_version=0.1.1\n")
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new")

            result = deploy_prism_dev.deploy(project, prism_mc, build_runner=fake_build)

            self.assertEqual(result.version, "0.1.1")
            self.assertEqual((project / "gradle.properties").read_text(), "mod_id=magic_storage\nmod_version=0.1.1\n")
            self.assertEqual((mods / "magic_storage-0.1.1.jar").read_text(), "new")
            self.assertEqual(sorted(p.name for p in mods.glob("magic_storage-*.jar")), ["magic_storage-0.1.1.jar"])
            self.assertEqual((mods / "Patchouli.jar").read_text(), "patchouli")
            self.assertEqual(len(result.backups), 1)
            self.assertFalse((mods / "magic_storage-0.1.0.jar").exists())
            self.assertTrue(result.backups[0].exists())
            self.assertEqual(result.backups[0].read_text(), "old")

    def test_deploy_restores_version_when_build_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            project.mkdir()
            mods.mkdir(parents=True)
            original_properties = "mod_id=magic_storage\nmod_version=0.1.0\n"
            (project / "gradle.properties").write_text(original_properties)
            (mods / "magic_storage-0.1.0.jar").write_text("old")

            def failing_build(project_dir: Path, version: str) -> None:
                raise RuntimeError("build failed")

            with self.assertRaisesRegex(RuntimeError, "build failed"):
                deploy_prism_dev.deploy(project, prism_mc, build_runner=failing_build)

            self.assertEqual((project / "gradle.properties").read_text(), original_properties)
            self.assertEqual((mods / "magic_storage-0.1.0.jar").read_text(), "old")

if __name__ == "__main__":
    unittest.main()
