import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "run_prism_gui_session.py"


class RunPrismGuiSessionTests(unittest.TestCase):
    def load_script(self):
        self.assertTrue(SCRIPT_PATH.exists(), "missing scripts/run_prism_gui_session.py")
        spec = importlib.util.spec_from_file_location("run_prism_gui_session", SCRIPT_PATH)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        return module

    def fake_prepare(self, minecraft_dir: Path, source_world: str, target_world: str):
        world_dir = minecraft_dir / "saves" / target_world
        world_dir.mkdir(parents=True, exist_ok=True)
        return {
            "world_name": target_world,
            "world_dir": str(world_dir),
            "hotbar_views": {
                "1": {"slot": 0, "function": "view_storage_terminal", "target": "storage_terminal"},
                "2": {"slot": 1, "function": "view_crafting_terminal", "target": "crafting_terminal"},
            },
            "fullscreen_gate": {"required": True, "when": "after_world_ready_before_first_gui_action"},
        }

    def fake_setup(self, instance_dir: Path, app_path: Path, wrapper_path: Path):
        class Result:
            bundle_id = "run.hapi.magicstorage.minecraftcu"
            instance_cfg_changed = True
            app_path = Path("/tmp/MagicStorageMinecraftCU.app")
            app_executable = Path("/tmp/MagicStorageMinecraftCU.app/Contents/MacOS/MagicStorageMinecraftCU")
            wrapper_path = Path("/tmp/magic_storage_minecraft_cu_wrapper.sh")
            instance_cfg = instance_dir / "instance.cfg"
        return Result()

    def test_run_session_writes_artifacts_launches_world_and_marks_manual_gui_required(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            latest_log = minecraft_dir / "logs" / "latest.log"
            latest_log.write_text("old run\nMS_GUI_TEST_READY\n")
            launched = []

            result = mod.run_session(
                scenario_name="terminal-left-rail",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                setup_wrapper_func=self.fake_setup,
                launcher=lambda command: launched.append(command),
                wait_for_log_func=lambda **kwargs: "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                timestamp_func=lambda: "20260711-010203",
            )

            self.assertTrue(result.manual_gui_required)
            self.assertEqual(["open", "-a", "Prism Launcher", "--args", "-l", "dev", "-w", "MagicStorageGuiTest"], launched[0])
            self.assertTrue(result.run_dir.name.endswith("terminal-left-rail"))
            checklist = (result.run_dir / "checklist.md").read_text()
            self.assertIn("fullscreen gate", checklist)
            self.assertIn("hotbar `1`", checklist)
            self.assertIn("hotbar `2`", checklist)
            self.assertIn("Manual GUI required: yes", checklist)
            self.assertIn("run.hapi.magicstorage.minecraftcu", checklist)
            session = json.loads((result.run_dir / "session.json").read_text())
            self.assertEqual("terminal-left-rail", session["scenario"])
            self.assertEqual("after_world_ready_before_first_gui_action", session["manifest"]["fullscreen_gate"]["when"])
            self.assertIn("SelfTest: 104 passed", (result.run_dir / "log-excerpt.log").read_text())

    def test_boot_smoke_scenario_is_non_visual_and_does_not_require_manual_gui(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            wait_calls = []
            result = mod.run_session(
                scenario_name="boot-smoke",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                setup_wrapper_func=self.fake_setup,
                launcher=lambda command: None,
                wait_for_log_func=lambda **kwargs: wait_calls.append(kwargs) or "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                timestamp_func=lambda: "20260711-010203",
            )

            self.assertFalse(result.manual_gui_required)
            self.assertEqual(300, wait_calls[0]["timeout_seconds"])
            checklist = (result.run_dir / "checklist.md").read_text()
            self.assertIn("Manual GUI required: no", checklist)
            self.assertIn("No Computer Use visual pass is required", checklist)

    def test_log_wait_ignores_stale_errors_before_cursor_and_fails_on_current_errors(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("old ERROR before this run\nMS_GUI_TEST_READY\n")
            cursor = mod.log_cursor(log)
            log.write_text(log.read_text() + "SelfTest: 104 passed\nMS_GUI_TEST_READY\n")
            text = mod.wait_for_log_patterns(
                log_path=log,
                offset=cursor,
                required_patterns=["SelfTest:", "MS_GUI_TEST_READY"],
                forbidden_patterns=["ERROR", "FATAL", "advanced_container_set_data"],
                timeout_seconds=0,
                poll_seconds=0,
                sleep_func=lambda seconds: None,
            )
            self.assertIn("SelfTest", text)

            cursor = mod.log_cursor(log)
            log.write_text(log.read_text() + "ERROR current failure\n")
            with self.assertRaisesRegex(RuntimeError, "forbidden log pattern"):
                mod.wait_for_log_patterns(
                    log_path=log,
                    offset=cursor,
                    required_patterns=["SelfTest:"],
                    forbidden_patterns=["ERROR"],
                    timeout_seconds=0,
                    poll_seconds=0,
                    sleep_func=lambda seconds: None,
                )


if __name__ == "__main__":
    unittest.main()
