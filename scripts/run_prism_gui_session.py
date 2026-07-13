#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable, NamedTuple

try:
    from prepare_prism_gui_world import DEFAULT_PRISM_MINECRAFT_DIR, DEFAULT_SOURCE_WORLD, DEFAULT_WORLD_NAME, prepare_world
except ModuleNotFoundError:
    from scripts.prepare_prism_gui_world import DEFAULT_PRISM_MINECRAFT_DIR, DEFAULT_SOURCE_WORLD, DEFAULT_WORLD_NAME, prepare_world

DEFAULT_INSTANCE_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev"
DEFAULT_PROJECT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_RUN_ROOT = Path("build/gui-runs")
DEFAULT_TIMEOUT_SECONDS = 300
DEFAULT_PROCESS_TERMINATION_TIMEOUT_SECONDS = 5
DEFAULT_OFFLINE_PLAYER = "MagicStorageBot"
DEFAULT_REQUIRED_PATTERNS = ["SelfTest:", "MS_GUI_TEST_READY"]
DEFAULT_FORBIDDEN_PATTERNS = ["advanced_container_set_data", "ERROR", "FATAL", "Caused by"]
MANUAL_HANDOFF_MESSAGE = "Minecraft is ready in the fixed test world. Please take over for the fullscreen visual checks."
OFFLINE_AUTH_PROPERTY_ERROR = "[net.minecraft.client.Minecraft/]: Failed to fetch user properties"
PRIMARY_MONITOR_ERROR = "glfwGetPrimaryMonitor failed"
LOG_TIMESTAMP_PATTERN = re.compile(r"^\[\d{1,2}[A-Za-z]{3}\d{4} ")
DEPLOY_REQUIRED_MESSAGE = "Run `python3 scripts/deploy_prism_dev.py` before launching Prism."

SCENARIOS = {
    "boot-smoke": {
        "description": "Launch the fixed GUI test world and verify boot/resource logs only.",
        "manual_gui_required": False,
        "hotbar_keys": [],
        "checks": [
            "No manual visual pass is required unless this run is investigating visible UI behavior.",
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
    "crafting-fuel-page": {
        "description": "Verify the adaptive Crafting Terminal, two-row station/tool page, recipe resource workspace, runtime Fuel, expanded station-gated recipes, EMI action, 16×16 textures, and focus behavior.",
        "manual_gui_required": True,
        "hotbar_keys": ["2", "7", "8", "9"],
        "checks": [
            "Pass the fullscreen gate before pressing hotbar/use/click/scroll.",
            "Use hotbar `8` and confirm the prepared world is at the true-void ready baseline: one active network, six connected Storage Unit tiers, empty process-machine slots, preinstalled recipe stations/tools, and zero Energy Reserves.",
            "hotbar `2`, then `u`: open the Crafting Terminal; confirm the frame and left rail are centered as one group, retain visible outer margins, and use the side-by-side layout without clipping at this fullscreen size.",
            "On the Storage tab, confirm only stored stacks appear and the recipe panel, player inventory, scrollbar, and slot grid are aligned to one geometry; the EMI overlay covers neither the frame nor left rail.",
            "Confirm the first three page tabs are Storage, Craftable, and Fuel, followed by a clear visual gap before the item-page sorting/search controls; every rail icon occupies the same visual size inside identical buttons.",
            "Cycle search mode and confirm Name uses a magnifier, Tag uses #, and Mod uses @; none may look like a crosshair or an overlay inside the magnifier.",
            "Click a left-rail toggle, then click empty panel space: no white focus border may remain on the button.",
            "Use the Fuel page tab; confirm its left rail contains only Storage, Craftable, and Fuel page tabs, while search/grid/recipe controls hide.",
            "Confirm Installed Stations uses two flow rows and both Installed Stations and Energy Reserves distribute across the available panel width instead of clustering at the left, without clipping.",
            "Confirm the lower-right Fuel control panel is occupied and balanced with the player inventory: Fuel Target and the Fuel input stay inside it with no dead blank quadrant or overlap.",
            "Confirm Energy Reserves has a single current-value selector labeled Fuel Target; normal click cycles forward, while Shift-click and hovered mouse wheel cycle backward without scrolling the reserve panel.",
            "Confirm Fuel shows no permanent rate formula, no per-tile explanatory labels, and no shadow behind reserve values; exact identity, rate, and stored amount remain available in the hover tooltip.",
            "Confirm Energy Reserves uses representative items (Coal, Blaze Rod, and Glass Bottle) instead of abstract energy glyphs; the selector text always states the current target.",
            "On Storage and Craftable, confirm large item counts use compact units and automatically shrink/right-align inside their own slot without entering neighboring item cells.",
            "On Fuel, confirm stored types / total type capacity is visible above the player inventory and matches the Storage Core.",
            "At the reset baseline, confirm process-machine slots 0–4 are empty and every machine-generated total stays at zero; the preinstalled Crafting Table, Stonecutter, Smithing Table, and iron axe must not generate energy.",
            "Shift-click the supplied stack of three Furnaces into Installed Stations; the hover tooltip must report the exact rate and only Smelting Energy may rise.",
            "Remove the Furnace stack and confirm generation stops while the accumulated Smelting Energy remains.",
            "Remove each preinstalled Crafting Table, Stonecutter, Smithing Table, and iron axe once, confirm its recipes disappear, then reinstall the supplied copy in its exact Installed Stations slot; none may increase a machine-generated energy total.",
            "With player-inventory ingredients enabled, verify station gating dynamically: Crafting Table exposes an Oak Log crafting recipe, Stonecutter exposes a cobblestone result, Smithing Table exposes the Netherite Smithing Transform, and the reinstalled axe exposes an Oak Log strip transformation.",
            "Select each expanded recipe once and confirm the native recipe workspace clearly shows station/type, input resources, operation arrow, output, Ready/Missing state, Available / Required values, navigation, and equal-size ×1/×8/×64/Max controls.",
            "For the axe strip recipe, confirm the axe appears as a required durability resource and one successful craft lowers its raw durability by one without consuming the whole tool.",
            "Confirm every currently registered reserve total is reachable (scroll its panel if paging is shown), and functional labels read Fuel, Brew Energy, and Bottle Energy rather than implementation names.",
            "Confirm the single Fuel Target selector shows Auto, then Shift-click a Blaze Rod and verify only Brew Energy increases; use hotbar `9` reset before another case if needed.",
            "Use hotbar `9` reset, re-open Fuel, select Fuel in the Fuel Target selector, and Shift-click Oak Logs; the stack must be accepted and add its current runtime burn time multiplied by the accepted count.",
            "Use hotbar `9` reset again; install three Furnaces, add Coal to Fuel, and wait until Smelting Energy reaches at least one Charcoal recipe cooking time.",
            "Return to the Storage tab and confirm Charcoal with zero stored count is absent; then enable player-inventory ingredients and open the Craftable tab, where Charcoal must appear because supplied Oak Logs make its exact recipe currently craftable.",
            "Shift-click the synthetic Charcoal entry and confirm no item is extracted; normal-click it and confirm the exact smelting preview opens with an Available / Required table.",
            "With fewer than eight legal crafts, confirm ×8 and ×64 are dim while ×1 and Max reflect the live capacity; use Max once and confirm it crafts the complete currently possible amount without a partial ×8 craft.",
            "In that table, confirm Oak Log shows its current available count and 1 required, while Smelting Energy and Fuel each show their current reserve and the exact recipe cooking time required for one craft.",
            "Change an ingredient or energy reserve and confirm the corresponding available value refreshes while required-for-one stays recipe-derived.",
            "Open the Charcoal recipe in EMI: NONE selection must preserve resources, then a cursor/inventory craft action must perform one immediate server-authoritative craft without recursively crafting intermediates.",
            "Use hotbar `7` to inspect the isolated texture gallery, then inspect the held Remote Terminal item: the Storage Core, six Storage Unit tiers, both terminals, and Import/Export Bus faces must read as crisp native 16×16 pixel art with a coherent dark stone plus restrained amethyst/cyan accent, while adjacent tiers and bus faces remain distinguishable.",
            "Return between Storage, Craftable, and Fuel once more and confirm search, sort, item grid, adaptive recipe panel, machine slots, and crafting controls restore without drifting.",
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
    manual_handoff_message: str | None
    run_dir: Path
    launch_command: list[str]
    log_excerpt: str
    manifest: dict


class LogCursor(NamedTuple):
    size: int
    device: int | None
    inode: int | None


def timestamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S")


def log_cursor(log_path: Path) -> LogCursor:
    if not log_path.exists():
        return LogCursor(0, None, None)
    stat_result = log_path.stat()
    return LogCursor(stat_result.st_size, stat_result.st_dev, stat_result.st_ino)


def read_log_since(log_path: Path, cursor: LogCursor) -> str:
    if not log_path.exists():
        return ""
    stat_result = log_path.stat()
    same_file = cursor.device == stat_result.st_dev and cursor.inode == stat_result.st_ino
    start = cursor.size if same_file and stat_result.st_size >= cursor.size else 0
    with log_path.open("rb") as handle:
        handle.seek(start)
        return handle.read().decode("utf-8", errors="replace")


def find_pattern(text: str, pattern: str) -> bool:
    return re.search(pattern, text) is not None if pattern.startswith("(?") else pattern in text


def remove_allowed_log_noise(text: str) -> str:
    lines = text.splitlines(keepends=True)
    filtered = []
    skipping_offline_auth_error = False
    for line in lines:
        if OFFLINE_AUTH_PROPERTY_ERROR in line:
            skipping_offline_auth_error = True
            continue
        if skipping_offline_auth_error and LOG_TIMESTAMP_PATTERN.match(line):
            skipping_offline_auth_error = False
        if not skipping_offline_auth_error:
            filtered.append(line)
    return "".join(filtered)


def wait_for_log_patterns(
    log_path: Path,
    offset: LogCursor,
    required_patterns: list[str],
    forbidden_patterns: list[str],
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    poll_seconds: float = 0.25,
    sleep_func: Callable[[float], None] = time.sleep,
) -> str:
    deadline = time.monotonic() + timeout_seconds
    while True:
        text = read_log_since(log_path, offset)
        filtered_text = remove_allowed_log_noise(text)
        if PRIMARY_MONITOR_ERROR in filtered_text:
            raise RuntimeError(
                "Minecraft could not access a primary monitor; wake and unlock the macOS display, then rerun the same GUI scenario."
            )
        for pattern in forbidden_patterns:
            if find_pattern(filtered_text, pattern):
                raise RuntimeError(f"forbidden log pattern after session start: {pattern}")
        missing = [pattern for pattern in required_patterns if not find_pattern(text, pattern)]
        if not missing:
            return text
        if time.monotonic() >= deadline:
            raise RuntimeError(f"timed out waiting for log patterns: {', '.join(missing)}")
        sleep_func(poll_seconds)


def build_launch_command(prism_app: str, instance: str, world: str) -> list[str]:
    return ["open", "-a", prism_app, "--args", "-l", instance, "-w", world, "-o", DEFAULT_OFFLINE_PLAYER]


def default_launcher(command: list[str]) -> None:
    subprocess.run(command, check=True)


def snapshot_processes(run_func=subprocess.run) -> dict[int, tuple[int, str]]:
    result = run_func(
        ["ps", "-axo", "pid=,ppid=,command="],
        text=True,
        capture_output=True,
        check=True,
    )
    processes = {}
    for line_number, line in enumerate(result.stdout.splitlines(), start=1):
        if not line.strip():
            continue
        fields = line.strip().split(None, 2)
        if len(fields) != 3:
            raise RuntimeError(f"unable to parse process snapshot line {line_number}: {line}")
        try:
            pid = int(fields[0])
            parent_pid = int(fields[1])
        except ValueError as exc:
            raise RuntimeError(f"unable to parse process ids on snapshot line {line_number}: {line}") from exc
        processes[pid] = (parent_pid, fields[2])
    return processes


def process_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError as exc:
        raise RuntimeError(f"permission denied while checking process {pid}") from exc
    return True


def terminate_processes(
    pids: list[int],
    timeout_seconds: float = DEFAULT_PROCESS_TERMINATION_TIMEOUT_SECONDS,
    kill_func=os.kill,
    process_alive_func=process_is_alive,
    sleep_func=time.sleep,
) -> None:
    ordered_pids = list(dict.fromkeys(pids))
    if any(pid <= 1 for pid in ordered_pids):
        raise RuntimeError(f"refusing to terminate unsafe process ids: {ordered_pids}")

    for pid in ordered_pids:
        if not process_alive_func(pid):
            continue
        try:
            kill_func(pid, signal.SIGTERM)
        except ProcessLookupError:
            continue

    deadline = time.monotonic() + timeout_seconds
    remaining = [pid for pid in ordered_pids if process_alive_func(pid)]
    while remaining and time.monotonic() < deadline:
        sleep_func(0.1)
        remaining = [pid for pid in remaining if process_alive_func(pid)]

    for pid in remaining:
        try:
            kill_func(pid, signal.SIGKILL)
        except ProcessLookupError:
            continue

    kill_deadline = time.monotonic() + timeout_seconds
    remaining = [pid for pid in remaining if process_alive_func(pid)]
    while remaining and time.monotonic() < kill_deadline:
        sleep_func(0.1)
        remaining = [pid for pid in remaining if process_alive_func(pid)]
    if remaining:
        raise RuntimeError(f"failed to terminate runner-started processes: {remaining}")


def runner_started_process_ids(
    baseline: dict[int, tuple[int, str]],
    current: dict[int, tuple[int, str]],
    instance_dir: Path,
    minecraft_dir: Path,
    instance: str,
    world: str,
) -> list[int]:
    new_processes = {pid: process for pid, process in current.items() if pid not in baseline}
    instance_path = str(instance_dir)
    minecraft_path = str(minecraft_dir)
    targets = set()

    def is_prism_command(command: str) -> bool:
        lower_command = command.lower()
        return "prismlauncher" in lower_command or "/prism launcher.app/" in lower_command

    for pid, (_, command) in new_processes.items():
        is_runner_prism = is_prism_command(command) and all(
            marker in command
            for marker in [f"-l {instance}", f"-w {world}", f"-o {DEFAULT_OFFLINE_PLAYER}"]
        )
        is_java = re.search(r"(?:^|/)java(?:\s|$)", command) is not None
        is_instance_java = is_java and (instance_path in command or minecraft_path in command)
        if is_runner_prism or is_instance_java:
            targets.add(pid)

    for pid in list(targets):
        seen = {pid}
        parent_pid = new_processes[pid][0]
        while parent_pid in new_processes:
            if parent_pid in seen:
                raise RuntimeError(f"cycle detected in process tree at pid {parent_pid}")
            seen.add(parent_pid)
            if is_prism_command(new_processes[parent_pid][1]):
                targets.add(parent_pid)
            parent_pid = new_processes[parent_pid][0]

    changed = True
    while changed:
        changed = False
        for pid, (parent_pid, _) in new_processes.items():
            if pid not in targets and parent_pid in targets:
                targets.add(pid)
                changed = True

    def process_depth(pid: int) -> int:
        depth = 0
        seen = {pid}
        parent_pid = current[pid][0]
        while parent_pid in targets:
            if parent_pid in seen:
                raise RuntimeError(f"cycle detected in process tree at pid {parent_pid}")
            seen.add(parent_pid)
            depth += 1
            parent_pid = current[parent_pid][0]
        return depth

    return sorted(targets, key=lambda pid: (process_depth(pid), pid), reverse=True)


def cleanup_started_processes(
    baseline: dict[int, tuple[int, str]],
    instance_dir: Path,
    minecraft_dir: Path,
    instance: str,
    world: str,
) -> None:
    current = snapshot_processes()
    targets = runner_started_process_ids(baseline, current, instance_dir, minecraft_dir, instance, world)
    if targets:
        terminate_processes(targets)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_deployed_magic_storage_jar(project_dir: Path, minecraft_dir: Path) -> None:
    project_dir = project_dir.expanduser().resolve()
    minecraft_dir = minecraft_dir.expanduser().resolve()
    properties_path = project_dir / "gradle.properties"
    if not properties_path.is_file():
        raise RuntimeError(f"Gradle properties not found: {properties_path}. {DEPLOY_REQUIRED_MESSAGE}")
    versions = [
        line.split("=", 1)[1].strip()
        for line in properties_path.read_text().splitlines()
        if line.split("=", 1)[0].strip() == "mod_version" and "=" in line
    ]
    if len(versions) != 1 or not versions[0]:
        raise RuntimeError(f"Expected exactly one mod_version in {properties_path}. {DEPLOY_REQUIRED_MESSAGE}")

    build_jar = project_dir / "build" / "libs" / f"magic_storage-{versions[0]}.jar"
    if not build_jar.is_file():
        raise RuntimeError(f"Current Magic Storage build jar not found: {build_jar}. {DEPLOY_REQUIRED_MESSAGE}")

    mods_dir = minecraft_dir / "mods"
    deployed_jars = sorted(mods_dir.glob("magic_storage-*.jar")) if mods_dir.is_dir() else []
    if len(deployed_jars) != 1:
        found = ", ".join(path.name for path in deployed_jars) or "none"
        raise RuntimeError(
            f"Expected exactly one Magic Storage jar in Prism dev mods at {mods_dir}, found {len(deployed_jars)}: {found}. "
            f"{DEPLOY_REQUIRED_MESSAGE}"
        )

    deployed_jar = deployed_jars[0]
    if deployed_jar.name != build_jar.name:
        raise RuntimeError(
            f"Prism dev Magic Storage jar version does not match the current build: expected {build_jar.name}, "
            f"found {deployed_jar.name}. {DEPLOY_REQUIRED_MESSAGE}"
        )

    build_hash = sha256_file(build_jar)
    deployed_hash = sha256_file(deployed_jar)
    if build_hash != deployed_hash:
        raise RuntimeError(
            f"Magic Storage jar contents differ for {build_jar.name}: build SHA-256 {build_hash}, "
            f"Prism dev SHA-256 {deployed_hash}. {DEPLOY_REQUIRED_MESSAGE}"
        )


def configure_instance_for_manual_handoff(instance_dir: Path) -> bool:
    instance_cfg = instance_dir.expanduser().resolve() / "instance.cfg"
    if not instance_cfg.is_file():
        raise RuntimeError(f"Prism instance config not found: {instance_cfg}")
    original = instance_cfg.read_text()
    overrides = {
        "OverrideConsole": "true",
        "ShowConsole": "false",
        "ShowConsoleOnError": "false",
        "WrapperCommand": "",
    }
    output = []
    found = set()
    for line in original.splitlines():
        key = line.split("=", 1)[0]
        if key in overrides:
            output.append(f"{key}={overrides[key]}")
            found.add(key)
        else:
            output.append(line)
    for key, value in overrides.items():
        if key not in found:
            output.append(f"{key}={value}")
    updated = "\n".join(output) + "\n"
    if updated == original:
        return False
    instance_cfg.write_text(updated)
    return True


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def write_checklist(
    path: Path,
    scenario_name: str,
    scenario: dict,
    manifest: dict,
    launch_command: list[str],
    log_path: Path,
) -> None:
    manual = "yes" if scenario["manual_gui_required"] else "no"
    lines = [
        f"# Prism GUI Session — {scenario_name}",
        "",
        f"Description: {scenario['description']}",
        f"Manual GUI required: {manual}",
        f"Visual verification owner: {'user' if scenario['manual_gui_required'] else 'none'}",
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
    if scenario["manual_gui_required"]:
        lines.extend([
            "Stop automation here and hand control to the user after Minecraft reaches MS_GUI_TEST_READY.",
            "The user performs the fullscreen gate and the scenario steps below.",
            "",
        ])
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
        lines.extend(["", "No visual pass is required for this scenario unless the current code change touches visible GUI/Patchouli behavior."])
    path.write_text("\n".join(lines) + "\n")


def session_to_json(result: SessionResult, instance_cfg_changed: bool, log_path: Path) -> dict:
    return {
        "scenario": result.scenario,
        "manual_gui_required": result.manual_gui_required,
        "manual_handoff_required": result.manual_gui_required,
        "manual_handoff_message": result.manual_handoff_message,
        "visual_verification_owner": "user" if result.manual_gui_required else "none",
        "run_dir": str(result.run_dir),
        "launch_command": result.launch_command,
        "log_path": str(log_path),
        "manifest": result.manifest,
        "launch_profile": {
            "offline_player": DEFAULT_OFFLINE_PLAYER,
            "computer_use_wrapper_disabled": True,
            "error_console_disabled": True,
            "instance_cfg_changed": instance_cfg_changed,
        },
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
    configure_instance_func=configure_instance_for_manual_handoff,
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

    if not no_launch:
        verify_deployed_magic_storage_jar(DEFAULT_PROJECT_DIR, minecraft_dir)
    instance_cfg_changed = configure_instance_func(instance_dir)
    manifest = prepare_world_func(minecraft_dir, source_world, world)
    log_path = minecraft_dir / "logs" / "latest.log"
    offset = log_cursor(log_path)
    launch_command = build_launch_command(prism_app, instance, world)

    run_dir = run_root / f"{timestamp_func()}-{scenario_name}"
    run_dir.mkdir(parents=True, exist_ok=False)
    write_json(run_dir / "manifest.json", manifest)
    write_checklist(run_dir / "checklist.md", scenario_name, scenario, manifest, launch_command, log_path)

    process_baseline = None
    try:
        if no_launch:
            log_excerpt = ""
        else:
            process_baseline = snapshot_processes()
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
            manual_handoff_message=MANUAL_HANDOFF_MESSAGE if scenario["manual_gui_required"] and not no_launch else None,
            run_dir=run_dir,
            launch_command=launch_command,
            log_excerpt=log_excerpt,
            manifest=manifest,
        )
        write_json(run_dir / "session.json", session_to_json(result, instance_cfg_changed, log_path))
    except BaseException as session_error:
        if process_baseline is not None:
            try:
                cleanup_started_processes(process_baseline, instance_dir, minecraft_dir, instance, world)
            except Exception as cleanup_error:
                raise RuntimeError(
                    f"Prism session failed before handoff ({session_error}); process cleanup also failed ({cleanup_error})"
                ) from session_error
        raise

    if process_baseline is not None and not scenario["manual_gui_required"]:
        cleanup_started_processes(process_baseline, instance_dir, minecraft_dir, instance, world)
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
        "manual_handoff_message": result.manual_handoff_message,
        "run_dir": str(result.run_dir),
        "launch_command": result.launch_command,
    }, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
