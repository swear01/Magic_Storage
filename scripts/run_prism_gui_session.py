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
    from prepare_prism_gui_world import DisplayMode, DEFAULT_PRISM_MINECRAFT_DIR, DEFAULT_SOURCE_WORLD, DEFAULT_WORLD_NAME, current_macos_main_display_mode, prepare_world
except ModuleNotFoundError:
    from scripts.prepare_prism_gui_world import DisplayMode, DEFAULT_PRISM_MINECRAFT_DIR, DEFAULT_SOURCE_WORLD, DEFAULT_WORLD_NAME, current_macos_main_display_mode, prepare_world

try:
    from deploy_prism_dev import FUSION_FILENAME, FUSION_SHA512, fusion_jars, sha512
except ModuleNotFoundError:
    from scripts.deploy_prism_dev import FUSION_FILENAME, FUSION_SHA512, fusion_jars, sha512

DEFAULT_INSTANCE_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev"
DEFAULT_PROJECT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_RUN_ROOT = Path("build/gui-runs")
DEFAULT_TIMEOUT_SECONDS = 300
DEFAULT_PROCESS_TERMINATION_TIMEOUT_SECONDS = 5
DEFAULT_SHUTDOWN_STALL_TIMEOUT_SECONDS = 5
DEFAULT_OFFLINE_PLAYER = "MagicStorageBot"
DEFAULT_REQUIRED_PATTERNS = ["SelfTest:", "MS_GUI_TEST_READY"]
DEFAULT_FORBIDDEN_PATTERNS = ["advanced_container_set_data", "ERROR", "FATAL", "Caused by"]
MANUAL_HANDOFF_MESSAGE = "Minecraft is ready in the fixed test world. Please take over for the fullscreen visual checks; close with F11, wait for the normal window, then Command-Q."
OFFLINE_AUTH_PROPERTY_ERROR = "[net.minecraft.client.Minecraft/]: Failed to fetch user properties"
PRIMARY_MONITOR_ERROR = "glfwGetPrimaryMonitor failed"
STOPPING_LOG_PATTERN = "[net.minecraft.client.Minecraft/]: Stopping!"
LOG_TIMESTAMP_PATTERN = re.compile(r"^\[\d{1,2}[A-Za-z]{3}\d{4} ")
DEPLOY_REQUIRED_MESSAGE = "Run `python3 scripts/deploy_prism_dev.py` before launching Prism."
CURRENT_RUN_LOG_CHECK = "Check latest.log for no non-whitelisted advanced_container_set_data, ERROR, FATAL, or Caused by during the current run; the known offline profile-properties 401 is allowed only in its exact authlib stack."

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
            CURRENT_RUN_LOG_CHECK,
        ],
    },
    "crafting-fuel-page": {
        "description": "Verify the adaptive Crafting Terminal, vanilla-like full-height paged Fuel/recipe workspaces, runtime Fuel, station-gated recipes, EMI action, native 16×16 art backed by Fusion 80×16 connected sheets, and focus behavior.",
        "manual_gui_required": True,
        "hotbar_keys": ["1", "2", "7", "8", "9"],
        "checks": [
            "Pass the fullscreen gate before pressing hotbar/use/click/scroll.",
            "Use hotbar `8` and confirm the prepared world is at the true-void ready baseline: one active network backed by a fresh empty Core record owned by the repository, six connected finite Storage Unit tiers plus one Creative Storage Unit, empty timed-station slots and instant-station slots, zero Axe Energy, and zero timed/Fuel reserves.",
            "hotbar `1`, then `u`: open the Storage Terminal and confirm the active Creative Storage Unit makes the capacity line show localized unlimited type capacity rather than a large finite sentinel.",
            "hotbar `2`, then `u`: open the Crafting Terminal; confirm the frame and left rail are centered as one group, retain visible outer margins, and use the side-by-side layout without clipping at this fullscreen size.",
            "On the Storage tab, confirm only stored stacks appear and the recipe panel, player inventory, scrollbar, and slot grid are aligned to one geometry; the EMI overlay covers neither the frame nor left rail.",
            "Confirm the first three page tabs are Storage, Craftable, and Fuel, followed by a clear visual gap before the item-page sorting/search controls; every rail icon occupies the same visual size inside identical buttons.",
            "Cycle search mode and confirm Name uses a magnifier, Tag uses #, and Mod uses @; none may look like a crosshair or an overlay inside the magnifier.",
            "Click a left-rail toggle, then click empty panel space: no white focus border may remain on the button.",
            "Use the Fuel page tab; confirm its left rail contains only Storage, Craftable, and Fuel page tabs, while search/grid/recipe controls hide.",
            "Confirm Consumables, Timed Stations, and Instant Stations form three full-width category panels in that order, all using the same light vanilla container panels, inset wells, slot frames, and dark-gray label text as the player inventory; they start immediately below the Crafting Terminal heading, fill the complete vertical span down to the player-inventory label band, and leave no dead blank quadrant. Each name stays inside a bounded left label strip; cells fill the available space evenly within each page, and every descriptor area supports multi-row pages with a visible page indicator and panel-local wheel paging when its category overflows.",
            "Confirm Consumables contains Fuel input, Fuel, Brew Energy, and Axe Energy; Timed Stations contains the five energy-producing stations; Instant Stations contains Crafting Table, Stonecutter, and Smithing Table without fake energy totals.",
            "Confirm the compact Fuel Target bar is visually separate above the Consumables content row: left-click or wheel-down cycles forward, right-click or wheel-up cycles backward, and middle-click resets to Auto without scrolling the row.",
            "Confirm every cyclic sort/search/source/output selector also resets to its documented default on middle-click; page tabs do not pretend to be on/off controls.",
            "Use the separate Fuel Target list button; confirm the popup contains Auto plus every current target with its representative item, marks the selected row, uses bounded scrolling when needed, stays clear of the left rail/EMI overlay, and closes on selection, Escape, or a click outside the popup without leaving a focus border.",
            "Confirm Fuel shows no permanent rate formula, no per-tile explanatory labels, and no shadow behind reserve values; exact identity, rate, and stored amount remain available in the hover tooltip.",
            "Confirm station/reserve hover details appear only over the actual station slot or reserve icon, not over unused space in the wider flow cell.",
            "Confirm Consumables uses representative items—Coal and Blaze Rod—as the only fuel reserves; no third reserve appears.",
            "On Storage and Craftable, confirm large item counts use compact units and automatically shrink/right-align inside their own slot without entering neighboring item cells.",
            "On Fuel, confirm stored types / total type capacity appears in an independent information box immediately to the right of the player inventory, aligned to the inventory's top and bottom, readable without hover, and matching the Storage Core. The active network must show localized unlimited type capacity with the Creative Storage Unit icon; finite networks continue to use the Tier 1 icon. Instant Stations uses its full category width and never reserves a station cell for this information.",
            "At the reset baseline, confirm every Timed and Instant Stations slot shows a dim representative station item without looking installed, the Axe Energy input contains no retrievable axe, and every machine-generated or consumable total stays at zero.",
            "Shift-click the supplied stack of three Furnaces into Timed Stations; the hover tooltip must report the exact rate and only Smelting Energy may rise.",
            "Remove the Furnace stack and confirm generation stops while the accumulated Smelting Energy remains.",
            "Shift-click each supplied pair of instant stations and confirm they accept only one Crafting Table, Stonecutter, and Smithing Table while the second copy remains in the player inventory. Remove the installed copy to make its recipes disappear, then reinstall one copy to restore them; none may increase a machine-generated energy total.",
            "Shift-click the supplied plain iron axe into the Axe Energy input and confirm it is consumed immediately, no retrievable axe remains, and finite Axe Energy increases by its exact remaining durability. Repeat with the supplied damaged Unbreaking II axe and confirm the increase is remaining durability multiplied by three.",
            "With player-inventory ingredients enabled, verify station gating dynamically: Crafting Table exposes an Oak Log crafting recipe, Stonecutter exposes a cobblestone result, Smithing Table exposes the Netherite Smithing Transform, and stored Axe Energy exposes an Oak Log strip transformation.",
            "Select each expanded recipe once and confirm the light vanilla-style recipe workspace contains one compact raised panel: the EMI recipe diagram is used for represented standard recipes while the internal axe transformation uses the native diagram, and the material ledger follows immediately below without an oversized empty panel. Required resources align from the ledger's top edge, use at most four columns, and recipes with more than eight total item/energy/tool resources continue into a third row without clipping. Both clearly show input resources, operation arrow, exact output count, Ready/Missing state, Available / Required values, explicit vanilla-style navigation buttons, and vanilla-style ×1/×8/×64/Max buttons. A dim station icon stays in the diagram's lower-right corner without covering recipe content.",
            "Select no item and confirm the complete wrapped prompt is centered in that same compact card without losing its final character; select a stored item with no supported recipe and confirm the panel shows a neutral No supported recipe message instead of an empty panel, white fallback surface, or red error.",
            "Toggle Craft Output and confirm Player uses a player-head icon while Storage uses the Storage Core icon; Craft Output: Storage has no status light, while the genuine Use Player Inventory on/off control may show one.",
            "For the axe strip recipe, confirm Axe Energy appears as a distinct required energy resource and one successful finite craft decrements Axe Energy by exactly one without storing or mutating an axe item.",
            "Use hotbar `9` reset, then Shift-click the supplied Unbreakable axe into the Axe Energy input; it must be consumed and display an infinity marker. A later axe must be rejected unchanged, and a successful axe recipe must not decrement infinite Axe Energy.",
            "Confirm every currently registered reserve total is reachable (scroll its panel if paging is shown), and the only fuel-reserve labels are Fuel and Brew Energy rather than implementation names.",
            "Confirm the single Fuel Target selector shows Auto, then Shift-click a Blaze Rod and verify only Brew Energy increases; use hotbar `9` reset before another case if needed.",
            "Use hotbar `9` reset, re-open Fuel, select Fuel in the Fuel Target selector, and Shift-click Oak Logs; the stack must be accepted and add its current runtime burn time multiplied by the accepted count.",
            "Use hotbar `9` reset again; install three Furnaces, add Coal to Fuel, and wait until Smelting Energy reaches at least one Charcoal recipe cooking time.",
            "Return to the Storage tab and confirm Charcoal with zero stored count is absent; then enable player-inventory ingredients and open the Craftable tab, where Charcoal must appear because supplied Oak Logs make its exact recipe currently craftable.",
            "Shift-click the synthetic Charcoal entry and confirm no item is extracted; normal-click it and confirm the exact smelting preview opens with an Available / Required table.",
            "With fewer than eight legal crafts, confirm ×8 and ×64 are dim while ×1 and Max reflect the live capacity; use Max once and confirm it crafts the complete currently possible amount without a partial ×8 craft.",
            "In that table, confirm Oak Log shows its current available count and 1 required, while Smelting Energy and Fuel each show their current reserve and the exact recipe cooking time required for one craft.",
            "Change an ingredient or energy reserve and confirm the corresponding available value refreshes while required-for-one stays recipe-derived.",
            "Open the Charcoal recipe in EMI: NONE selection must preserve resources, then a cursor/inventory craft action must perform one immediate server-authoritative craft without recursively crafting intermediates.",
            "Use hotbar `7` to inspect both gallery rows and the supplied Creative Storage Unit item in the player inventory. Its item texture and the Creative Storage Unit block in the isolated row must use crisp native 16×16 pixel art with a centered cyan-amethyst infinity motif distinct from all six finite tiers; its localized tooltip must state unlimited distinct item types and that it does not generate items. The Storage Core, six finite tiers, both terminals, and Import/Export Bus faces must retain their existing family grammar.",
            "Inspect the contiguous connected row behind it: the Fusion 80×16 five-tile sheets must remove shared casing borders between every adjacent network role, including the Creative Storage Unit, while its centered cyan-amethyst infinity motif and all other center motifs remain intact. The connected row intentionally has no Storage Core, so it cannot become a second active network. Import and Export must keep a readable directional front instead of connecting that face.",
            "While holding the Wrench on hotbar `7`, normal right-click the gallery Import or Export Bus and confirm its directional front rotates once. Then sneak-right-click one gallery block and confirm it dismantles directly into the inventory without losing its state or contents; use hotbar `9` to restore the complete gallery immediately afterward.",
            "Return between Storage, Craftable, and Fuel once more and confirm search, sort, item grid, adaptive recipe panel, machine slots, and crafting controls restore without drifting.",
            CURRENT_RUN_LOG_CHECK,
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
    targets = set()

    def is_prism_command(command: str) -> bool:
        lower_command = command.lower()
        return "prismlauncher" in lower_command or "/prism launcher.app/" in lower_command

    for pid, (_, command) in new_processes.items():
        is_runner_prism = is_prism_command(command) and all(
            marker in command
            for marker in [f"-l {instance}", f"-w {world}", f"-o {DEFAULT_OFFLINE_PLAYER}"]
        )
        is_instance_java = is_instance_java_command(command, instance_dir, minecraft_dir)
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


def is_instance_java_command(command: str, instance_dir: Path, minecraft_dir: Path) -> bool:
    is_java = re.search(r"(?:^|/)java(?:\s|$)", command) is not None
    if not is_java:
        return False
    if str(instance_dir) in command or str(minecraft_dir) in command:
        return True
    user_dir_match = re.search(r'-Duser\.dir=(?:"([^"]+)"|\'([^\']+)\'|(\S+))', command)
    if user_dir_match is None:
        return False
    user_dir = Path(next(value for value in user_dir_match.groups() if value is not None)).expanduser().resolve()
    return user_dir in {instance_dir.expanduser().resolve(), minecraft_dir.expanduser().resolve()}


def cleanup_existing_session(
    instance_dir: Path,
    minecraft_dir: Path,
    instance: str,
    world: str,
    snapshot_func=None,
    terminate_func=None,
) -> None:
    snapshot_func = snapshot_processes if snapshot_func is None else snapshot_func
    terminate_func = terminate_processes if terminate_func is None else terminate_func
    current = snapshot_func()
    targets = runner_started_process_ids({}, current, instance_dir, minecraft_dir, instance, world)
    if targets:
        terminate_func(targets)


def supervise_shutdown(
    expected_processes: dict[int, str],
    log_path: Path,
    cursor: LogCursor,
    run_dir: Path,
    stall_timeout_seconds: float = DEFAULT_SHUTDOWN_STALL_TIMEOUT_SECONDS,
    poll_seconds: float = 0.25,
    snapshot_func=None,
    terminate_func=None,
    monotonic_func=time.monotonic,
    sleep_func=time.sleep,
) -> dict:
    snapshot_func = snapshot_processes if snapshot_func is None else snapshot_func
    terminate_func = terminate_processes if terminate_func is None else terminate_func
    stopping_since = None
    stopping_detected = False
    while True:
        current = snapshot_func()
        matching = {
            pid: command
            for pid, command in expected_processes.items()
            if pid in current and current[pid][1] == command
        }
        if not matching:
            result = {
                "status": "graceful",
                "stopping_detected": stopping_detected,
                "forced_pids": [],
                "stall_timeout_seconds": stall_timeout_seconds,
            }
            write_json(run_dir / "shutdown.json", result)
            return result

        if STOPPING_LOG_PATTERN in read_log_since(log_path, cursor):
            stopping_detected = True
            if stopping_since is None:
                stopping_since = monotonic_func()
            elif monotonic_func() - stopping_since >= stall_timeout_seconds:
                forced_pids = sorted(matching)
                terminate_func(forced_pids)
                result = {
                    "status": "forced_after_glfw_shutdown_stall",
                    "stopping_detected": True,
                    "forced_pids": forced_pids,
                    "stall_timeout_seconds": stall_timeout_seconds,
                }
                write_json(run_dir / "shutdown.json", result)
                return result
        sleep_func(poll_seconds)


def start_shutdown_watchdog(
    expected_processes: dict[int, str],
    log_path: Path,
    cursor: LogCursor,
    run_dir: Path,
    popen_func=subprocess.Popen,
) -> None:
    config_path = run_dir / "shutdown-watchdog.json"
    write_json(config_path, {
        "expected_processes": {str(pid): command for pid, command in expected_processes.items()},
        "log_path": str(log_path),
        "cursor": {"size": cursor.size, "device": cursor.device, "inode": cursor.inode},
        "run_dir": str(run_dir),
        "stall_timeout_seconds": DEFAULT_SHUTDOWN_STALL_TIMEOUT_SECONDS,
    })
    watchdog_log = (run_dir / "shutdown-watchdog.log").open("ab")
    try:
        popen_func(
            [sys.executable, str(Path(__file__).resolve()), "--watchdog-config", str(config_path)],
            stdin=subprocess.DEVNULL,
            stdout=watchdog_log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            close_fds=True,
        )
    finally:
        watchdog_log.close()


def run_shutdown_watchdog(config_path: Path) -> dict:
    config = json.loads(config_path.read_text())
    cursor_data = config["cursor"]
    return supervise_shutdown(
        {int(pid): command for pid, command in config["expected_processes"].items()},
        Path(config["log_path"]),
        LogCursor(cursor_data["size"], cursor_data["device"], cursor_data["inode"]),
        Path(config["run_dir"]),
        stall_timeout_seconds=float(config["stall_timeout_seconds"]),
    )


def verify_desktop_display_mode(manifest: dict, mode_func=current_macos_main_display_mode) -> DisplayMode:
    expected_data = manifest.get("desktop_display_mode")
    if not isinstance(expected_data, dict):
        raise RuntimeError("GUI manifest is missing the captured macOS desktop display mode")
    try:
        expected = DisplayMode(**expected_data)
    except (TypeError, ValueError) as exc:
        raise RuntimeError("GUI manifest contains an invalid macOS desktop display mode") from exc
    actual = mode_func()
    if actual != expected:
        raise RuntimeError(
            "Minecraft changed the macOS desktop display mode: "
            f"expected {expected.width}x{expected.height}@{expected.refresh_rate} "
            f"({expected.pixel_width}x{expected.pixel_height} pixels), found "
            f"{actual.width}x{actual.height}@{actual.refresh_rate} "
            f"({actual.pixel_width}x{actual.pixel_height} pixels)"
        )
    return actual


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


def verify_deployed_fusion_jar(minecraft_dir: Path) -> None:
    mods_dir = minecraft_dir.expanduser().resolve() / "mods"
    deployed_jars = fusion_jars(mods_dir) if mods_dir.is_dir() else []
    if len(deployed_jars) != 1 or deployed_jars[0].name != FUSION_FILENAME:
        found = ", ".join(path.name for path in deployed_jars) or "none"
        raise RuntimeError(
            f"Expected exactly one Fusion jar named {FUSION_FILENAME} in Prism dev mods at {mods_dir}, "
            f"found {len(deployed_jars)}: {found}. {DEPLOY_REQUIRED_MESSAGE}"
        )
    deployed_hash = sha512(deployed_jars[0])
    if deployed_hash != FUSION_SHA512:
        raise RuntimeError(
            f"Fusion jar contents differ for {FUSION_FILENAME}: expected SHA-512 {FUSION_SHA512}, "
            f"found {deployed_hash}. {DEPLOY_REQUIRED_MESSAGE}"
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
        "Minecraft automatically starts in borderless Minecraft F11 fullscreen; wait for the current MS_GUI_TEST_READY log line before taking control.",
        "On macOS the client never attaches the GLFW window to the monitor, and the runner fails closed if the desktop display mode changes.",
        "Do not use the macOS green fullscreen button or Control-Command-F, and never combine native fullscreen with Minecraft F11 fullscreen.",
        "Do not perform hotbar/use/click/scroll/screenshot actions until the fullscreen gate is visually confirmed.",
        "To close this test session safely, press F11 once, wait until the normal window is visible, then press Command-Q.",
        "Do not press Command-Q while Minecraft F11 fullscreen is still active.",
        "A runner-owned shutdown watchdog terminates only this test client if the Minecraft shutdown log appears but the Java process stalls for five seconds.",
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
    fullscreen_gate = result.manifest["fullscreen_gate"]
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
            "fullscreen_mode": fullscreen_gate["accepted_methods"][0],
            "automatic_fullscreen": fullscreen_gate["automatic"],
            "desktop_display_mode": result.manifest["desktop_display_mode"],
            "instance_cfg_changed": instance_cfg_changed,
        },
        "shutdown_profile": {
            "safe_sequence": "f11_then_command_q",
            "watchdog_enabled": result.manual_gui_required,
            "stall_timeout_seconds": DEFAULT_SHUTDOWN_STALL_TIMEOUT_SECONDS,
            "direct_command_q_while_fullscreen": "forbidden",
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
    cleanup_existing_func=cleanup_existing_session,
    configure_instance_func=configure_instance_for_manual_handoff,
    launcher=default_launcher,
    wait_for_log_func=wait_for_log_patterns,
    display_mode_verifier=None,
    watchdog_launcher=start_shutdown_watchdog,
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
        verify_deployed_fusion_jar(minecraft_dir)
        cleanup_existing_func(instance_dir, minecraft_dir, instance, world)
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
            verifier = verify_desktop_display_mode if display_mode_verifier is None else display_mode_verifier
            verifier(manifest)
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
        if process_baseline is not None and scenario["manual_gui_required"]:
            current_processes = snapshot_processes()
            owned_pids = runner_started_process_ids(
                process_baseline,
                current_processes,
                instance_dir,
                minecraft_dir,
                instance,
                world,
            )
            expected_java = {
                pid: current_processes[pid][1]
                for pid in owned_pids
                if is_instance_java_command(current_processes[pid][1], instance_dir, minecraft_dir)
            }
            if expected_java:
                watchdog_launcher(expected_java, log_path, log_cursor(log_path), run_dir)
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
    if len(argv) == 3 and argv[1] == "--watchdog-config":
        try:
            result = run_shutdown_watchdog(Path(argv[2]))
        except Exception as exc:
            print(str(exc), file=sys.stderr)
            return 1
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
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
