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

    def test_deploy_copy_failure_restores_version_and_active_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            (project / "build" / "libs").mkdir(parents=True)
            mods.mkdir(parents=True)
            original_properties = "mod_id=magic_storage\nmod_version=0.1.0\n"
            (project / "gradle.properties").write_text(original_properties)
            old_jar = mods / "magic_storage-0.1.0.jar"
            old_jar.write_text("old")

            def fake_build(project_dir: Path, version: str) -> None:
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new")

            original_copy2 = deploy_prism_dev.shutil.copy2
            deploy_prism_dev.shutil.copy2 = lambda source, destination: (_ for _ in ()).throw(OSError("copy failed"))
            try:
                with self.assertRaisesRegex(OSError, "copy failed"):
                    deploy_prism_dev.deploy(project, prism_mc, build_runner=fake_build)
            finally:
                deploy_prism_dev.shutil.copy2 = original_copy2

            self.assertEqual(original_properties, (project / "gradle.properties").read_text())
            self.assertEqual("old", old_jar.read_text())
            self.assertEqual([old_jar], deploy_prism_dev.magic_storage_jars(mods))

    def test_deploy_rolls_back_when_bump_is_interrupted_after_writing_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            project.mkdir()
            mods.mkdir(parents=True)
            original_properties = "mod_id=magic_storage\nmod_version=0.1.0\n"
            properties = project / "gradle.properties"
            properties.write_text(original_properties)
            old_jar = mods / "magic_storage-0.1.0.jar"
            old_jar.write_text("old")
            original_bump = deploy_prism_dev.bump_patch_version

            def interrupted_bump(properties_path: Path) -> str:
                properties_path.write_text("mod_id=magic_storage\nmod_version=0.1.1\n")
                raise KeyboardInterrupt("interrupted after bump")

            deploy_prism_dev.bump_patch_version = interrupted_bump
            try:
                with self.assertRaisesRegex(KeyboardInterrupt, "interrupted after bump"):
                    deploy_prism_dev.deploy(project, prism_mc)
            finally:
                deploy_prism_dev.bump_patch_version = original_bump

            self.assertEqual(original_properties, properties.read_text())
            self.assertEqual("old", old_jar.read_text())
            self.assertEqual([old_jar], deploy_prism_dev.magic_storage_jars(mods))

    def test_deploy_rolls_back_version_and_active_jar_on_keyboard_interrupt_after_swap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            (project / "build" / "libs").mkdir(parents=True)
            mods.mkdir(parents=True)
            original_properties = "mod_id=magic_storage\nmod_version=0.1.0\n"
            properties = project / "gradle.properties"
            properties.write_text(original_properties)
            old_jar = mods / "magic_storage-0.1.0.jar"
            old_jar.write_text("old")

            def fake_build(project_dir: Path, version: str) -> None:
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new")

            original_jar_finder = deploy_prism_dev.magic_storage_jars
            calls = 0

            def interrupted_jar_finder(mods_dir: Path) -> list[Path]:
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise KeyboardInterrupt("interrupted after swap")
                return original_jar_finder(mods_dir)

            deploy_prism_dev.magic_storage_jars = interrupted_jar_finder
            try:
                with self.assertRaisesRegex(KeyboardInterrupt, "interrupted after swap"):
                    deploy_prism_dev.deploy(project, prism_mc, build_runner=fake_build)
            finally:
                deploy_prism_dev.magic_storage_jars = original_jar_finder

            self.assertEqual(original_properties, properties.read_text())
            self.assertEqual("old", old_jar.read_text())
            self.assertEqual([old_jar], deploy_prism_dev.magic_storage_jars(mods))

    def test_deploy_rolls_back_partial_backup_on_base_exception(self):
        class DeploymentAbort(BaseException):
            pass

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            (project / "build" / "libs").mkdir(parents=True)
            mods.mkdir(parents=True)
            original_properties = "mod_id=magic_storage\nmod_version=0.1.0\n"
            properties = project / "gradle.properties"
            properties.write_text(original_properties)
            old_jars = [
                mods / "magic_storage-0.0.9.jar",
                mods / "magic_storage-0.1.0.jar",
            ]
            for index, jar in enumerate(old_jars):
                jar.write_text(f"old-{index}")

            def fake_build(project_dir: Path, version: str) -> None:
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new")

            original_move = deploy_prism_dev.shutil.move
            calls = 0

            def interrupted_move(source, destination):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise DeploymentAbort("interrupted during backup")
                return original_move(source, destination)

            deploy_prism_dev.shutil.move = interrupted_move
            try:
                with self.assertRaisesRegex(DeploymentAbort, "interrupted during backup"):
                    deploy_prism_dev.deploy(project, prism_mc, build_runner=fake_build)
            finally:
                deploy_prism_dev.shutil.move = original_move

            self.assertEqual(original_properties, properties.read_text())
            self.assertEqual(["old-0", "old-1"], [jar.read_text() for jar in old_jars])
            self.assertEqual(old_jars, deploy_prism_dev.magic_storage_jars(mods))

if __name__ == "__main__":
    unittest.main()
