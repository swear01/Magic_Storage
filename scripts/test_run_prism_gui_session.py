import importlib.util
import json
import signal
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
                "7": {"slot": 6, "function": "view_texture_gallery", "target": "texture_gallery"},
                "8": {"slot": 7, "function": "home", "target": "overview"},
                "9": {"slot": 8, "function": "reset_from_hotbar", "target": "reset"},
            },
            "world_generator": {
                "type": "minecraft:flat",
                "biome": "minecraft:the_void",
                "layers": [{"height": 1, "block": "minecraft:air"}],
                "features": False,
                "lakes": False,
                "structure_overrides": [],
            },
            "fullscreen_gate": {"required": True, "when": "after_world_ready_before_first_gui_action"},
        }

    def configure_matching_deployment(self, mod, root: Path, minecraft_dir: Path) -> None:
        project_dir = root / "project"
        (project_dir / "build" / "libs").mkdir(parents=True)
        (minecraft_dir / "mods").mkdir(parents=True, exist_ok=True)
        (project_dir / "gradle.properties").write_text("mod_version=0.1.7\n")
        jar_bytes = b"matching build"
        (project_dir / "build" / "libs" / "magic_storage-0.1.7.jar").write_bytes(jar_bytes)
        (minecraft_dir / "mods" / "magic_storage-0.1.7.jar").write_bytes(jar_bytes)
        mod.DEFAULT_PROJECT_DIR = project_dir

    def process_snapshots(self, minecraft_dir: Path):
        runner_prism = (
            "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher "
            "-l dev -w MagicStorageGuiTest -o MagicStorageBot"
        )
        baseline = {
            100: (1, runner_prism),
            110: (1, "/usr/bin/python3 unrelated.py"),
        }
        current = {
            **baseline,
            200: (1, runner_prism),
            201: (200, f"/usr/bin/java -Duser.dir={minecraft_dir} net.minecraft.client.main.Main"),
            300: (1, "/usr/bin/python3 other_new_work.py"),
            400: (1, "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher -l other -w OtherWorld"),
            401: (400, "/usr/bin/java -Duser.dir=/tmp/other-instance net.minecraft.client.main.Main"),
        }
        return baseline, current

    def test_run_session_writes_artifacts_launches_world_and_marks_manual_gui_required(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            latest_log = minecraft_dir / "logs" / "latest.log"
            latest_log.write_text("old run\nMS_GUI_TEST_READY\n")
            self.configure_matching_deployment(mod, root, minecraft_dir)
            mod.snapshot_processes = lambda: {}
            launched = []
            configured = []

            result = mod.run_session(
                scenario_name="terminal-left-rail",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                configure_instance_func=lambda instance_dir: configured.append(instance_dir) or True,
                launcher=lambda command: launched.append(command),
                wait_for_log_func=lambda **kwargs: "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                timestamp_func=lambda: "20260711-010203",
            )

            self.assertTrue(result.manual_gui_required)
            self.assertEqual([(root / "instances" / "dev").resolve()], configured)
            self.assertEqual(
                "Minecraft is ready in the fixed test world. Please take over for the fullscreen visual checks.",
                result.manual_handoff_message,
            )
            self.assertEqual(["open", "-a", "Prism Launcher", "--args", "-l", "dev", "-w", "MagicStorageGuiTest", "-o", "MagicStorageBot"], launched[0])
            self.assertTrue(result.run_dir.name.endswith("terminal-left-rail"))
            checklist = (result.run_dir / "checklist.md").read_text()
            self.assertIn("-o MagicStorageBot", checklist)
            self.assertIn("fullscreen gate", checklist)
            self.assertIn("hotbar `1`", checklist)
            self.assertIn("hotbar `2`", checklist)
            self.assertIn("Manual GUI required: yes", checklist)
            self.assertIn("Visual verification owner: user", checklist)
            self.assertIn("Stop automation here and hand control to the user", checklist)
            self.assertNotIn("Computer Use bundle id", checklist)
            session = json.loads((result.run_dir / "session.json").read_text())
            self.assertEqual("terminal-left-rail", session["scenario"])
            self.assertEqual("user", session["visual_verification_owner"])
            self.assertTrue(session["manual_handoff_required"])
            self.assertEqual(result.manual_handoff_message, session["manual_handoff_message"])
            self.assertTrue(session["launch_profile"]["computer_use_wrapper_disabled"])
            self.assertTrue(session["launch_profile"]["error_console_disabled"])
            self.assertNotIn("computer_use_wrapper", session)
            self.assertEqual("after_world_ready_before_first_gui_action", session["manifest"]["fullscreen_gate"]["when"])
            self.assertIn("SelfTest: 104 passed", (result.run_dir / "log-excerpt.log").read_text())

    def test_boot_smoke_scenario_is_non_visual_and_does_not_require_manual_gui(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            self.configure_matching_deployment(mod, root, minecraft_dir)
            mod.snapshot_processes = lambda: {}
            wait_calls = []
            result = mod.run_session(
                scenario_name="boot-smoke",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                configure_instance_func=lambda instance_dir: True,
                launcher=lambda command: None,
                wait_for_log_func=lambda **kwargs: wait_calls.append(kwargs) or "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                timestamp_func=lambda: "20260711-010203",
            )

            self.assertFalse(result.manual_gui_required)
            self.assertEqual(300, wait_calls[0]["timeout_seconds"])
            checklist = (result.run_dir / "checklist.md").read_text()
            self.assertIn("Manual GUI required: no", checklist)
            self.assertIn("No visual pass is required", checklist)
            self.assertNotIn("Computer Use", checklist)

    def test_run_session_rejects_same_version_different_jar_bytes_before_launch(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project_dir = root / "project"
            minecraft_dir = root / "minecraft"
            (project_dir / "build" / "libs").mkdir(parents=True)
            (minecraft_dir / "logs").mkdir(parents=True)
            (minecraft_dir / "mods").mkdir()
            (project_dir / "gradle.properties").write_text("mod_version=0.1.7\n")
            (project_dir / "build" / "libs" / "magic_storage-0.1.7.jar").write_bytes(b"current build")
            (minecraft_dir / "mods" / "magic_storage-0.1.7.jar").write_bytes(b"stale deployed build")
            mod.DEFAULT_PROJECT_DIR = project_dir
            launched = []

            with self.assertRaisesRegex(RuntimeError, r"contents differ.*deploy_prism_dev\.py"):
                mod.run_session(
                    scenario_name="boot-smoke",
                    minecraft_dir=minecraft_dir,
                    instance_dir=root / "instances" / "dev",
                    run_root=root / "gui-runs",
                    prepare_world_func=self.fake_prepare,
                    configure_instance_func=lambda instance_dir: True,
                    launcher=lambda command: launched.append(command),
                    wait_for_log_func=lambda **kwargs: "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                    timestamp_func=lambda: "20260712-020304",
                )

            self.assertEqual([], launched)

    def test_run_session_rejects_multiple_deployed_magic_storage_jars_before_launch(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project_dir = root / "project"
            minecraft_dir = root / "minecraft"
            (project_dir / "build" / "libs").mkdir(parents=True)
            (minecraft_dir / "logs").mkdir(parents=True)
            (minecraft_dir / "mods").mkdir()
            (project_dir / "gradle.properties").write_text("mod_version=0.1.7\n")
            jar_bytes = b"current build"
            (project_dir / "build" / "libs" / "magic_storage-0.1.7.jar").write_bytes(jar_bytes)
            (minecraft_dir / "mods" / "magic_storage-0.1.7.jar").write_bytes(jar_bytes)
            (minecraft_dir / "mods" / "magic_storage-0.1.6.jar").write_bytes(b"old build")
            mod.DEFAULT_PROJECT_DIR = project_dir
            launched = []

            with self.assertRaisesRegex(RuntimeError, r"exactly one.*deploy_prism_dev\.py"):
                mod.run_session(
                    scenario_name="boot-smoke",
                    minecraft_dir=minecraft_dir,
                    instance_dir=root / "instances" / "dev",
                    run_root=root / "gui-runs",
                    prepare_world_func=self.fake_prepare,
                    configure_instance_func=lambda instance_dir: True,
                    launcher=lambda command: launched.append(command),
                    wait_for_log_func=lambda **kwargs: "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                    timestamp_func=lambda: "20260712-020305",
                )

            self.assertEqual([], launched)

    def test_boot_smoke_success_cleans_only_new_runner_processes(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            self.configure_matching_deployment(mod, root, minecraft_dir)
            baseline, current = self.process_snapshots(minecraft_dir)
            snapshots = iter([baseline, current])
            terminated = []
            mod.snapshot_processes = lambda: next(snapshots)
            mod.terminate_processes = lambda pids: terminated.append(pids)

            mod.run_session(
                scenario_name="boot-smoke",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                configure_instance_func=lambda instance_dir: True,
                launcher=lambda command: None,
                wait_for_log_func=lambda **kwargs: "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                timestamp_func=lambda: "20260712-030405",
            )

            self.assertEqual([[201, 200]], terminated)

    def test_visual_failure_before_handoff_cleans_only_new_runner_processes(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            self.configure_matching_deployment(mod, root, minecraft_dir)
            baseline, current = self.process_snapshots(minecraft_dir)
            snapshots = iter([baseline, current])
            terminated = []
            mod.snapshot_processes = lambda: next(snapshots)
            mod.terminate_processes = lambda pids: terminated.append(pids)

            with self.assertRaisesRegex(RuntimeError, "current-run log failure"):
                mod.run_session(
                    scenario_name="terminal-left-rail",
                    minecraft_dir=minecraft_dir,
                    instance_dir=root / "instances" / "dev",
                    run_root=root / "gui-runs",
                    prepare_world_func=self.fake_prepare,
                    configure_instance_func=lambda instance_dir: True,
                    launcher=lambda command: None,
                    wait_for_log_func=lambda **kwargs: (_ for _ in ()).throw(RuntimeError("current-run log failure")),
                    timestamp_func=lambda: "20260712-030406",
                )

            self.assertEqual([[201, 200]], terminated)

    def test_visual_success_preserves_new_runner_processes_for_manual_handoff(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            self.configure_matching_deployment(mod, root, minecraft_dir)
            baseline, current = self.process_snapshots(minecraft_dir)
            snapshots = iter([baseline, current])
            snapshot_calls = []
            terminated = []

            def snapshot():
                snapshot_calls.append(True)
                return next(snapshots)

            mod.snapshot_processes = snapshot
            mod.terminate_processes = lambda pids: terminated.append(pids)

            result = mod.run_session(
                scenario_name="terminal-left-rail",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                configure_instance_func=lambda instance_dir: True,
                launcher=lambda command: None,
                wait_for_log_func=lambda **kwargs: "SelfTest: 104 passed\nMS_GUI_TEST_READY\n",
                timestamp_func=lambda: "20260712-030407",
            )

            self.assertIsNotNone(result.manual_handoff_message)
            self.assertEqual(1, len(snapshot_calls))
            self.assertEqual([], terminated)

    def test_crafting_fuel_page_scenario_generates_focused_fullscreen_checklist(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            minecraft_dir = root / "minecraft"
            (minecraft_dir / "logs").mkdir(parents=True)
            result = mod.run_session(
                scenario_name="crafting-fuel-page",
                minecraft_dir=minecraft_dir,
                instance_dir=root / "instances" / "dev",
                run_root=root / "gui-runs",
                prepare_world_func=self.fake_prepare,
                configure_instance_func=lambda instance_dir: True,
                no_launch=True,
                timestamp_func=lambda: "20260712-010203",
            )

            self.assertTrue(result.manual_gui_required)
            checklist = (result.run_dir / "checklist.md").read_text()
            for expected in [
                "fullscreen gate",
                "true-void",
                "hotbar `2`",
                "hotbar `7`",
                "hotbar `8`",
                "hotbar `9`",
                "Fuel page",
                "Storage tab",
                "Craftable tab",
                "page tabs",
                "visual gap",
                "Auto",
                "Blaze Rod",
                "Oak Logs",
                "runtime burn time",
                "Charcoal",
                "zero stored",
                "EMI",
                "cursor/inventory",
                "visible outer margins",
                "frame and left rail are centered as one group",
                "process-machine slots",
                "currently registered reserve",
                "scroll its panel",
                "Stations & Axe Energy",
                "two flow rows",
                "lower-right Fuel control panel",
                "Crafting Table",
                "Stonecutter",
                "Smithing Table",
                "accept only one",
                "Axe Energy",
                "consumed immediately",
                "Unbreaking",
                "infinity marker",
                "Smithing Transform",
                "strip",
                "16×16",
                "stays at zero",
                "hover tooltip",
                "Brew Energy",
                "white focus border",
                "Available / Required",
                "Oak Log",
                "Smelting Energy",
                "same visual size",
                "no permanent rate formula",
                "no shadow",
                "representative items",
                "large item counts",
                "inside their own slot",
                "stored types / total type capacity",
                "only Storage, Craftable, and Fuel page tabs",
                "single current-value selector",
                "distribute across the available panel width",
                "×8",
                "×64",
                "Max",
                "magnifier",
                "#",
                "@",
            ]:
                self.assertIn(expected, checklist)
            self.assertNotIn("Cooking Energy", checklist)
            self.assertNotIn("Installed Machines", checklist)
            self.assertNotIn("reinstalled axe", checklist)
            self.assertNotIn("lowers its raw durability", checklist)
            self.assertNotIn("Compact Grid", checklist)
            self.assertNotIn("all five", checklist)
            self.assertNotIn("all eight", checklist)
            self.assertIn("- hotbar `7` → `texture_gallery`", checklist)
            self.assertIn("- hotbar `8` → `overview`", checklist)
            self.assertIn("- hotbar `9` → `reset`", checklist)

    def test_configure_instance_for_manual_handoff_disables_wrapper_and_error_console(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            instance_dir = Path(tmp) / "dev"
            instance_dir.mkdir()
            cfg = instance_dir / "instance.cfg"
            cfg.write_text(
                "InstanceType=OneSix\n"
                "OverrideCommands=true\n"
                "OverrideConsole=false\n"
                "ShowConsole=true\n"
                "ShowConsoleOnError=true\n"
                "AutoCloseConsole=false\n"
                "JavaPath=/tmp/java\n"
                "WrapperCommand=/tmp/magic_storage_minecraft_cu_wrapper.sh\n"
            )

            changed = mod.configure_instance_for_manual_handoff(instance_dir)

            self.assertTrue(changed)
            self.assertEqual(
                [
                    "InstanceType=OneSix",
                    "OverrideCommands=true",
                    "OverrideConsole=true",
                    "ShowConsole=false",
                    "ShowConsoleOnError=false",
                    "AutoCloseConsole=false",
                    "JavaPath=/tmp/java",
                    "WrapperCommand=",
                ],
                cfg.read_text().splitlines(),
            )

    def test_launch_command_uses_prism_offline_player_to_skip_account_refresh(self):
        mod = self.load_script()
        self.assertEqual(
            ["open", "-a", "Prism Launcher", "--args", "-l", "dev", "-w", "MagicStorageGuiTest", "-o", "MagicStorageBot"],
            mod.build_launch_command("Prism Launcher", "dev", "MagicStorageGuiTest"),
        )

    def test_snapshot_processes_parses_pid_parent_and_full_command(self):
        mod = self.load_script()

        class Result:
            stdout = (
                "  100     1 /Applications/Prism Launcher.app/Contents/MacOS/prismlauncher -l dev\n"
                "  201   100 /usr/bin/java -Duser.dir=/tmp/minecraft net.minecraft.client.main.Main\n"
            )

        processes = mod.snapshot_processes(run_func=lambda *args, **kwargs: Result())

        self.assertEqual(
            {
                100: (1, "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher -l dev"),
                201: (100, "/usr/bin/java -Duser.dir=/tmp/minecraft net.minecraft.client.main.Main"),
            },
            processes,
        )

    def test_runner_started_process_ids_include_new_prism_parent_without_launch_args(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            instance_dir = root / "instances" / "dev"
            minecraft_dir = instance_dir / "minecraft"
            baseline = {100: (1, "/usr/bin/python3 existing.py")}
            current = {
                **baseline,
                200: (1, "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"),
                201: (200, f"/usr/bin/java -Duser.dir={minecraft_dir} org.prismlauncher.EntryPoint"),
            }

            self.assertEqual(
                [201, 200],
                mod.runner_started_process_ids(
                    baseline,
                    current,
                    instance_dir,
                    minecraft_dir,
                    "dev",
                    "MagicStorageGuiTest",
                ),
            )

    def test_terminate_processes_sends_term_to_each_live_owned_pid(self):
        mod = self.load_script()
        alive = {201, 200}
        signals = []

        def kill(pid, process_signal):
            signals.append((pid, process_signal))
            alive.discard(pid)

        mod.terminate_processes(
            [201, 200],
            timeout_seconds=0,
            kill_func=kill,
            process_alive_func=lambda pid: pid in alive,
            sleep_func=lambda seconds: None,
        )

        self.assertEqual([(201, signal.SIGTERM), (200, signal.SIGTERM)], signals)

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

    def test_log_wait_reports_locked_or_sleeping_display_for_primary_monitor_failure(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("")
            cursor = mod.log_cursor(log)
            log.write_text(
                "[12Jul2026 12:34:47.026] [main/ERROR] [EARLYDISPLAY/]: Failed to find a primary monitor\n"
                "Failed to locate a primary monitor.\n"
                "glfwGetPrimaryMonitor failed.\n"
            )

            with self.assertRaisesRegex(RuntimeError, "wake and unlock the macOS display"):
                mod.wait_for_log_patterns(
                    log_path=log,
                    offset=cursor,
                    required_patterns=["SelfTest:", "MS_GUI_TEST_READY"],
                    forbidden_patterns=["ERROR", "FATAL"],
                    timeout_seconds=0,
                    poll_seconds=0,
                    sleep_func=lambda seconds: None,
                )

    def test_log_wait_allows_only_known_offline_auth_property_fetch_noise(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("")
            cursor = mod.log_cursor(log)
            log.write_text(
                "[11Jul2026 00:54:34.535] [Download-2/ERROR] [net.minecraft.client.Minecraft/]: Failed to fetch user properties\n"
                "com.mojang.authlib.exceptions.InvalidCredentialsException: Status: 401\n"
                "\tat authlib.fetchProperties(YggdrasilUserApiService.java:150)\n"
                "Caused by: com.mojang.authlib.exceptions.MinecraftClientHttpException: Status: 401\n"
                "\tat authlib.readInputStream(MinecraftClient.java:100)\n"
                "[11Jul2026 00:54:38.495] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: [MagicStorageBot] MS_GUI_TEST_READY\n"
                "[11Jul2026 00:54:34.271] [modloading-worker-0/INFO] [com.swearprom.magicstorage.magic_storage.MagicStorage/]: SelfTest: 104 passed, 0 failed, 104 total\n"
            )
            text = mod.wait_for_log_patterns(
                log_path=log,
                offset=cursor,
                required_patterns=["SelfTest:", "MS_GUI_TEST_READY"],
                forbidden_patterns=["ERROR", "Caused by"],
                timeout_seconds=0,
                poll_seconds=0,
                sleep_func=lambda seconds: None,
            )
            self.assertIn("Failed to fetch user properties", text)

            cursor = mod.log_cursor(log)
            log.write_text(log.read_text() + "[11Jul2026 00:54:39.000] [Render thread/ERROR] [other/]: real failure\n")
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

    def test_log_wait_reads_replaced_log_from_start_instead_of_stale_byte_offset(self):
        mod = self.load_script()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / "latest.log"
            log.write_text("x" * 120)
            cursor = mod.log_cursor(log)
            replacement = root / "replacement.log"
            replacement.write_text(
                "ERROR current failure before stale offset\n"
                + "y" * 130
                + "\nSelfTest: 104 passed\nMS_GUI_TEST_READY\n"
            )
            replacement.replace(log)

            with self.assertRaisesRegex(RuntimeError, "forbidden log pattern"):
                mod.wait_for_log_patterns(
                    log_path=log,
                    offset=cursor,
                    required_patterns=["SelfTest:", "MS_GUI_TEST_READY"],
                    forbidden_patterns=["ERROR"],
                    timeout_seconds=0,
                    poll_seconds=0,
                    sleep_func=lambda seconds: None,
                )


if __name__ == "__main__":
    unittest.main()
