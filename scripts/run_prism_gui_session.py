#!/usr/bin/env python3
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable, NamedTuple

try:
    from prepare_prism_gui_world import DEFAULT_PRISM_MINECRAFT_DIR, DEFAULT_SOURCE_WORLD, DEFAULT_WORLD_NAME, prepare_world
    from setup_prism_computer_use_wrapper import DEFAULT_APP_PATH, DEFAULT_WRAPPER_PATH, setup as setup_computer_use_wrapper, result_to_json
except ModuleNotFoundError:
    from scripts.prepare_prism_gui_world import DEFAULT_PRISM_MINECRAFT_DIR, DEFAULT_SOURCE_WORLD, DEFAULT_WORLD_NAME, prepare_world
    from scripts.setup_prism_computer_use_wrapper import DEFAULT_APP_PATH, DEFAULT_WRAPPER_PATH, setup as setup_computer_use_wrapper, result_to_json

DEFAULT_INSTANCE_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev"
DEFAULT_RUN_ROOT = Path("build/gui-runs")
DEFAULT_TIMEOUT_SECONDS = 300
DEFAULT_REQUIRED_PATTERNS = ["SelfTest:", "MS_GUI_TEST_READY"]
DEFAULT_FORBIDDEN_PATTERNS = ["advanced_container_set_data", "ERROR", "FATAL", "Caused by"]

SCENARIOS = {
    "boot-smoke": {
        "description": "Launch the fixed GUI test world and verify boot/resource logs only.",
        "manual_gui_required": False,
        "hotbar_keys": [],
        "checks": [
            "No manual Computer Use visual pass is required unless this run is investigating visible UI behavior.",
            "Review log-excerpt.log for SelfTest and MS_GUI_TEST_READY.",
        ],
    },
    "terminal-left-rail": {
        "description": "Open Storage/Crafting Terminal in fullscreen and inspect terminal layout.",
        "manual_gui_required": True,
        "hotbar_keys": ["1", "2"],
        "checks": [
            "Pass the fullscreen gate before pressing hotbar/use/click/scroll.",
            "hotbar `1`, then `u`: Storage Terminal opens with left-side view buttons and no clipping.",
            "hotbar `2`, then `u`: Crafting Terminal opens with left-side view buttons and no EMI overlap.",
            "Check latest.log for no advanced_container_set_data, ERROR, FATAL, or Caused by during the current run.",
        ],
    },
    "patchouli-guide": {
        "description": "Verify Patchouli guide content in a real client when guide resources changed.",
        "manual_gui_required": True,
        "hotbar_keys": [],
        "checks": [
            "Pass the fullscreen gate before any GUI action.",
            "Open the Magic Storage Guide and confirm categories/entries render, not No Entries.",
            "Confirm log-excerpt.log includes Patchouli preloading when Patchouli is installed in the Prism dev instance.",
        ],
    },
}


class SessionResult(NamedTuple):
    scenario: str
    manual_gui_required: bool
    run_dir: Path
    launch_command: list[str]
    log_excerpt: str
    manifest: dict


def timestamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S")


def log_cursor(log_path: Path) -> int:
    return log_path.stat().st_size if log_path.exists() else 0


def read_log_since(log_path: Path, offset: int) -> str:
    if not log_path.exists():
        return ""
    size = log_path.stat().st_size
    start = 0 if size < offset else offset
    with log_path.open("rb") as handle:
        handle.seek(start)
        return handle.read().decode("utf-8", errors="replace")


def find_pattern(text: str, pattern: str) -> bool:
    return re.search(pattern, text) is not None if pattern.startswith("(?") else pattern in text


def wait_for_log_patterns(
    log_path: Path,
    offset: int,
    required_patterns: list[str],
    forbidden_patterns: list[str],
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    poll_seconds: float = 0.25,
    sleep_func: Callable[[float], None] = time.sleep,
) -> str:
    deadline = time.monotonic() + timeout_seconds
    while True:
        text = read_log_since(log_path, offset)
        for pattern in forbidden_patterns:
            if find_pattern(text, pattern):
                raise RuntimeError(f"forbidden log pattern after session start: {pattern}")
        missing = [pattern for pattern in required_patterns if not find_pattern(text, pattern)]
        if not missing:
            return text
        if time.monotonic() >= deadline:
            raise RuntimeError(f"timed out waiting for log patterns: {', '.join(missing)}")
        sleep_func(poll_seconds)


def build_launch_command(prism_app: str, instance: str, world: str) -> list[str]:
    return ["open", "-a", prism_app, "--args", "-l", instance, "-w", world]


def default_launcher(command: list[str]) -> None:
    subprocess.run(command, check=True)


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def write_checklist(
    path: Path,
    scenario_name: str,
    scenario: dict,
    manifest: dict,
    launch_command: list[str],
    bundle_id: str,
    log_path: Path,
) -> None:
    manual = "yes" if scenario["manual_gui_required"] else "no"
    lines = [
        f"# Prism GUI Session — {scenario_name}",
        "",
        f"Description: {scenario['description']}",
        f"Manual GUI required: {manual}",
        f"Computer Use bundle id: `{bundle_id}`",
        f"Log path: `{log_path}`",
        f"Launch command: `{' '.join(launch_command)}`",
        "",
        "## Fullscreen gate",
        "",
        "Start windowed, wait for the current MS_GUI_TEST_READY log line, then enter native fullscreen or F11 fullscreen.",
        "Do not perform hotbar/use/click/scroll/screenshot actions until the fullscreen gate is visually confirmed.",
        "",
        "## Scenario steps",
        "",
    ]
    for check in scenario["checks"]:
        lines.append(f"- {check}")
    if scenario["hotbar_keys"]:
        lines.extend(["", "## Hotbar targets", ""])
        hotbar = manifest.get("hotbar_views", {})
        for key in scenario["hotbar_keys"]:
            view = hotbar.get(key, {})
            target = view.get("target", "unknown")
            lines.append(f"- hotbar `{key}` → `{target}`")
    if not scenario["manual_gui_required"]:
        lines.extend(["", "No Computer Use visual pass is required for this scenario unless the current code change touches visible GUI/Patchouli behavior."])
    path.write_text("\n".join(lines) + "\n")


def session_to_json(result: SessionResult, wrapper_result, log_path: Path) -> dict:
    return {
        "scenario": result.scenario,
        "manual_gui_required": result.manual_gui_required,
        "run_dir": str(result.run_dir),
        "launch_command": result.launch_command,
        "log_path": str(log_path),
        "manifest": result.manifest,
        "computer_use_wrapper": result_to_json(wrapper_result),
    }


def run_session(
    scenario_name: str,
    minecraft_dir: Path = DEFAULT_PRISM_MINECRAFT_DIR,
    instance_dir: Path = DEFAULT_INSTANCE_DIR,
    run_root: Path = DEFAULT_RUN_ROOT,
    source_world: str = DEFAULT_SOURCE_WORLD,
    world: str = DEFAULT_WORLD_NAME,
    prism_app: str = "Prism Launcher",
    instance: str = "dev",
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    poll_seconds: float = 0.25,
    no_launch: bool = False,
    prepare_world_func=prepare_world,
    setup_wrapper_func=setup_computer_use_wrapper,
    launcher=default_launcher,
    wait_for_log_func=wait_for_log_patterns,
    timestamp_func=timestamp,
) -> SessionResult:
    if scenario_name not in SCENARIOS:
        raise RuntimeError(f"unknown GUI scenario: {scenario_name}")
    scenario = SCENARIOS[scenario_name]
    minecraft_dir = minecraft_dir.expanduser().resolve()
    instance_dir = instance_dir.expanduser().resolve()
    run_root = run_root.expanduser().resolve()

    wrapper_result = setup_wrapper_func(instance_dir=instance_dir, app_path=DEFAULT_APP_PATH, wrapper_path=DEFAULT_WRAPPER_PATH)
    manifest = prepare_world_func(minecraft_dir, source_world, world)
    log_path = minecraft_dir / "logs" / "latest.log"
    offset = log_cursor(log_path)
    launch_command = build_launch_command(prism_app, instance, world)

    run_dir = run_root / f"{timestamp_func()}-{scenario_name}"
    run_dir.mkdir(parents=True, exist_ok=False)
    write_json(run_dir / "manifest.json", manifest)
    write_checklist(run_dir / "checklist.md", scenario_name, scenario, manifest, launch_command, wrapper_result.bundle_id, log_path)

    if no_launch:
        log_excerpt = ""
    else:
        launcher(launch_command)
        log_excerpt = wait_for_log_func(
            log_path=log_path,
            offset=offset,
            required_patterns=DEFAULT_REQUIRED_PATTERNS,
            forbidden_patterns=DEFAULT_FORBIDDEN_PATTERNS,
            timeout_seconds=timeout_seconds,
            poll_seconds=poll_seconds,
        )
    (run_dir / "log-excerpt.log").write_text(log_excerpt)

    result = SessionResult(
        scenario=scenario_name,
        manual_gui_required=bool(scenario["manual_gui_required"]),
        run_dir=run_dir,
        launch_command=launch_command,
        log_excerpt=log_excerpt,
        manifest=manifest,
    )
    write_json(run_dir / "session.json", session_to_json(result, wrapper_result, log_path))
    return result


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Prepare and launch the fixed Prism GUI test world with current-run log polling.")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), default="boot-smoke")
    parser.add_argument("--minecraft-dir", type=Path, default=DEFAULT_PRISM_MINECRAFT_DIR)
    parser.add_argument("--instance-dir", type=Path, default=DEFAULT_INSTANCE_DIR)
    parser.add_argument("--run-root", type=Path, default=DEFAULT_RUN_ROOT)
    parser.add_argument("--source-world", default=DEFAULT_SOURCE_WORLD)
    parser.add_argument("--world", default=DEFAULT_WORLD_NAME)
    parser.add_argument("--prism-app", default="Prism Launcher")
    parser.add_argument("--instance", default="dev")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--poll", type=float, default=0.25)
    parser.add_argument("--no-launch", action="store_true", help="Prepare artifacts/checklist without launching Prism or waiting for logs.")
    args = parser.parse_args(argv[1:])
    try:
        result = run_session(
            scenario_name=args.scenario,
            minecraft_dir=args.minecraft_dir,
            instance_dir=args.instance_dir,
            run_root=args.run_root,
            source_world=args.source_world,
            world=args.world,
            prism_app=args.prism_app,
            instance=args.instance,
            timeout_seconds=args.timeout,
            poll_seconds=args.poll,
            no_launch=args.no_launch,
        )
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(json.dumps({
        "scenario": result.scenario,
        "manual_gui_required": result.manual_gui_required,
        "run_dir": str(result.run_dir),
        "launch_command": result.launch_command,
    }, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
