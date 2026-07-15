import hashlib
import tempfile
import unittest
from pathlib import Path

import deploy_prism_dev

class DeployPrismDevTests(unittest.TestCase):
    FUSION_BYTES = b"official fusion test artifact"

    def deploy(self, project: Path, prism_mc: Path, build_runner):
        original_hash = getattr(deploy_prism_dev, "FUSION_SHA512", None)
        deploy_prism_dev.FUSION_SHA512 = hashlib.sha512(self.FUSION_BYTES).hexdigest()

        def fake_download(url: str, destination: Path) -> None:
            destination.write_bytes(self.FUSION_BYTES)

        try:
            return deploy_prism_dev.deploy(
                project,
                prism_mc,
                build_runner=build_runner,
                fusion_downloader=fake_download,
            )
        finally:
            if original_hash is None:
                del deploy_prism_dev.FUSION_SHA512
            else:
                deploy_prism_dev.FUSION_SHA512 = original_hash

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

            result = self.deploy(project, prism_mc, fake_build)

            self.assertEqual(result.version, "0.1.1")
            self.assertEqual((project / "gradle.properties").read_text(), "mod_id=magic_storage\nmod_version=0.1.1\n")
            self.assertEqual((mods / "magic_storage-0.1.1.jar").read_text(), "new")
            self.assertEqual(sorted(p.name for p in mods.glob("magic_storage-*.jar")), ["magic_storage-0.1.1.jar"])
            self.assertEqual((mods / "Patchouli.jar").read_text(), "patchouli")
            self.assertEqual(len(result.backups), 1)
            self.assertFalse((mods / "magic_storage-0.1.0.jar").exists())
            self.assertTrue(result.backups[0].exists())
            self.assertEqual(result.backups[0].read_text(), "old")
            self.assertEqual(
                self.FUSION_BYTES,
                (mods / deploy_prism_dev.FUSION_FILENAME).read_bytes(),
            )
            self.assertEqual(
                [deploy_prism_dev.FUSION_FILENAME],
                [path.name for path in deploy_prism_dev.fusion_jars(mods)],
            )

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
                self.deploy(project, prism_mc, failing_build)

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
                    self.deploy(project, prism_mc, fake_build)
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
                    self.deploy(project, prism_mc, deploy_prism_dev.run_gradle_build)
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
                    self.deploy(project, prism_mc, fake_build)
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
                    self.deploy(project, prism_mc, fake_build)
            finally:
                deploy_prism_dev.shutil.move = original_move

            self.assertEqual(original_properties, properties.read_text())
            self.assertEqual(["old-0", "old-1"], [jar.read_text() for jar in old_jars])
            self.assertEqual(old_jars, deploy_prism_dev.magic_storage_jars(mods))

    def test_fusion_artifact_pin_matches_the_approved_official_release(self):
        self.assertEqual("1.2.12", deploy_prism_dev.FUSION_VERSION)
        self.assertEqual(
            "fusion-1.2.12-neoforge-mc1.21.1.jar",
            deploy_prism_dev.FUSION_FILENAME,
        )
        self.assertEqual(
            "https://cdn.modrinth.com/data/p19vrgc2/versions/h2GrA0Ku/fusion-1.2.12-neoforge-mc1.21.1.jar",
            deploy_prism_dev.FUSION_URL,
        )
        self.assertEqual(
            "50604fa4125e846b659479a8bb8bcef5db47460a8185902b8655d8b12c6cc67eb3cc4c08fee45e82a6b215976bea2a480e32ce420f062cea88abe17cb362365c",
            deploy_prism_dev.FUSION_SHA512,
        )

    def test_hash_mismatch_leaves_version_and_both_active_mods_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            prism_mc = root / "prism" / "minecraft"
            mods = prism_mc / "mods"
            (project / "build" / "libs").mkdir(parents=True)
            mods.mkdir(parents=True)
            original_properties = "mod_id=magic_storage\nmod_version=0.1.0\n"
            (project / "gradle.properties").write_text(original_properties)
            old_magic = mods / "magic_storage-0.1.0.jar"
            old_fusion = mods / "fusion-1.2.11-neoforge-mc1.21.1.jar"
            old_magic.write_text("old magic")
            old_fusion.write_text("old fusion")

            def fake_build(project_dir: Path, version: str) -> None:
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new")

            def tampered_download(url: str, destination: Path) -> None:
                destination.write_bytes(b"tampered")

            with self.assertRaisesRegex(RuntimeError, "Fusion SHA-512 mismatch"):
                deploy_prism_dev.deploy(
                    project,
                    prism_mc,
                    build_runner=fake_build,
                    fusion_downloader=tampered_download,
                )

            self.assertEqual(original_properties, (project / "gradle.properties").read_text())
            self.assertTrue(old_magic.is_file(), "old Magic Storage jar was stranded outside mods")
            self.assertTrue(old_fusion.is_file(), "old Fusion jar was stranded outside mods")
            self.assertEqual("old magic", old_magic.read_text())
            self.assertEqual("old fusion", old_fusion.read_text())
            self.assertEqual([old_magic], deploy_prism_dev.magic_storage_jars(mods))
            self.assertEqual([old_fusion], deploy_prism_dev.fusion_jars(mods))

    def test_base_exception_after_both_swaps_rolls_back_magic_fusion_and_version(self):
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
            (project / "gradle.properties").write_text(original_properties)
            old_magic = mods / "magic_storage-0.1.0.jar"
            old_fusion = mods / "fusion-1.2.11-neoforge-mc1.21.1.jar"
            old_magic.write_text("old magic")
            old_fusion.write_text("old fusion")

            def fake_build(project_dir: Path, version: str) -> None:
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new magic")

            original_hash = getattr(deploy_prism_dev, "FUSION_SHA512", None)
            deploy_prism_dev.FUSION_SHA512 = hashlib.sha512(self.FUSION_BYTES).hexdigest()

            def fake_download(url: str, destination: Path) -> None:
                destination.write_bytes(self.FUSION_BYTES)

            original_finder = deploy_prism_dev.magic_storage_jars

            def interrupted_finder(mods_dir: Path):
                expected_magic = mods_dir / "magic_storage-0.1.1.jar"
                expected_fusion = mods_dir / deploy_prism_dev.FUSION_FILENAME
                if expected_magic.exists() and expected_fusion.exists():
                    raise DeploymentAbort("interrupted after both swaps")
                return original_finder(mods_dir)

            deploy_prism_dev.magic_storage_jars = interrupted_finder
            try:
                with self.assertRaisesRegex(DeploymentAbort, "interrupted after both swaps"):
                    deploy_prism_dev.deploy(
                        project,
                        prism_mc,
                        build_runner=fake_build,
                        fusion_downloader=fake_download,
                    )
            finally:
                deploy_prism_dev.magic_storage_jars = original_finder
                if original_hash is None:
                    del deploy_prism_dev.FUSION_SHA512
                else:
                    deploy_prism_dev.FUSION_SHA512 = original_hash

            self.assertEqual(original_properties, (project / "gradle.properties").read_text())
            self.assertEqual("old magic", old_magic.read_text())
            self.assertEqual("old fusion", old_fusion.read_text())
            self.assertEqual([old_magic], deploy_prism_dev.magic_storage_jars(mods))
            self.assertEqual([old_fusion], deploy_prism_dev.fusion_jars(mods))

    def test_base_exception_at_backup_journal_handoff_restores_both_mods(self):
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
            (project / "gradle.properties").write_text(original_properties)
            old_magic = mods / "magic_storage-0.1.0.jar"
            old_fusion = mods / "fusion-1.2.11-neoforge-mc1.21.1.jar"
            unrelated = mods / "Patchouli.jar"
            old_magic.write_text("old magic")
            old_fusion.write_text("old fusion")
            unrelated.write_text("unrelated")

            def fake_build(project_dir: Path, version: str) -> None:
                (project_dir / "build" / "libs" / f"magic_storage-{version}.jar").write_text("new magic")

            original_hash = deploy_prism_dev.FUSION_SHA512
            deploy_prism_dev.FUSION_SHA512 = hashlib.sha512(self.FUSION_BYTES).hexdigest()

            def fake_download(url: str, destination: Path) -> None:
                destination.write_bytes(self.FUSION_BYTES)

            original_backup = deploy_prism_dev.backup_existing_jars

            def interrupted_backup(jars: list[Path], prism_minecraft_dir: Path, backups=None):
                if backups is None:
                    original_backup(jars, prism_minecraft_dir)
                else:
                    original_backup(jars, prism_minecraft_dir, backups)
                raise DeploymentAbort("interrupted at backup journal handoff")

            deploy_prism_dev.backup_existing_jars = interrupted_backup
            try:
                with self.assertRaisesRegex(DeploymentAbort, "interrupted at backup journal handoff"):
                    deploy_prism_dev.deploy(
                        project,
                        prism_mc,
                        build_runner=fake_build,
                        fusion_downloader=fake_download,
                    )
            finally:
                deploy_prism_dev.backup_existing_jars = original_backup
                deploy_prism_dev.FUSION_SHA512 = original_hash

            self.assertEqual(original_properties, (project / "gradle.properties").read_text())
            self.assertTrue(old_magic.is_file(), "old Magic Storage jar was stranded outside mods")
            self.assertTrue(old_fusion.is_file(), "old Fusion jar was stranded outside mods")
            self.assertEqual("old magic", old_magic.read_text())
            self.assertEqual("old fusion", old_fusion.read_text())
            self.assertEqual("unrelated", unrelated.read_text())
            self.assertEqual([old_magic], deploy_prism_dev.magic_storage_jars(mods))
            self.assertEqual([old_fusion], deploy_prism_dev.fusion_jars(mods))
            self.assertEqual([], list(mods.glob(".*.staging")))
            backup_root = prism_mc / "magic_storage_backups"
            self.assertFalse(backup_root.exists() and any(backup_root.iterdir()))

if __name__ == "__main__":
    unittest.main()
