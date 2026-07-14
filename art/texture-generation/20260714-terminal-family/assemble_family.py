from pathlib import Path
import hashlib
import json

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[2]
PALETTE_HEX = [
    "#0E1016",
    "#161922",
    "#20242F",
    "#2A303D",
    "#353D4C",
    "#465164",
    "#5B687D",
    "#AAB6CC",
    "#3FDCE5",
    "#2EA8FF",
    "#9A5CE8",
    "#C083FF",
    "#FF8A24",
    "#FFC060",
]
PALETTE = [tuple(bytes.fromhex(color[1:])) for color in PALETTE_HEX]
VOID, SHADOW, DARK, MID, CASING, EDGE, HIGHLIGHT, LIGHT = PALETTE[:8]
CYAN, BLUE, PURPLE, LIGHT_PURPLE, ORANGE, LIGHT_ORANGE = PALETTE[8:]
CHASSIS_POINTS = sorted({
    (x, y)
    for y in range(16)
    for x in range(16)
    if x in (0, 1, 14, 15) or y in (0, 1, 14, 15)
} | {(2, 2), (13, 2), (2, 13), (13, 13)})
TIER_POSITIONS = [(x, 13) for x in range(5, 11)]
SELECTED = ROOT / "selected"
METADATA = ROOT / "metadata"


def nearest(rgb):
    return min(PALETTE, key=lambda color: sum((value - target) ** 2 for value, target in zip(rgb, color)))


def quantized(relative):
    source = Image.open(ROOT / relative).convert("RGBA")
    output = Image.new("RGBA", source.size)
    for y in range(source.height):
        for x in range(source.width):
            red, green, blue, alpha = source.getpixel((x, y))
            output.putpixel((x, y), (*nearest((red, green, blue)), alpha))
    return output


def apply_chassis(image, chassis):
    for point in CHASSIS_POINTS:
        image.putpixel(point, chassis.getpixel(point))


def panel(draw):
    draw.rectangle((3, 3, 12, 12), fill=EDGE)
    draw.rectangle((4, 4, 11, 11), fill=SHADOW)


def core(image):
    draw = ImageDraw.Draw(image)
    panel(draw)
    for x, y in ((7, 4), (8, 4), (6, 5), (7, 5), (8, 5), (9, 5),
                 (5, 6), (6, 6), (7, 6), (8, 6), (9, 6), (10, 6),
                 (5, 7), (6, 7), (7, 7), (8, 7), (9, 7), (10, 7),
                 (5, 8), (6, 8), (7, 8), (8, 8), (9, 8), (10, 8),
                 (6, 9), (7, 9), (8, 9), (9, 9), (7, 10), (8, 10)):
        image.putpixel((x, y), PURPLE)
    for point in ((7, 5), (8, 5), (7, 6), (8, 6), (6, 7), (7, 7),
                  (8, 7), (9, 7), (7, 8), (8, 8), (7, 9), (8, 9)):
        image.putpixel(point, CYAN)
    image.putpixel((7, 6), LIGHT_PURPLE)


def storage_terminal(image):
    draw = ImageDraw.Draw(image)
    panel(draw)
    draw.rectangle((4, 4, 11, 10), fill=DARK)
    for left, top, color in ((5, 5, CYAN), (8, 5, BLUE), (5, 8, BLUE), (8, 8, CYAN)):
        draw.rectangle((left, top, left + 1, top + 1), fill=color)
    draw.point((10, 12), fill=PURPLE)


def crafting_terminal(image):
    draw = ImageDraw.Draw(image)
    panel(draw)
    draw.rectangle((4, 4, 11, 11), fill=DARK)
    for y in (5, 7, 9):
        for x in (5, 7, 9):
            draw.point((x, y), fill=PURPLE if (x, y) == (7, 7) else CYAN)
    draw.point((8, 7), fill=LIGHT_PURPLE)
    draw.line((10, 7, 11, 7), fill=BLUE)


def storage_unit(image, tier):
    draw = ImageDraw.Draw(image)
    panel(draw)
    draw.rectangle((5, 3, 10, 11), fill=EDGE)
    draw.rectangle((6, 4, 9, 10), fill=DARK)
    draw.rectangle((6, 7, 9, 10), fill=PURPLE)
    draw.rectangle((7, 5, 8, 9), fill=CYAN)
    draw.point((8, 5), fill=LIGHT_PURPLE)
    for index, point in enumerate(TIER_POSITIONS):
        image.putpixel(point, CYAN if index < tier else MID)


def bus_top(image):
    draw = ImageDraw.Draw(image)
    panel(draw)
    draw.line((7, 4, 7, 11), fill=CYAN)
    draw.line((4, 7, 11, 7), fill=CYAN)
    draw.rectangle((7, 7, 8, 8), fill=PURPLE)
    draw.point((8, 7), fill=LIGHT_PURPLE)


def bus_side(image):
    draw = ImageDraw.Draw(image)
    panel(draw)
    draw.rectangle((3, 6, 12, 9), fill=DARK)
    draw.line((3, 7, 12, 7), fill=CYAN)
    draw.rectangle((7, 6, 8, 8), fill=PURPLE)
    draw.point((8, 7), fill=LIGHT_PURPLE)


def directional_bus(image, inward):
    draw = ImageDraw.Draw(image)
    panel(draw)
    draw.rectangle((7, 6, 8, 9), fill=PURPLE)
    draw.rectangle((7, 7, 8, 8), fill=LIGHT_PURPLE)
    color = BLUE if inward else ORANGE
    bright = CYAN if inward else LIGHT_ORANGE
    if inward:
        points = ((3, 7), (4, 7), (5, 7), (6, 7), (5, 6), (5, 8),
                  (9, 7), (10, 7), (11, 7), (12, 7), (10, 6), (10, 8))
        tips = ((6, 7), (9, 7))
    else:
        points = ((3, 7), (4, 7), (5, 7), (6, 7), (4, 6), (4, 8),
                  (9, 7), (10, 7), (11, 7), (12, 7), (11, 6), (11, 8))
        tips = ((3, 7), (12, 7))
    for point in points:
        draw.point(point, fill=color)
    for point in tips:
        draw.point(point, fill=bright)


def remote(image):
    draw = ImageDraw.Draw(image)
    for point in ((0, 0), (15, 0), (0, 15), (15, 15)):
        image.putpixel(point, (0, 0, 0, 0))
    draw.rectangle((3, 2, 12, 10), fill=EDGE)
    draw.rectangle((4, 3, 11, 9), fill=SHADOW)
    draw.rectangle((5, 4, 10, 7), fill=DARK)
    draw.rectangle((5, 4, 7, 5), fill=CYAN)
    draw.rectangle((8, 4, 10, 5), fill=BLUE)
    draw.rectangle((5, 6, 7, 7), fill=BLUE)
    draw.rectangle((8, 6, 10, 7), fill=CYAN)
    draw.rectangle((4, 11, 6, 13), fill=PURPLE)
    draw.point((5, 11), fill=LIGHT_PURPLE)
    draw.line((8, 12, 11, 12), fill=CYAN)


def normalize_metadata(group, candidate):
    source = ROOT / f"candidates/{group}/{candidate}.json"
    original = json.loads(source.read_text())
    output = {
        "created_at_utc": original["created_at_utc"],
        "type": original["type"],
        "subject": original["subject"],
        "model": original["model"],
        "style": original["style"],
        "tileable": original["tileable"],
        "prompt": original["prompt"],
        "size": original["size"],
        "seed": original["seed"],
        "reference_image": "selected/chassis.png",
        "img2img": original["img2img"],
        "input": original["input"],
        "prediction": f"candidates/{group}/{candidate}.raw.png",
        "selected_candidate": f"candidates/{group}/{candidate}.png",
        "postprocess": "nearest shared palette, shared chassis mask, semantic pixel cleanup",
    }
    path = METADATA / f"{group}.json"
    path.write_text(json.dumps(output, indent=2) + "\n")
    return path.relative_to(ROOT).as_posix()


def normalize_candidate_metadata():
    def normalized(value):
        if isinstance(value, dict):
            return {key: normalized(nested) for key, nested in value.items()}
        if isinstance(value, list):
            return [normalized(nested) for nested in value]
        if isinstance(value, str) and value.startswith("/"):
            path = Path(value).resolve()
            try:
                return path.relative_to(ROOT).as_posix()
            except ValueError as error:
                raise ValueError(f"candidate metadata path escapes family root: {path}") from error
        return value

    for path in sorted((ROOT / "candidates").rglob("*.json")):
        path.write_text(json.dumps(normalized(json.loads(path.read_text())), indent=2) + "\n")


def control_atlas():
    image = Image.new("RGBA", (256, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    white = (255, 255, 255, 255)

    def points(index, values):
        left = index * 16
        for x, y in values:
            draw.point((left + x, y), fill=white)

    ascending = {(3, y) for y in range(3, 13)} | {(2, 4), (4, 4)}
    ascending |= {(x, 4) for x in range(7, 13)} | {(x, 8) for x in range(7, 12)}
    ascending |= {(x, 12) for x in range(7, 10)}
    points(0, ascending)
    descending = {(3, y) for y in range(3, 13)} | {(2, 11), (4, 11)}
    descending |= {(x, 4) for x in range(7, 10)} | {(x, 8) for x in range(7, 12)}
    descending |= {(x, 12) for x in range(7, 13)}
    points(1, descending)
    name = {(3, y) for y in range(5, 13)} | {(10, y) for y in range(5, 13)}
    name |= {(x, 4) for x in range(4, 10)} | {(x, 8) for x in range(3, 11)}
    points(2, name)
    quantity = {(x, y) for x in (3, 4) for y in range(10, 13)}
    quantity |= {(x, y) for x in (7, 8) for y in range(7, 13)}
    quantity |= {(x, y) for x in (11, 12) for y in range(4, 13)}
    points(3, quantity)
    mod = {(x, y) for left, top in ((3, 3), (9, 3), (3, 9), (9, 9))
           for x in range(left, left + 3) for y in range(top, top + 3)}
    points(4, mod)
    tag = {(x, y) for x in (5, 10) for y in range(3, 13)}
    tag |= {(x, y) for y in (6, 10) for x in range(3, 13)}
    points(5, tag)
    search = {(4, 3), (5, 2), (6, 2), (7, 2), (8, 3), (3, 4), (3, 5),
              (3, 6), (4, 7), (5, 8), (6, 8), (7, 8), (8, 7), (9, 6),
              (9, 5), (9, 4), (9, 7), (10, 8), (11, 9), (12, 10), (13, 11)}
    points(6, search)
    points(7, tag)
    at = {(x, 3) for x in range(5, 10)} | {(x, 11) for x in range(5, 10)}
    at |= {(4, y) for y in range(4, 11)} | {(10, y) for y in range(4, 10)}
    at |= {(7, 6), (8, 6), (6, 7), (8, 7), (6, 8), (7, 8), (8, 8), (9, 8), (10, 8)}
    points(8, at)
    previous = {(3, 7), (4, 6), (4, 8), (5, 5), (5, 9), (6, 4), (6, 10)}
    previous |= {(x, 7) for x in range(4, 13)}
    points(9, previous)
    points(10, {(15 - x, y) for x, y in previous})
    path = SELECTED / "terminal_controls.png"
    image.save(path)
    return path


def save_member(name, source, transform):
    image = quantized(source)
    chassis = Image.open(SELECTED / "chassis.png").convert("RGBA")
    apply_chassis(image, chassis)
    transform(image)
    path = SELECTED / f"{name}.png"
    image.save(path)
    return path


def contact_sheet(paths):
    scale = 12
    cell = 16 * scale
    label_height = 28
    columns = 4
    rows = (len(paths) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * (cell + 18), rows * (cell + label_height)), (234, 234, 234, 255))
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(paths):
        left = (index % columns) * (cell + 18)
        top = (index // columns) * (cell + label_height)
        image = Image.open(path).convert("RGBA").resize((cell, cell), Image.Resampling.NEAREST)
        sheet.alpha_composite(image, (left, top))
        draw.rectangle((left, top, left + cell - 1, top + cell - 1), outline=(55, 55, 55, 255))
        draw.text((left + 2, top + cell + 4), path.stem, fill=(25, 25, 25, 255))
    sheet.save(ROOT / "selected-contact-sheet.png")


def main():
    SELECTED.mkdir(parents=True, exist_ok=True)
    METADATA.mkdir(parents=True, exist_ok=True)
    normalize_candidate_metadata()
    member_paths = {}
    member_paths["storage_core"] = save_member(
        "storage_core", "candidates/storage_core/storage_core_02.png", core)
    member_paths["storage_terminal"] = save_member(
        "storage_terminal", "candidates/storage_terminal/storage_terminal_02.png", storage_terminal)
    member_paths["crafting_terminal"] = save_member(
        "crafting_terminal", "candidates/crafting_terminal/crafting_terminal_01.png", crafting_terminal)
    for tier in range(1, 7):
        member_paths[f"storage_unit_t{tier}"] = save_member(
            f"storage_unit_t{tier}",
            "candidates/storage_unit_base/storage_unit_base_02.png",
            lambda image, tier=tier: storage_unit(image, tier),
        )
    for prefix in ("import", "export"):
        member_paths[f"{prefix}_bus_top"] = save_member(
            f"{prefix}_bus_top", "candidates/bus_top/bus_top_01.png", bus_top)
        member_paths[f"{prefix}_bus_side"] = save_member(
            f"{prefix}_bus_side", "candidates/bus_side/bus_side_01.png", bus_side)
    member_paths["import_bus_front"] = save_member(
        "import_bus_front", "candidates/import_bus_front/import_bus_front_01.png",
        lambda image: directional_bus(image, True))
    member_paths["export_bus_front"] = save_member(
        "export_bus_front", "candidates/export_bus_front/export_bus_front_01.png",
        lambda image: directional_bus(image, False))
    remote_image = quantized("candidates/remote_terminal/remote_terminal_02.png")
    remote(remote_image)
    member_paths["remote_terminal"] = SELECTED / "remote_terminal.png"
    remote_image.save(member_paths["remote_terminal"])
    control_atlas()

    metadata = {
        "storage_core": normalize_metadata("storage_core", "storage_core_02"),
        "storage_terminal": normalize_metadata("storage_terminal", "storage_terminal_02"),
        "crafting_terminal": normalize_metadata("crafting_terminal", "crafting_terminal_01"),
        "storage_unit": normalize_metadata("storage_unit_base", "storage_unit_base_02"),
        "bus_top": normalize_metadata("bus_top", "bus_top_01"),
        "bus_side": normalize_metadata("bus_side", "bus_side_01"),
        "import_bus_front": normalize_metadata("import_bus_front", "import_bus_front_01"),
        "export_bus_front": normalize_metadata("export_bus_front", "export_bus_front_01"),
        "remote_terminal": normalize_metadata("remote_terminal", "remote_terminal_02"),
    }
    roles = {
        "storage_core": "core_rune_crystal",
        "storage_terminal": "storage_item_grid",
        "crafting_terminal": "crafting_grid_mark",
        **{f"storage_unit_t{tier}": f"storage_cell_tier_{tier}" for tier in range(1, 7)},
        "import_bus_top": "import_casing_top",
        "import_bus_side": "import_casing_side",
        "import_bus_front": "import_inward_arrow",
        "export_bus_top": "export_casing_top",
        "export_bus_side": "export_casing_side",
        "export_bus_front": "export_outward_arrow",
        "remote_terminal": "remote_display",
    }
    metadata_for = {
        "storage_core": metadata["storage_core"],
        "storage_terminal": metadata["storage_terminal"],
        "crafting_terminal": metadata["crafting_terminal"],
        **{f"storage_unit_t{tier}": metadata["storage_unit"] for tier in range(1, 7)},
        "import_bus_top": metadata["bus_top"],
        "export_bus_top": metadata["bus_top"],
        "import_bus_side": metadata["bus_side"],
        "export_bus_side": metadata["bus_side"],
        "import_bus_front": metadata["import_bus_front"],
        "export_bus_front": metadata["export_bus_front"],
        "remote_terminal": metadata["remote_terminal"],
    }
    members = {}
    for name, role in roles.items():
        category = "item" if name == "remote_terminal" else "block"
        texture_id = f"magic_storage:{category}/{name}"
        runtime = f"src/main/resources/assets/magic_storage/textures/{category}/{name}.png"
        source = member_paths[name]
        members[texture_id] = {
            "role": role,
            "runtime": runtime,
            "source": source.relative_to(ROOT).as_posix(),
            "metadata": metadata_for[name],
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        }
    icons = [
        "SORT_ASCENDING", "SORT_DESCENDING", "SORT_NAME", "SORT_QUANTITY", "SORT_MOD",
        "SORT_ID", "SEARCH", "SEARCH_TAG", "SEARCH_MOD", "PREVIOUS", "NEXT",
    ]
    manifest = {
        "schema": 1,
        "model": "retro-diffusion/rd-fast",
        "runtime_size": [16, 16],
        "settings": {
            "seed": 71421,
            "block_img2img_strength": 0.38,
            "item_img2img_strength": 0.68,
            "style": "mc_texture/mc_item",
        },
        "palette": PALETTE_HEX,
        "chassis": {
            "source": "selected/chassis.png",
            "metadata": "metadata/chassis.json",
            "selected_candidate": "candidates/chassis/shared_chassis_04.png",
            "points": [list(point) for point in CHASSIS_POINTS],
        },
        "tier_bar": {
            "positions": [list(point) for point in TIER_POSITIONS],
            "active": "#3FDCE5",
            "inactive": "#2A303D",
        },
        "members": members,
        "control_atlas": {
            "runtime": "src/main/resources/assets/magic_storage/textures/gui/terminal_controls.png",
            "source": "selected/terminal_controls.png",
            "cell_size": 16,
            "icons": [
                {"name": name, "atlas_index": index}
                for index, name in enumerate(icons)
            ],
            "sha256": hashlib.sha256((SELECTED / "terminal_controls.png").read_bytes()).hexdigest(),
        },
        "contact_sheets": ["chassis-contact-sheet.png", "candidate-contact-sheet.png", "selected-contact-sheet.png"],
    }
    (ROOT / "selection.json").write_text(json.dumps(manifest, indent=2) + "\n")
    contact_sheet([member_paths[name] for name in roles])


if __name__ == "__main__":
    main()
