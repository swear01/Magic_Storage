#!/usr/bin/env python3
import gzip
import importlib.util
import json
import shutil
import struct
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "prepare_prism_gui_world.py"


def minimal_level_dat(level_name="New World", allow_commands=0):
    def name(value):
        data = value.encode("utf-8")
        return struct.pack(">H", len(data)) + data

    payload = bytearray()
    payload.extend(b"\x0a" + name("Data"))
    payload.extend(b"\x08" + name("LevelName") + name(level_name))
    payload.extend(b"\x01" + name("allowCommands") + struct.pack(">b", allow_commands))
    payload.extend(b"\x00")
    payload.extend(b"\x00")
    root = b"\x0a" + name("") + bytes(payload)
    return gzip.compress(root)


class PreparePrismGuiWorldTests(unittest.TestCase):
    def load_script(self):
        self.assertTrue(SCRIPT_PATH.exists(), "missing scripts/prepare_prism_gui_world.py")
        spec = importlib.util.spec_from_file_location("prepare_prism_gui_world", SCRIPT_PATH)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        return module

    def test_install_datapack_writes_known_rig_without_command_blocks(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            world_dir = Path(tmp) / "MagicStorageGuiTest"
            world_dir.mkdir()

            manifest = mod.install_datapack(world_dir)

            pack_meta = json.loads((world_dir / "datapacks/magic_storage_gui_test/pack.mcmeta").read_text())
            self.assertEqual(48, pack_meta["pack"]["pack_format"])
            self.assertEqual([0, 64, 1], manifest["targets"]["storage_terminal"]["block"])
            self.assertEqual([1, 64, 0], manifest["targets"]["crafting_terminal"]["block"])
            self.assertEqual(
                "/function magic_storage_gui_test:view_storage_terminal",
                manifest["commands"]["view_storage_terminal"],
            )
            self.assertEqual("view_storage_terminal", manifest["hotbar_views"]["1"]["function"])
            self.assertEqual("view_crafting_terminal", manifest["hotbar_views"]["2"]["function"])
            self.assertTrue(manifest["fullscreen_gate"]["required"])
            self.assertEqual("after_world_ready_before_first_gui_action", manifest["fullscreen_gate"]["when"])
            self.assertEqual("windowed_only", manifest["fullscreen_gate"]["launch_mode"])
            self.assertIn("native_fullscreen", manifest["fullscreen_gate"]["accepted_methods"])
            self.assertIn("User confirms the entire Minecraft frame is visible", manifest["fullscreen_gate"]["verify"])
            self.assertFalse(any("Computer Use" in check for check in manifest["fullscreen_gate"]["verify"]))
            self.assertIn("-o MagicStorageBot", manifest["launch_command"])

            datapack = world_dir / "datapacks/magic_storage_gui_test"
            self.assertTrue((datapack / "data/minecraft/tags/function/load.json").exists())
            self.assertTrue((datapack / "data/minecraft/tags/function/tick.json").exists())
            setup = (datapack / "data/magic_storage_gui_test/function/setup.mcfunction").read_text()
            self.assertIn("setblock 0 64 0 magic_storage:storage_core", setup)
            self.assertIn("setblock -1 64 0 magic_storage:storage_unit_t6", setup)
            self.assertIn("setblock 0 64 1 magic_storage:storage_terminal", setup)
            self.assertIn("setblock 1 64 0 magic_storage:crafting_terminal", setup)
            player_ready = (datapack / "data/magic_storage_gui_test/function/player_ready.mcfunction").read_text()
            self.assertIn("give @s minecraft:coal 3", player_ready)
            self.assertIn("give @s minecraft:blaze_rod 2", player_ready)
            self.assertIn("give @s minecraft:furnace 3", player_ready)
            self.assertIn("give @s minecraft:blast_furnace 1", player_ready)
            self.assertIn("give @s minecraft:smoker 1", player_ready)
            self.assertIn("give @s minecraft:campfire 1", player_ready)
            self.assertIn("give @s minecraft:brewing_stand 1", player_ready)
            self.assertIn("give @s minecraft:crafting_table 1", player_ready)
            self.assertIn("give @s minecraft:stonecutter 1", player_ready)
            self.assertIn("give @s minecraft:smithing_table 1", player_ready)
            self.assertIn("give @s minecraft:iron_axe 1", player_ready)
            self.assertIn("give @s minecraft:netherite_upgrade_smithing_template 1", player_ready)
            self.assertIn("give @s minecraft:diamond_sword 1", player_ready)
            self.assertIn("give @s minecraft:netherite_ingot 1", player_ready)

            view = (datapack / "data/magic_storage_gui_test/function/view_storage_terminal.mcfunction").read_text()
            self.assertIn("tp @s 0.5 65.0 4.5 facing 0.5 64.5 1.5", view)
            self.assertNotIn("sleep", view.lower())
            tick = (datapack / "data/magic_storage_gui_test/function/tick.mcfunction").read_text()
            self.assertIn("function magic_storage_gui_test:hotbar_views", tick)
            hotbar = (datapack / "data/magic_storage_gui_test/function/hotbar_views.mcfunction").read_text()
            self.assertIn("SelectedItemSlot:0", hotbar)
            self.assertIn("function magic_storage_gui_test:view_storage_terminal", hotbar)
            self.assertIn("SelectedItemSlot:1", hotbar)
            self.assertIn("function magic_storage_gui_test:view_crafting_terminal", hotbar)

            all_function_text = "\n".join(path.read_text() for path in datapack.rglob("*.mcfunction"))
            self.assertNotIn("command_block", all_function_text)
            self.assertNotIn("sleep", all_function_text.lower())

    def test_patch_options_sets_fast_reproducible_gui_values(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            options = Path(tmp) / "options.txt"
            options.write_text(
                "fullscreen:true\n"
                "pauseOnLostFocus:true\n"
                "guiScale:2\n"
                "key_key.use:key.mouse.right\n"
                "unrelated:kept\n"
            )

            changed = mod.patch_options(options)

            lines = dict(line.split(":", 1) for line in options.read_text().splitlines() if ":" in line)
            self.assertTrue(changed)
            self.assertEqual("false", lines["fullscreen"])
            self.assertEqual("false", lines["pauseOnLostFocus"])
            self.assertEqual("4", lines["guiScale"])
            self.assertEqual("key.keyboard.u", lines["key_key.use"])
            self.assertEqual("1280", lines["overrideWidth"])
            self.assertEqual("720", lines["overrideHeight"])
            self.assertEqual("none", lines["tutorialStep"])
            self.assertEqual("kept", lines["unrelated"])

    def test_patch_options_preserves_original_when_atomic_replace_fails(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            options = Path(tmp) / "options.txt"
            original = "fullscreen:true\nunrelated:kept\n"
            options.write_text(original)
            original_replace = Path.replace

            def failing_replace(path, target):
                if Path(target) == options:
                    raise OSError("replace failed")
                return original_replace(path, target)

            Path.replace = failing_replace
            try:
                with self.assertRaisesRegex(OSError, "replace failed"):
                    mod.patch_options(options)
            finally:
                Path.replace = original_replace

            self.assertEqual(original, options.read_text())
            self.assertEqual([options.name], sorted(path.name for path in options.parent.iterdir()))

    def test_prepare_world_recreates_only_marked_target_from_template(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            minecraft_dir = Path(tmp) / "minecraft"
            source = minecraft_dir / "saves" / "New World"
            source.mkdir(parents=True)
            (source / "level.dat").write_bytes(minimal_level_dat())
            (source / "region").mkdir()
            (minecraft_dir / "options.txt").write_text("fullscreen:true\n")

            first = mod.prepare_world(minecraft_dir)
            target = minecraft_dir / "saves" / "MagicStorageGuiTest"
            self.assertEqual(str(target.resolve()), first["world_dir"])
            self.assertTrue((target / ".magic_storage_gui_test_world").exists())
            self.assertTrue((target / "datapacks/magic_storage_gui_test/pack.mcmeta").exists())
            self.assertTrue((source / "level.dat").exists())
            self.assertEqual(
                {"LevelName": "MagicStorageGuiTest", "allowCommands": 1},
                mod.read_level_dat_summary(target / "level.dat"),
            )

            stale = target / "stale.txt"
            stale.write_text("old generated state")
            second = mod.prepare_world(minecraft_dir)
            self.assertEqual(str(target.resolve()), second["world_dir"])
            self.assertFalse(stale.exists())

            shutil.rmtree(target)
            target.mkdir()
            (target / "level.dat").write_bytes(minimal_level_dat("Personal World"))
            with self.assertRaisesRegex(RuntimeError, "not marked"):
                mod.prepare_world(minecraft_dir)

    def test_prepare_world_refuses_to_recreate_marked_target_when_open(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            minecraft_dir = Path(tmp) / "minecraft"
            source = minecraft_dir / "saves" / "New World"
            target = minecraft_dir / "saves" / "MagicStorageGuiTest"
            source.mkdir(parents=True)
            target.mkdir(parents=True)
            (source / "level.dat").write_bytes(minimal_level_dat())
            (target / "level.dat").write_bytes(minimal_level_dat("MagicStorageGuiTest", 1))
            (target / ".magic_storage_gui_test_world").write_text("generated")
            stale = target / "stale.txt"
            stale.write_text("keep")
            (minecraft_dir / "options.txt").write_text("fullscreen:true\n")

            original_checker = mod.world_has_open_files
            mod.world_has_open_files = lambda path: path == target.resolve()
            try:
                with self.assertRaisesRegex(RuntimeError, "appears to be open"):
                    mod.prepare_world(minecraft_dir)
            finally:
                mod.world_has_open_files = original_checker
            self.assertEqual("keep", stale.read_text())

    def test_copy_template_rejects_same_source_and_target_before_mutation(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            world = Path(tmp) / "World"
            world.mkdir()
            (world / mod.MARKER_FILE).write_text("generated")
            sentinel = world / "sentinel.txt"
            sentinel.write_text("keep")

            with self.assertRaisesRegex(RuntimeError, "different directories"):
                mod._copy_template_world(world, world)

            self.assertEqual("keep", sentinel.read_text())

    def test_copy_failure_preserves_previous_generated_target(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "Source"
            target = root / "Target"
            source.mkdir()
            target.mkdir()
            (source / "level.dat").write_bytes(minimal_level_dat())
            (target / mod.MARKER_FILE).write_text("generated")
            sentinel = target / "sentinel.txt"
            sentinel.write_text("keep")
            original_copytree = mod.shutil.copytree
            original_checker = mod.world_has_open_files
            mod.shutil.copytree = lambda source_path, target_path: (_ for _ in ()).throw(OSError("copy failed"))
            mod.world_has_open_files = lambda path: False
            try:
                with self.assertRaisesRegex(OSError, "copy failed"):
                    mod._copy_template_world(source, target)
            finally:
                mod.shutil.copytree = original_copytree
                mod.world_has_open_files = original_checker

            self.assertEqual("keep", sentinel.read_text())
            self.assertTrue((target / mod.MARKER_FILE).exists())

    def test_prepare_world_preserves_existing_target_when_source_level_dat_is_invalid(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            minecraft_dir = Path(tmp) / "minecraft"
            source = minecraft_dir / "saves" / "New World"
            target = minecraft_dir / "saves" / "MagicStorageGuiTest"
            source.mkdir(parents=True)
            target.mkdir()
            (source / "level.dat").write_bytes(b"not gzip nbt")
            (target / mod.MARKER_FILE).write_text("generated")
            (target / "level.dat").write_bytes(minimal_level_dat("MagicStorageGuiTest", 1))
            sentinel = target / "sentinel.txt"
            sentinel.write_text("keep")
            options = minecraft_dir / "options.txt"
            options.write_text("fullscreen:true\n")
            original_checker = mod.world_has_open_files
            mod.world_has_open_files = lambda path: False
            try:
                with self.assertRaises(gzip.BadGzipFile):
                    mod.prepare_world(minecraft_dir)
            finally:
                mod.world_has_open_files = original_checker

            self.assertTrue(sentinel.exists())
            self.assertEqual("keep", sentinel.read_text())
            self.assertEqual(
                {"LevelName": "MagicStorageGuiTest", "allowCommands": 1},
                mod.read_level_dat_summary(target / "level.dat"),
            )
            self.assertEqual("fullscreen:true\n", options.read_text())

    def test_prepare_world_preserves_existing_target_when_manifest_install_fails(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            minecraft_dir = Path(tmp) / "minecraft"
            source = minecraft_dir / "saves" / "New World"
            target = minecraft_dir / "saves" / "MagicStorageGuiTest"
            source.mkdir(parents=True)
            target.mkdir()
            (source / "level.dat").write_bytes(minimal_level_dat())
            (target / mod.MARKER_FILE).write_text("generated")
            sentinel = target / "sentinel.txt"
            sentinel.write_text("keep")
            options = minecraft_dir / "options.txt"
            options.write_text("fullscreen:true\n")
            original_checker = mod.world_has_open_files
            original_installer = mod.install_datapack
            mod.world_has_open_files = lambda path: False
            mod.install_datapack = lambda world_dir: (_ for _ in ()).throw(OSError("manifest failed"))
            try:
                with self.assertRaisesRegex(OSError, "manifest failed"):
                    mod.prepare_world(minecraft_dir)
            finally:
                mod.install_datapack = original_installer
                mod.world_has_open_files = original_checker

            self.assertTrue(sentinel.exists())
            self.assertEqual("keep", sentinel.read_text())
            self.assertEqual("fullscreen:true\n", options.read_text())

    def test_gui_docs_require_fullscreen_before_gui_actions(self):
        notes = (ROOT / "docs" / "notes.md").read_text()
        self.assertIn("全螢幕 gate", notes)
        self.assertIn("所有 GUI 測試都必須先通過全螢幕 gate", notes)
        self.assertIn("任何 `u`、hotbar、點擊、滾輪、截圖前", notes)


if __name__ == "__main__":
    unittest.main()
