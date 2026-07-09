#!/usr/bin/env python3
import argparse
import gzip
import json
import shutil
import struct
import subprocess
import sys
from pathlib import Path

DEFAULT_PRISM_MINECRAFT_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev/minecraft"
DEFAULT_SOURCE_WORLD = "New World"
DEFAULT_WORLD_NAME = "MagicStorageGuiTest"
DATAPACK_NAME = "magic_storage_gui_test"
MARKER_FILE = ".magic_storage_gui_test_world"
PACK_FORMAT = 48

OPTION_OVERRIDES = {
    "fullscreen": "false",
    "pauseOnLostFocus": "false",
    "key_key.use": "key.keyboard.u",
    "guiScale": "4",
    "overrideWidth": "1280",
    "overrideHeight": "720",
    "tutorialStep": "none",
    "autoJump": "false",
}

FULLSCREEN_GATE = {
    "required": True,
    "when": "after_world_ready_before_first_gui_action",
    "launch_mode": "windowed_only",
    "accepted_methods": ["native_fullscreen", "f11"],
    "ready_log": "MS_GUI_TEST_READY",
    "verify": [
        "Minecraft content is not offset or clipped",
        "hotbar and GUI edges are fully visible",
        "Computer Use can read run.hapi.magicstorage.minecraftcu after entering fullscreen",
    ],
}

TARGETS = {
    "storage_core": {"block": [0, 64, 0], "stand": [0.5, 65.0, 4.5], "face": [0.5, 64.5, 0.5]},
    "storage_unit_t6": {"block": [-1, 64, 0], "stand": [-4.5, 65.0, 0.5], "face": [-0.5, 64.5, 0.5]},
    "storage_terminal": {"block": [0, 64, 1], "stand": [0.5, 65.0, 4.5], "face": [0.5, 64.5, 1.5]},
    "crafting_terminal": {"block": [1, 64, 0], "stand": [4.5, 65.0, 0.5], "face": [1.5, 64.5, 0.5]},
    "import_bus": {"block": [-1, 64, 1], "stand": [-4.5, 65.0, 1.5], "face": [-0.5, 64.5, 1.5]},
    "export_bus": {"block": [1, 64, 1], "stand": [4.5, 65.0, 1.5], "face": [1.5, 64.5, 1.5]},
}


HOTBAR_VIEWS = {
    "1": {"slot": 0, "function": "view_storage_terminal", "target": "storage_terminal"},
    "2": {"slot": 1, "function": "view_crafting_terminal", "target": "crafting_terminal"},
    "3": {"slot": 2, "function": "view_storage_core", "target": "storage_core"},
    "4": {"slot": 3, "function": "view_storage_unit_t6", "target": "storage_unit_t6"},
    "5": {"slot": 4, "function": "view_import_bus", "target": "import_bus"},
    "6": {"slot": 5, "function": "view_export_bus", "target": "export_bus"},
    "9": {"slot": 8, "function": "reset_from_hotbar", "target": "reset"},
}

FUNCTIONS = {
    "setup": """
gamerule commandBlockOutput false
gamerule sendCommandFeedback false
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
time set noon
weather clear
difficulty peaceful
fill -8 63 -8 8 70 8 minecraft:air
fill -8 62 -8 8 62 8 minecraft:smooth_stone
setworldspawn 0 65 4
setblock 0 64 0 magic_storage:storage_core
setblock -1 64 0 magic_storage:storage_unit_t6
setblock -2 64 0 magic_storage:storage_unit_t1
setblock 0 64 1 magic_storage:storage_terminal
setblock 1 64 0 magic_storage:crafting_terminal
setblock -1 64 1 magic_storage:import_bus
setblock 1 64 1 magic_storage:export_bus
setblock -1 64 2 minecraft:barrel
setblock 1 64 2 minecraft:barrel
tag @a remove ms_gui_ready
""",
    "tick": """
execute as @a[tag=!ms_gui_ready] run function magic_storage_gui_test:player_ready
execute as @a[tag=ms_gui_ready] run function magic_storage_gui_test:hotbar_views
""",
    "player_ready": """
gamemode creative @s
effect give @s minecraft:night_vision infinite 0 true
clear @s
give @s magic_storage:remote_terminal 1
give @s minecraft:cobblestone 128
give @s minecraft:oak_log 96
give @s minecraft:iron_ingot 192
give @s minecraft:diamond 32
tag @s add ms_gui_ready
say MS_GUI_TEST_READY
tp @s 0.5 65.0 4.5 facing 0.5 64.5 1.5
""",
    "reset": """
function magic_storage_gui_test:setup
tag @a remove ms_gui_ready
execute as @a run function magic_storage_gui_test:player_ready
""",
    "reset_from_hotbar": """
function magic_storage_gui_test:setup
tag @s remove ms_gui_ready
function magic_storage_gui_test:player_ready
""",
    "home": """
tp @s 0.5 65.0 4.5 facing 0.5 64.5 1.5
""",
    "view_storage_core": """
tp @s 0.5 65.0 4.5 facing 0.5 64.5 0.5
""",
    "view_storage_unit_t6": """
tp @s -4.5 65.0 0.5 facing -0.5 64.5 0.5
""",
    "view_storage_terminal": """
tp @s 0.5 65.0 4.5 facing 0.5 64.5 1.5
""",
    "view_crafting_terminal": """
tp @s 4.5 65.0 0.5 facing 1.5 64.5 0.5
""",
    "view_import_bus": """
tp @s -4.5 65.0 1.5 facing -0.5 64.5 1.5
""",
    "view_export_bus": """
tp @s 4.5 65.0 1.5 facing 1.5 64.5 1.5
""",
}

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NbtReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def read(self, length: int) -> bytes:
        if self.pos + length > len(self.data):
            raise ValueError("truncated NBT")
        out = self.data[self.pos:self.pos + length]
        self.pos += length
        return out

    def unpack(self, fmt: str):
        return struct.unpack(fmt, self.read(struct.calcsize(fmt)))[0]

    def read_string(self) -> str:
        length = self.unpack(">H")
        return self.read(length).decode("utf-8")

    def read_named_tag(self):
        tag_type = self.unpack(">B")
        if tag_type == TAG_END:
            return (TAG_END, "", None)
        name = self.read_string()
        return (tag_type, name, self.read_payload(tag_type))

    def read_payload(self, tag_type: int):
        if tag_type == TAG_BYTE:
            return self.unpack(">b")
        if tag_type == TAG_SHORT:
            return self.unpack(">h")
        if tag_type == TAG_INT:
            return self.unpack(">i")
        if tag_type == TAG_LONG:
            return self.unpack(">q")
        if tag_type == TAG_FLOAT:
            return self.unpack(">f")
        if tag_type == TAG_DOUBLE:
            return self.unpack(">d")
        if tag_type == TAG_BYTE_ARRAY:
            return self.read(self.unpack(">i"))
        if tag_type == TAG_STRING:
            return self.read_string()
        if tag_type == TAG_LIST:
            child_type = self.unpack(">B")
            length = self.unpack(">i")
            return (child_type, [self.read_payload(child_type) for _ in range(length)])
        if tag_type == TAG_COMPOUND:
            items = []
            while True:
                child_type = self.unpack(">B")
                if child_type == TAG_END:
                    return items
                items.append((child_type, self.read_string(), self.read_payload(child_type)))
        if tag_type == TAG_INT_ARRAY:
            return [self.unpack(">i") for _ in range(self.unpack(">i"))]
        if tag_type == TAG_LONG_ARRAY:
            return [self.unpack(">q") for _ in range(self.unpack(">i"))]
        raise ValueError(f"unsupported NBT tag type {tag_type}")


def _pack_string(value: str) -> bytes:
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _write_payload(tag_type: int, payload) -> bytes:
    if tag_type == TAG_BYTE:
        return struct.pack(">b", payload)
    if tag_type == TAG_SHORT:
        return struct.pack(">h", payload)
    if tag_type == TAG_INT:
        return struct.pack(">i", payload)
    if tag_type == TAG_LONG:
        return struct.pack(">q", payload)
    if tag_type == TAG_FLOAT:
        return struct.pack(">f", payload)
    if tag_type == TAG_DOUBLE:
        return struct.pack(">d", payload)
    if tag_type == TAG_BYTE_ARRAY:
        return struct.pack(">i", len(payload)) + bytes(payload)
    if tag_type == TAG_STRING:
        return _pack_string(payload)
    if tag_type == TAG_LIST:
        child_type, items = payload
        return struct.pack(">Bi", child_type, len(items)) + b"".join(_write_payload(child_type, item) for item in items)
    if tag_type == TAG_COMPOUND:
        return b"".join(_write_named_tag(child_type, name, item) for child_type, name, item in payload) + b"\x00"
    if tag_type == TAG_INT_ARRAY:
        return struct.pack(">i", len(payload)) + b"".join(struct.pack(">i", item) for item in payload)
    if tag_type == TAG_LONG_ARRAY:
        return struct.pack(">i", len(payload)) + b"".join(struct.pack(">q", item) for item in payload)
    raise ValueError(f"unsupported NBT tag type {tag_type}")


def _write_named_tag(tag_type: int, name: str, payload) -> bytes:
    return struct.pack(">B", tag_type) + _pack_string(name) + _write_payload(tag_type, payload)


def _read_gzip_nbt(path: Path):
    return NbtReader(gzip.decompress(path.read_bytes())).read_named_tag()


def _write_gzip_nbt(path: Path, root) -> None:
    path.write_bytes(gzip.compress(_write_named_tag(*root)))


def _find_compound_item(compound: list, name: str):
    for index, item in enumerate(compound):
        if item[1] == name:
            return index, item
    return None, None


def _set_compound_item(compound: list, name: str, tag_type: int, payload) -> None:
    index, _ = _find_compound_item(compound, name)
    item = (tag_type, name, payload)
    if index is None:
        compound.append(item)
    else:
        compound[index] = item


def _data_compound(root):
    tag_type, _, payload = root
    if tag_type != TAG_COMPOUND:
        raise ValueError("level.dat root is not a compound")
    _, data_item = _find_compound_item(payload, "Data")
    if data_item is None or data_item[0] != TAG_COMPOUND:
        raise ValueError("level.dat is missing Data compound")
    return data_item[2]


def update_level_dat(path: Path, level_name: str, allow_commands: bool = True) -> None:
    root = _read_gzip_nbt(path)
    data = _data_compound(root)
    _set_compound_item(data, "LevelName", TAG_STRING, level_name)
    _set_compound_item(data, "allowCommands", TAG_BYTE, 1 if allow_commands else 0)
    _write_gzip_nbt(path, root)


def read_level_dat_summary(path: Path) -> dict:
    data = _data_compound(_read_gzip_nbt(path))
    summary = {}
    for key in ("LevelName", "allowCommands"):
        _, item = _find_compound_item(data, key)
        if item is not None:
            summary[key] = item[2]
    return summary


def _clean_lines(text: str) -> list[str]:
    return text.splitlines()


def patch_options(options_path: Path) -> bool:
    options_path.parent.mkdir(parents=True, exist_ok=True)
    original = options_path.read_text() if options_path.exists() else ""
    seen = set()
    output = []
    for line in _clean_lines(original):
        if ":" not in line:
            output.append(line)
            continue
        key, _ = line.split(":", 1)
        if key in OPTION_OVERRIDES:
            output.append(f"{key}:{OPTION_OVERRIDES[key]}")
            seen.add(key)
        else:
            output.append(line)
    for key, value in OPTION_OVERRIDES.items():
        if key not in seen:
            output.append(f"{key}:{value}")
    updated = "\n".join(output) + ("\n" if output else "")
    if updated != original:
        options_path.write_text(updated)
        return True
    return False


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n")


def build_hotbar_views_function() -> str:
    lines = []
    for view in HOTBAR_VIEWS.values():
        slot = view["slot"]
        lines.append(
            f"execute if entity @s[nbt={{SelectedItemSlot:{slot}}},tag=!ms_hotbar_{slot}] run function {DATAPACK_NAME}:{view['function']}"
        )
    for index in range(9):
        lines.append(f"tag @s remove ms_hotbar_{index}")
    for view in HOTBAR_VIEWS.values():
        slot = view["slot"]
        lines.append(f"execute if entity @s[nbt={{SelectedItemSlot:{slot}}}] run tag @s add ms_hotbar_{slot}")
    return "\n".join(lines) + "\n"


def build_manifest(world_dir: Path) -> dict:
    commands = {name: f"/function {DATAPACK_NAME}:{name}" for name in FUNCTIONS}
    return {
        "world_name": world_dir.name,
        "world_dir": str(world_dir),
        "datapack": DATAPACK_NAME,
        "pack_format": PACK_FORMAT,
        "targets": TARGETS,
        "commands": commands,
        "hotbar_views": HOTBAR_VIEWS,
        "fullscreen_gate": FULLSCREEN_GATE,
        "open_key": "key.keyboard.u",
        "launch_command": f'open -a "Prism Launcher" --args -l dev -w "{world_dir.name}"',
    }


def install_datapack(world_dir: Path) -> dict:
    datapack = world_dir / "datapacks" / DATAPACK_NAME
    if datapack.exists():
        shutil.rmtree(datapack)

    manifest = build_manifest(world_dir)
    _write_text(datapack / "pack.mcmeta", json.dumps({"pack": {"pack_format": PACK_FORMAT, "description": "Magic Storage GUI test rig"}}, indent=2))
    _write_text(datapack / "data/minecraft/tags/function/load.json", json.dumps({"values": [f"{DATAPACK_NAME}:setup"]}, indent=2))
    _write_text(datapack / "data/minecraft/tags/function/tick.json", json.dumps({"values": [f"{DATAPACK_NAME}:tick"]}, indent=2))
    function_bodies = dict(FUNCTIONS)
    function_bodies["hotbar_views"] = build_hotbar_views_function()
    for name, body in function_bodies.items():
        _write_text(datapack / "data" / DATAPACK_NAME / "function" / f"{name}.mcfunction", body)
    _write_text(datapack / "magic_storage_gui_test_manifest.json", json.dumps(manifest, indent=2))
    return manifest


def world_has_open_files(world_dir: Path) -> bool:
    if not world_dir.exists():
        return False
    lsof = shutil.which("lsof") or "/usr/sbin/lsof"
    try:
        result = subprocess.run([lsof, "+D", str(world_dir)], text=True, capture_output=True)
    except FileNotFoundError as exc:
        raise RuntimeError("lsof not found; cannot safely check whether the Prism world is open") from exc
    if result.returncode == 0:
        return bool(result.stdout.strip())
    if result.returncode == 1:
        return False
    detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
    raise RuntimeError(f"lsof failed while checking {world_dir}: {detail}")


def _copy_template_world(source: Path, target: Path) -> None:
    source = source.resolve()
    target = target.resolve()
    if not source.is_dir():
        raise RuntimeError(f"source world not found: {source}")
    if world_has_open_files(source):
        raise RuntimeError(f"source world appears to be open; quit Minecraft before copying: {source}")
    if target.exists():
        marker = target / MARKER_FILE
        if not marker.exists():
            raise RuntimeError(f"target world exists but is not marked as generated: {target}")
        if world_has_open_files(target):
            raise RuntimeError(f"target world appears to be open; quit Minecraft before recreating: {target}")
        shutil.rmtree(target)
    shutil.copytree(source, target)
    for relative in ("playerdata", "advancements", "stats"):
        path = target / relative
        if path.exists():
            shutil.rmtree(path)
    (target / MARKER_FILE).write_text("generated by scripts/prepare_prism_gui_world.py\n")


def prepare_world(
    minecraft_dir: Path = DEFAULT_PRISM_MINECRAFT_DIR,
    source_world: str = DEFAULT_SOURCE_WORLD,
    target_world: str = DEFAULT_WORLD_NAME,
) -> dict:
    minecraft_dir = minecraft_dir.expanduser().resolve()
    source = minecraft_dir / "saves" / source_world
    target = minecraft_dir / "saves" / target_world
    _copy_template_world(source, target)

    level_dat = target / "level.dat"
    if not level_dat.exists():
        raise RuntimeError(f"level.dat not found in generated world: {level_dat}")
    update_level_dat(level_dat, target_world, allow_commands=True)

    manifest = install_datapack(target)
    options_changed = patch_options(minecraft_dir / "options.txt")
    manifest.update(
        {
            "world_name": target_world,
            "world_dir": str(target),
            "source_world": source_world,
            "options_path": str(minecraft_dir / "options.txt"),
            "options_changed": options_changed,
            "level": read_level_dat_summary(level_dat),
        }
    )
    _write_text(target / "magic_storage_gui_test_manifest.json", json.dumps(manifest, indent=2))
    return manifest


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Prepare the Prism dev Magic Storage GUI test world.")
    parser.add_argument("--minecraft-dir", type=Path, default=DEFAULT_PRISM_MINECRAFT_DIR)
    parser.add_argument("--source-world", default=DEFAULT_SOURCE_WORLD)
    parser.add_argument("--world", default=DEFAULT_WORLD_NAME)
    args = parser.parse_args(argv[1:])
    try:
        result = prepare_world(args.minecraft_dir, args.source_world, args.world)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
