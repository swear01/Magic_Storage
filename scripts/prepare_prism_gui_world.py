#!/usr/bin/env python3
import argparse
import gzip
import json
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_PRISM_MINECRAFT_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev/minecraft"
DEFAULT_SOURCE_WORLD = "New World"
DEFAULT_WORLD_NAME = "MagicStorageGuiTest"
DEFAULT_OFFLINE_PLAYER = "MagicStorageBot"
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
        "User confirms the entire Minecraft frame is visible",
    ],
}

VOID_GENERATOR = {
    "type": "minecraft:flat",
    "biome": "minecraft:the_void",
    "layers": [{"height": 1, "block": "minecraft:air"}],
    "features": False,
    "lakes": False,
    "structure_overrides": [],
}

COPIED_RUNTIME_PATHS = (
    "region", "entities", "poi", "data", "datapacks",
    "DIM-1", "DIM1", "dimensions", "serverconfig",
    "playerdata", "advancements", "stats",
    "session.lock", "level.dat_old", "icon.png",
)

LAB = {
    "platform_y": 79,
    "reset_bounds": [-18, 79, -12, 18, 90, 12],
    "platform_bounds": [-18, 79, -12, 18, 79, 12],
    "spawn": [0, 80, 7],
}

TARGETS = {
    "storage_core": {"block": [0, 80, 0], "stand": [0.5, 80.0, 4.5], "face": [0.5, 80.5, 0.5]},
    "storage_unit_t6": {"block": [0, 80, -1], "stand": [4.5, 80.0, -0.5], "face": [0.5, 80.5, -0.5]},
    "storage_terminal": {"block": [-1, 80, 0], "stand": [-0.5, 80.0, 4.5], "face": [-0.5, 80.5, 0.5]},
    "crafting_terminal": {"block": [1, 80, 0], "stand": [1.5, 80.0, 4.5], "face": [1.5, 80.5, 0.5]},
    "import_bus": {"block": [-1, 80, -1], "stand": [-4.5, 80.0, -0.5], "face": [-0.5, 80.5, -0.5]},
    "export_bus": {"block": [1, 80, -1], "stand": [4.5, 80.0, -0.5], "face": [1.5, 80.5, -0.5]},
    "texture_gallery": {"block": [0, 80, -9], "stand": [0.5, 84.0, -3.5], "face": [0.5, 80.5, -8.5]},
    "overview": {"stand": [0.5, 85.0, 9.5], "face": [0.5, 80.0, -1.5]},
}

GALLERY = [
    {"x": -10, "y": 80, "z": -9, "block": "magic_storage:storage_core"},
    {"x": -8, "y": 80, "z": -9, "block": "magic_storage:storage_unit_t1"},
    {"x": -6, "y": 80, "z": -9, "block": "magic_storage:storage_unit_t2"},
    {"x": -4, "y": 80, "z": -9, "block": "magic_storage:storage_unit_t3"},
    {"x": -2, "y": 80, "z": -9, "block": "magic_storage:storage_unit_t4"},
    {"x": 0, "y": 80, "z": -9, "block": "magic_storage:storage_unit_t5"},
    {"x": 2, "y": 80, "z": -9, "block": "magic_storage:storage_unit_t6"},
    {"x": 4, "y": 80, "z": -9, "block": "magic_storage:storage_terminal"},
    {"x": 6, "y": 80, "z": -9, "block": "magic_storage:crafting_terminal"},
    {"x": 8, "y": 80, "z": -9, "block": "magic_storage:import_bus[facing=south]"},
    {"x": 10, "y": 80, "z": -9, "block": "magic_storage:export_bus[facing=south]"},
]

BASELINE = {
    "stored_items": {
        "minecraft:cobblestone": 8192,
        "minecraft:oak_log": 192,
        "minecraft:iron_ingot": 128,
        "minecraft:diamond": 32,
        "minecraft:coal": 64,
        "minecraft:blaze_rod": 16,
        "minecraft:glass_bottle": 16,
        "minecraft:netherite_upgrade_smithing_template": 1,
        "minecraft:diamond_sword": 1,
        "minecraft:netherite_ingot": 4,
    },
    "installed_stations": {
        "5": "minecraft:crafting_table",
        "6": "minecraft:stonecutter",
        "7": "minecraft:smithing_table",
        "8": "minecraft:iron_axe",
    },
    "energy": {
        "smelting_energy": 0,
        "blasting_energy": 0,
        "smoking_energy": 0,
        "campfire_energy": 0,
        "brew_energy": 0,
        "furnace_fuel": 0,
        "blaze_fuel": 0,
        "bottle_fuel": 0,
    },
    "total_type_capacity": 785,
}

PLAYER_KIT = {
    "hotbar": {
        "1": {"slot": "hotbar.0", "item": "magic_storage:storage_terminal", "count": 1},
        "2": {"slot": "hotbar.1", "item": "magic_storage:crafting_terminal", "count": 1},
        "3": {"slot": "hotbar.2", "item": "magic_storage:storage_core", "count": 1},
        "4": {"slot": "hotbar.3", "item": "magic_storage:storage_unit_t6", "count": 1},
        "5": {"slot": "hotbar.4", "item": "magic_storage:import_bus", "count": 1},
        "6": {"slot": "hotbar.5", "item": "magic_storage:export_bus", "count": 1},
        "7": {"slot": "hotbar.6", "item": "minecraft:spyglass", "count": 1},
        "8": {"slot": "hotbar.7", "item": "minecraft:compass", "count": 1},
        "9": {"slot": "hotbar.8", "item": "minecraft:barrier", "count": 1},
    },
    "inventory": [
        {"slot": "inventory.0", "item": "magic_storage:remote_terminal", "count": 1},
        {"slot": "inventory.1", "item": "minecraft:cobblestone", "count": 64},
        {"slot": "inventory.2", "item": "minecraft:oak_log", "count": 64},
        {"slot": "inventory.3", "item": "minecraft:furnace", "count": 3},
        {"slot": "inventory.4", "item": "minecraft:blast_furnace", "count": 1},
        {"slot": "inventory.5", "item": "minecraft:smoker", "count": 1},
        {"slot": "inventory.6", "item": "minecraft:campfire", "count": 1},
        {"slot": "inventory.7", "item": "minecraft:brewing_stand", "count": 1},
        {"slot": "inventory.8", "item": "minecraft:coal", "count": 64},
        {"slot": "inventory.9", "item": "minecraft:blaze_rod", "count": 16},
        {"slot": "inventory.10", "item": "minecraft:glass_bottle", "count": 16},
        {"slot": "inventory.11", "item": "minecraft:crafting_table", "count": 1},
        {"slot": "inventory.12", "item": "minecraft:stonecutter", "count": 1},
        {"slot": "inventory.13", "item": "minecraft:smithing_table", "count": 1},
        {"slot": "inventory.14", "item": "minecraft:iron_axe", "count": 1},
        {"slot": "inventory.15", "item": "minecraft:netherite_upgrade_smithing_template", "count": 1},
        {"slot": "inventory.16", "item": "minecraft:diamond_sword", "count": 1},
        {"slot": "inventory.17", "item": "minecraft:netherite_ingot", "count": 4},
        {
            "slot": "inventory.18",
            "item": 'minecraft:iron_axe[minecraft:damage=100,minecraft:enchantments={levels:{"minecraft:unbreaking":2}}]',
            "count": 1,
        },
        {
            "slot": "inventory.19",
            "item": "minecraft:iron_axe[minecraft:unbreakable={}]",
            "count": 1,
        },
    ],
}

HOTBAR_VIEWS = {
    "1": {"slot": 0, "function": "view_storage_terminal", "target": "storage_terminal"},
    "2": {"slot": 1, "function": "view_crafting_terminal", "target": "crafting_terminal"},
    "3": {"slot": 2, "function": "view_storage_core", "target": "storage_core"},
    "4": {"slot": 3, "function": "view_storage_unit_t6", "target": "storage_unit_t6"},
    "5": {"slot": 4, "function": "view_import_bus", "target": "import_bus"},
    "6": {"slot": 5, "function": "view_export_bus", "target": "export_bus"},
    "7": {"slot": 6, "function": "view_texture_gallery", "target": "texture_gallery"},
    "8": {"slot": 7, "function": "home", "target": "overview"},
    "9": {"slot": 8, "function": "reset_from_hotbar", "target": "reset"},
}

STATIC_FUNCTIONS = {
    "load": """
scoreboard objectives add ms_gui_timer dummy
function magic_storage_gui_test:setup
""",
    "tick": """
execute as @a[tag=!ms_gui_ready] unless score @s ms_gui_timer matches 0.. run scoreboard players set @s ms_gui_timer 0
scoreboard players add @a[tag=!ms_gui_ready] ms_gui_timer 1
execute as @a[tag=!ms_gui_ready,scores={ms_gui_timer=3..}] run function magic_storage_gui_test:player_ready
execute as @a[tag=ms_gui_ready] run function magic_storage_gui_test:hotbar_views
""",
    "reset_from_hotbar": """
function magic_storage_gui_test:setup
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


def _remove_compound_item(compound: list, name: str) -> None:
    index, _ = _find_compound_item(compound, name)
    if index is not None:
        compound.pop(index)


def _data_compound(root):
    tag_type, _, payload = root
    if tag_type != TAG_COMPOUND:
        raise ValueError("level.dat root is not a compound")
    _, data_item = _find_compound_item(payload, "Data")
    if data_item is None or data_item[0] != TAG_COMPOUND:
        raise ValueError("level.dat is missing Data compound")
    return data_item[2]


def _require_compound(compound: list, name: str) -> list:
    _, item = _find_compound_item(compound, name)
    if item is None or item[0] != TAG_COMPOUND:
        raise ValueError(f"level.dat is missing {name} compound")
    return item[2]


def _require_item(compound: list, name: str, tag_type: int):
    _, item = _find_compound_item(compound, name)
    if item is None or item[0] != tag_type:
        raise ValueError(f"level.dat is missing {name} tag type {tag_type}")
    return item[2]


def _rewrite_overworld_as_true_void(data: list) -> None:
    worldgen = _require_compound(data, "WorldGenSettings")
    dimensions = _require_compound(worldgen, "dimensions")
    overworld = _require_compound(dimensions, "minecraft:overworld")
    void_settings = [
        (TAG_LIST, "structure_overrides", (TAG_STRING, [])),
        (TAG_LIST, "layers", (TAG_COMPOUND, [[
            (TAG_INT, "height", 1),
            (TAG_STRING, "block", "minecraft:air"),
        ]])),
        (TAG_BYTE, "lakes", 0),
        (TAG_BYTE, "features", 0),
        (TAG_STRING, "biome", "minecraft:the_void"),
    ]
    generator = [
        (TAG_STRING, "type", "minecraft:flat"),
        (TAG_COMPOUND, "settings", void_settings),
    ]
    _set_compound_item(overworld, "generator", TAG_COMPOUND, generator)


def update_level_dat(
    path: Path,
    level_name: str,
    allow_commands: bool = True,
) -> None:
    root = _read_gzip_nbt(path)
    data = _data_compound(root)
    _set_compound_item(data, "LevelName", TAG_STRING, level_name)
    _set_compound_item(data, "allowCommands", TAG_BYTE, 1 if allow_commands else 0)
    _set_compound_item(data, "SpawnX", TAG_INT, LAB["spawn"][0])
    _set_compound_item(data, "SpawnY", TAG_INT, LAB["spawn"][1])
    _set_compound_item(data, "SpawnZ", TAG_INT, LAB["spawn"][2])
    _rewrite_overworld_as_true_void(data)
    _remove_compound_item(data, "Player")
    _write_gzip_nbt(path, root)


def read_level_dat_summary(path: Path) -> dict:
    data = _data_compound(_read_gzip_nbt(path))
    summary = {}
    for key in ("LevelName", "allowCommands"):
        _, item = _find_compound_item(data, key)
        if item is not None:
            summary[key] = item[2]
    return summary


def read_spawn_summary(path: Path) -> dict:
    data = _data_compound(_read_gzip_nbt(path))
    return {
        "x": _require_item(data, "SpawnX", TAG_INT),
        "y": _require_item(data, "SpawnY", TAG_INT),
        "z": _require_item(data, "SpawnZ", TAG_INT),
    }


def read_void_generator_summary(path: Path) -> dict:
    data = _data_compound(_read_gzip_nbt(path))
    worldgen = _require_compound(data, "WorldGenSettings")
    dimensions = _require_compound(worldgen, "dimensions")
    overworld = _require_compound(dimensions, "minecraft:overworld")
    generator = _require_compound(overworld, "generator")
    settings = _require_compound(generator, "settings")
    layer_type, layers = _require_item(settings, "layers", TAG_LIST)
    if layer_type != TAG_COMPOUND:
        raise ValueError("level.dat true-void layers must be compounds")
    structure_type, structures = _require_item(settings, "structure_overrides", TAG_LIST)
    if structure_type != TAG_STRING:
        raise ValueError("level.dat true-void structure_overrides must be strings")
    return {
        "type": _require_item(generator, "type", TAG_STRING),
        "biome": _require_item(settings, "biome", TAG_STRING),
        "layers": [
            {
                "height": _require_item(layer, "height", TAG_INT),
                "block": _require_item(layer, "block", TAG_STRING),
            }
            for layer in layers
        ],
        "features": bool(_require_item(settings, "features", TAG_BYTE)),
        "lakes": bool(_require_item(settings, "lakes", TAG_BYTE)),
        "structure_overrides": list(structures),
    }


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
        staging = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                prefix=f".{options_path.name}.",
                suffix=".staging",
                dir=options_path.parent,
                delete=False,
            ) as staging_file:
                staging = Path(staging_file.name)
                staging_file.write(updated)
            staging.replace(options_path)
            staging = None
        finally:
            if staging is not None and staging.exists():
                staging.unlink()
        return True
    return False


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n")


def build_core_command() -> str:
    energy = ",".join(f"{name}:{amount}L" for name, amount in BASELINE["energy"].items())
    machines = ",".join(
        f'{{Slot:{slot}b,id:"{item}",count:1}}'
        for slot, item in BASELINE["installed_stations"].items()
    )
    inventory = ",".join(
        f'{{item:{{id:"{item}",count:1}},count:{amount}L}}'
        for item, amount in BASELINE["stored_items"].items()
    )
    return (
        "setblock 0 80 0 magic_storage:storage_core"
        f"{{energy:{{{energy}}},machines:{{Items:[{machines}]}},inventory:[{inventory}]}}"
    )


def build_setup_function() -> str:
    lines = [
        "gamerule commandBlockOutput false",
        "gamerule sendCommandFeedback false",
        "gamerule doDaylightCycle false",
        "gamerule doWeatherCycle false",
        "gamerule doMobSpawning false",
        "time set noon",
        "weather clear",
        "difficulty peaceful",
        "fill -18 80 -12 18 90 12 minecraft:air",
        "fill -18 79 -12 18 79 12 minecraft:polished_blackstone_bricks outline",
        "fill -17 79 -11 17 79 11 minecraft:smooth_stone",
        "kill @e[type=minecraft:item,x=-18,y=79,z=-12,dx=36,dy=11,dz=24]",
        "setworldspawn 0 80 7",
        "spawnpoint @a 0 80 7",
        build_core_command(),
        "setblock -1 80 0 magic_storage:storage_terminal",
        "setblock 1 80 0 magic_storage:crafting_terminal",
    ]
    for z, tier in zip(range(-1, -7, -1), range(6, 0, -1)):
        lines.append(f"setblock 0 80 {z} magic_storage:storage_unit_t{tier}")
    lines.extend([
        "setblock -1 80 -1 magic_storage:import_bus[facing=west]",
        "setblock -2 80 -1 minecraft:barrel",
        "setblock 1 80 -1 magic_storage:export_bus[facing=east]",
        "setblock 2 80 -1 minecraft:barrel",
    ])
    for entry in GALLERY:
        lines.append(f"setblock {entry['x']} {entry['y']} {entry['z']} {entry['block']}")
    lines.extend([
        "tag @a remove ms_gui_ready",
        "scoreboard players reset @a ms_gui_timer",
    ])
    for index in range(9):
        lines.append(f"tag @a remove ms_hotbar_{index}")
    return "\n".join(lines) + "\n"


def build_player_ready_function() -> str:
    lines = [
        "gamemode creative @s",
        "effect give @s minecraft:night_vision infinite 0 true",
        "clear @s",
    ]
    for entry in PLAYER_KIT["hotbar"].values():
        lines.append(
            f"item replace entity @s {entry['slot']} with {entry['item']} {entry['count']}"
        )
    for entry in PLAYER_KIT["inventory"]:
        lines.append(
            f"item replace entity @s {entry['slot']} with {entry['item']} {entry['count']}"
        )
    lines.extend([
        "spawnpoint @s 0 80 7",
        f"function {DATAPACK_NAME}:view_storage_terminal",
        f"function {DATAPACK_NAME}:prime_hotbar_latch",
        "tag @s add ms_gui_ready",
        "say MS_GUI_TEST_READY",
    ])
    return "\n".join(lines) + "\n"


def _view_function(target_name: str) -> str:
    target = TARGETS[target_name]
    stand = " ".join(str(value) for value in target["stand"])
    face = " ".join(str(value) for value in target["face"])
    return f"tp @s {stand} facing {face}\n"


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


def build_prime_hotbar_latch_function() -> str:
    lines = [f"tag @s remove ms_hotbar_{index}" for index in range(9)]
    for view in HOTBAR_VIEWS.values():
        slot = view["slot"]
        lines.append(
            f"execute if entity @s[nbt={{SelectedItemSlot:{slot}}}] run tag @s add ms_hotbar_{slot}"
        )
    return "\n".join(lines) + "\n"


def build_function_bodies() -> dict[str, str]:
    bodies = dict(STATIC_FUNCTIONS)
    bodies.update({
        "setup": build_setup_function(),
        "player_ready": build_player_ready_function(),
        "hotbar_views": build_hotbar_views_function(),
        "prime_hotbar_latch": build_prime_hotbar_latch_function(),
        "home": _view_function("overview"),
        "view_storage_core": _view_function("storage_core"),
        "view_storage_unit_t6": _view_function("storage_unit_t6"),
        "view_storage_terminal": _view_function("storage_terminal"),
        "view_crafting_terminal": _view_function("crafting_terminal"),
        "view_import_bus": _view_function("import_bus"),
        "view_export_bus": _view_function("export_bus"),
        "view_texture_gallery": _view_function("texture_gallery"),
    })
    return bodies


def build_manifest(world_dir: Path) -> dict:
    commands = {name: f"/function {DATAPACK_NAME}:{name}" for name in build_function_bodies()}
    return {
        "schema_version": 2,
        "world_name": world_dir.name,
        "world_dir": str(world_dir),
        "datapack": DATAPACK_NAME,
        "pack_format": PACK_FORMAT,
        "world_generator": VOID_GENERATOR,
        "stripped_template_paths": list(COPIED_RUNTIME_PATHS),
        "lab": LAB,
        "targets": TARGETS,
        "gallery": GALLERY,
        "baseline": BASELINE,
        "player_kit": PLAYER_KIT,
        "bootstrap": {
            "ready_delay_ticks": 3,
            "ready_log": "MS_GUI_TEST_READY",
            "reset_function": "setup",
        },
        "commands": commands,
        "hotbar_views": HOTBAR_VIEWS,
        "fullscreen_gate": FULLSCREEN_GATE,
        "open_key": "key.keyboard.u",
        "launch_command": f'open -a "Prism Launcher" --args -l dev -w "{world_dir.name}" -o {DEFAULT_OFFLINE_PLAYER}',
    }


def install_datapack(world_dir: Path) -> dict:
    datapack = world_dir / "datapacks" / DATAPACK_NAME
    if datapack.exists():
        shutil.rmtree(datapack)

    manifest = build_manifest(world_dir)
    _write_text(datapack / "pack.mcmeta", json.dumps({"pack": {"pack_format": PACK_FORMAT, "description": "Magic Storage true-void GUI test lab"}}, indent=2))
    _write_text(datapack / "data/minecraft/tags/function/load.json", json.dumps({"values": [f"{DATAPACK_NAME}:load"]}, indent=2))
    _write_text(datapack / "data/minecraft/tags/function/tick.json", json.dumps({"values": [f"{DATAPACK_NAME}:tick"]}, indent=2))
    function_bodies = build_function_bodies()
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


def _stage_template_world(source: Path, target: Path) -> Path:
    source = source.resolve()
    target = target.resolve()
    if source == target:
        raise RuntimeError("source and target worlds must be different directories")
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
    staging = Path(tempfile.mkdtemp(prefix=f".{target.name}.staging-", dir=target.parent))
    shutil.rmtree(staging)
    try:
        shutil.copytree(source, staging)
        for relative in COPIED_RUNTIME_PATHS:
            path = staging / relative
            if path.is_dir() and not path.is_symlink():
                shutil.rmtree(path)
            elif path.exists() or path.is_symlink():
                path.unlink()
        (staging / MARKER_FILE).write_text("generated by scripts/prepare_prism_gui_world.py\n")
        return staging
    except BaseException:
        if staging.exists():
            shutil.rmtree(staging)
        raise


def _swap_staged_world(staging: Path, target: Path) -> None:
    if target.exists():
        backup = Path(tempfile.mkdtemp(prefix=f".{target.name}.backup-", dir=target.parent))
        shutil.rmtree(backup)
        target.rename(backup)
        try:
            staging.rename(target)
        except BaseException:
            backup.rename(target)
            raise
        shutil.rmtree(backup)
    else:
        staging.rename(target)


def _copy_template_world(source: Path, target: Path) -> None:
    target = target.resolve()
    staging = _stage_template_world(source, target)
    try:
        _swap_staged_world(staging, target)
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def prepare_world(
    minecraft_dir: Path = DEFAULT_PRISM_MINECRAFT_DIR,
    source_world: str = DEFAULT_SOURCE_WORLD,
    target_world: str = DEFAULT_WORLD_NAME,
) -> dict:
    minecraft_dir = minecraft_dir.expanduser().resolve()
    source = minecraft_dir / "saves" / source_world
    target = minecraft_dir / "saves" / target_world
    staging = _stage_template_world(source, target)
    try:
        level_dat = staging / "level.dat"
        if not level_dat.exists():
            raise RuntimeError(f"level.dat not found in generated world: {level_dat}")
        update_level_dat(level_dat, target_world, allow_commands=True)
        world_generator = read_void_generator_summary(level_dat)
        if world_generator != VOID_GENERATOR:
            raise ValueError(f"generated overworld does not match the true-void contract: {world_generator}")

        manifest = install_datapack(staging)
        options_path = minecraft_dir / "options.txt"
        options_changed = patch_options(options_path)
        manifest.update(
            {
                "world_name": target_world,
                "world_dir": str(target),
                "source_world": source_world,
                "options_path": str(options_path),
                "options_changed": options_changed,
                "level": read_level_dat_summary(level_dat),
                "world_generator": world_generator,
                "launch_command": build_manifest(target)["launch_command"],
            }
        )
        manifest_text = json.dumps(manifest, indent=2)
        _write_text(staging / "magic_storage_gui_test_manifest.json", manifest_text)
        _write_text(
            staging / "datapacks" / DATAPACK_NAME / "magic_storage_gui_test_manifest.json",
            manifest_text,
        )
        _swap_staged_world(staging, target)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
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
