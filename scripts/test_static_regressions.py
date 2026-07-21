from pathlib import Path
import hashlib
import json
import re
import struct
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
FUSION_PACK = ROOT / "src/main/resources/resourcepacks/fusion_connected_casing"


class StaticRegressionTests(unittest.TestCase):
    def read_required(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.exists(), f"missing {relative_path}")
        return path.read_text()

    def png_dimensions(self, path: Path) -> tuple[int, int]:
        with path.open("rb") as texture_file:
            header = texture_file.read(24)
        self.assertEqual(b"\x89PNG\r\n\x1a\n", header[:8], f"not a PNG: {path}")
        self.assertEqual(b"IHDR", header[12:16], f"missing PNG IHDR: {path}")
        return int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")

    def rgba_png_pixels(self, path: Path) -> tuple[int, int, list[tuple[int, int, int, int]]]:
        payload = path.read_bytes()
        self.assertEqual(b"\x89PNG\r\n\x1a\n", payload[:8], f"not a PNG: {path}")
        offset = 8
        width = height = None
        compressed = bytearray()
        while offset < len(payload):
            length = struct.unpack(">I", payload[offset:offset + 4])[0]
            chunk_type = payload[offset + 4:offset + 8]
            chunk = payload[offset + 8:offset + 8 + length]
            offset += length + 12
            if chunk_type == b"IHDR":
                width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
                    ">IIBBBBB", chunk
                )
                self.assertEqual((8, 6, 0, 0, 0),
                                 (bit_depth, color_type, compression, filtering, interlace),
                                 f"expected non-interlaced RGBA8 PNG: {path}")
            elif chunk_type == b"IDAT":
                compressed.extend(chunk)
            elif chunk_type == b"IEND":
                break
        self.assertIsNotNone(width, f"missing PNG dimensions: {path}")
        raw = zlib.decompress(bytes(compressed))
        stride = width * 4
        previous = bytearray(stride)
        pixels = []
        cursor = 0
        for _ in range(height):
            filter_type = raw[cursor]
            cursor += 1
            scanline = bytearray(raw[cursor:cursor + stride])
            cursor += stride
            for index in range(stride):
                left = scanline[index - 4] if index >= 4 else 0
                up = previous[index]
                upper_left = previous[index - 4] if index >= 4 else 0
                if filter_type == 1:
                    scanline[index] = (scanline[index] + left) & 0xFF
                elif filter_type == 2:
                    scanline[index] = (scanline[index] + up) & 0xFF
                elif filter_type == 3:
                    scanline[index] = (scanline[index] + ((left + up) // 2)) & 0xFF
                elif filter_type == 4:
                    prediction = left + up - upper_left
                    distances = (
                        abs(prediction - left),
                        abs(prediction - up),
                        abs(prediction - upper_left),
                    )
                    predictor = (left, up, upper_left)[distances.index(min(distances))]
                    scanline[index] = (scanline[index] + predictor) & 0xFF
                elif filter_type != 0:
                    self.fail(f"unsupported PNG filter {filter_type}: {path}")
            pixels.extend(tuple(scanline[index:index + 4]) for index in range(0, stride, 4))
            previous = scanline
        return width, height, pixels

    def expected_texture_family(self) -> dict[str, str]:
        return {
            "magic_storage:block/storage_core": "core_rune_crystal",
            "magic_storage:block/storage_terminal": "storage_item_grid",
            "magic_storage:block/crafting_terminal": "crafting_grid_mark",
            **{
                f"magic_storage:block/storage_unit_t{tier}": f"storage_cell_tier_{tier}"
                for tier in range(1, 7)
            },
            "magic_storage:block/creative_storage_unit": "creative_infinity_cell",
            "magic_storage:block/import_bus_top": "import_casing_top",
            "magic_storage:block/import_bus_side": "import_casing_side",
            "magic_storage:block/import_bus_front": "import_inward_arrow",
            "magic_storage:block/export_bus_top": "export_casing_top",
            "magic_storage:block/export_bus_side": "export_casing_side",
            "magic_storage:block/export_bus_front": "export_outward_arrow",
            "magic_storage:item/remote_terminal": "remote_display",
        }

    def expected_connected_texture_family(self) -> set[str]:
        return {
            "magic_storage:block/storage_core_connected",
            *{
                f"magic_storage:block/storage_unit_t{tier}_connected"
                for tier in range(1, 7)
            },
            "magic_storage:block/creative_storage_unit_connected",
            "magic_storage:block/storage_terminal_connected",
            "magic_storage:block/crafting_terminal_connected",
            "magic_storage:block/import_bus_top_connected",
            "magic_storage:block/import_bus_side_connected",
            "magic_storage:block/export_bus_top_connected",
            "magic_storage:block/export_bus_side_connected",
        }

    def java_block(self, text: str, declaration: str, description: str) -> str:
        match = re.search(declaration, text, re.MULTILINE)
        if match is None:
            self.fail(f"missing {description}")
        opening = text.find("{", match.end())
        if opening < 0:
            self.fail(f"missing body for {description}")
        depth = 0
        for index in range(opening, len(text)):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
                if depth == 0:
                    return text[opening + 1:index]
        self.fail(f"unterminated body for {description}")

    def nested_java_classes(self, text: str) -> list[tuple[str, str]]:
        classes = []
        declaration = re.compile(
            r"^[ \t]{4,}(?:(?:private|protected|public)\s+)?"
            r"(?:static\s+)?(?:final\s+)?class\s+([A-Za-z_]\w*)\b",
            re.MULTILINE,
        )
        for match in declaration.finditer(text):
            classes.append((
                match.group(1),
                self.java_block(text[match.start():], declaration.pattern, match.group(1)),
            ))
        return classes

    def java_int_constant(self, text: str, name: str) -> int:
        seen = set()
        current = name
        while current not in seen:
            seen.add(current)
            match = re.search(
                rf"\b{re.escape(current)}\s*=\s*(\d+|[A-Z][A-Z0-9_]*)\s*;",
                text,
            )
            if match is None:
                self.fail(f"missing integer constant {current}")
            value = match.group(1)
            if value.isdigit():
                return int(value)
            current = value
        self.fail(f"cyclic integer constant starting at {name}")

    def test_every_gametest_class_is_registered_with_neoforge(self):
        java_root = ROOT / "src/main/java"
        unregistered = []
        for path in sorted(java_root.rglob("*.java")):
            text = path.read_text()
            if "@GameTest(" in text and "@GameTestHolder(" not in text:
                unregistered.append(str(path.relative_to(ROOT)))
        self.assertEqual(
            [],
            unregistered,
            "GameTest methods compile but never execute without @GameTestHolder",
        )

    def test_terminal_layout_has_one_profile_driven_entrypoint(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        profile = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalProfile.java"
        )
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        entrypoints = re.findall(
            r"^[ \t]{4}(?:(?:public|protected)\s+)?static\s+Geometry\s+"
            r"([A-Za-z_]\w*)\s*\(([^)]*)\)",
            layout,
            re.MULTILINE,
        )
        self.assertEqual(
            1,
            len(entrypoints),
            f"TerminalLayout must expose one non-private Geometry entrypoint, found {[name for name, _ in entrypoints]}",
        )
        entrypoint, parameters = entrypoints[0]
        self.assertIn("TerminalProfile", parameters)
        self.assertIsNone(
            re.search(r"\bstatic\s+Geometry\s+(?:storage|crafting)\s*\(", layout),
            "TerminalLayout.storage()/crafting() split entrypoints must be removed",
        )
        self.assertTrue(
            f"TerminalLayout.{entrypoint}(" in storage + crafting,
            f"terminal screens must use TerminalLayout.{entrypoint}()",
        )
        self.assertIn("static final TerminalProfile STORAGE", profile)
        self.assertIn("static final TerminalProfile CRAFTING", profile)
        self.assertTrue(
            "TerminalProfile.STORAGE" in storage,
            "StorageTerminalScreen must select the reduced STORAGE profile",
        )
        self.assertTrue(
            "TerminalProfile.CRAFTING" in crafting,
            "CraftingTerminalScreen must select the CRAFTING profile",
        )

    def test_shared_shell_alone_creates_common_terminal_controls(self):
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        self.assertFalse(
            "setViewButtonsVisible(false)" in crafting,
            "CraftingTerminalScreen must not hide inherited common controls after super.init()",
        )
        for duplicate in ["sortOrderRailBtn", "sortModeRailBtn", "searchModeRailBtn"]:
            self.assertFalse(
                duplicate in crafting,
                f"CraftingTerminalScreen must not recreate inherited {duplicate}",
            )
        for control in ["sortOrderBtn", "sortModeBtn", "searchModeBtn"]:
            self.assertIn(f"{control} = addCycleButton(", storage)
        for button_id in [11, 12, 13]:
            self.assertIsNone(
                re.search(rf"clickMenuButton\(\s*{button_id}\s*\)", crafting),
                f"CraftingTerminalScreen must not recreate common button id {button_id}",
            )
        for action in [
            "SORT_ORDER_BUTTON",
            "NEXT_SORT_MODE_BUTTON",
            "PREVIOUS_SORT_MODE_BUTTON",
            "NEXT_SEARCH_MODE_BUTTON",
            "PREVIOUS_SEARCH_MODE_BUTTON",
        ]:
            self.assertNotIn(action, crafting)

    def test_terminal_controls_use_18px_hitboxes_and_16px_icon_canvas(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        self.assertEqual(18, self.java_int_constant(layout, "CONTROL_SIZE"))
        self.assertEqual(18, self.java_int_constant(layout, "RAIL_BUTTON_SIZE"))
        self.assertEqual(16, self.java_int_constant(layout, "ICON_CANVAS_SIZE"))

        rail_buttons = self.java_block(
            layout,
            r"\bprivate\s+static\s+List<Rect>\s+railButtons\s*\(",
            "TerminalLayout.railButtons",
        )
        self.assertRegex(
            rail_buttons,
            r"new\s+Rect\([\s\S]*?RAIL_BUTTON_SIZE\s*,\s*RAIL_BUTTON_SIZE\s*\)",
        )

    def test_terminal_rail_icons_are_not_text_glyphs_or_procedural_shapes(self):
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        screens = storage + crafting
        for old_glyph in ["⌕", "#", "@", "≡", "MOD", "ID", "↓", "↑"]:
            self.assertFalse(
                f'Component.literal("{old_glyph}")' in screens,
                f"shared rail must not use the {old_glyph!r} text glyph as an icon",
            )
        self.assertFalse(
            "drawRailIcon(" in screens,
            "rail icons must use shared texture/item rendering, not procedural drawRailIcon shapes",
        )
        self.assertIsNone(
            re.search(r"\bclass\s+TerminalIconButton\b", crafting),
            "CraftingTerminalScreen must use the shared shell icon control",
        )
        self.assertIn("TERMINAL_CONTROLS_TEXTURE", storage)
        self.assertRegex(
            storage,
            r"graphics\.blit\(\s*TERMINAL_CONTROLS_TEXTURE",
        )

        icon_controls = [
            body
            for _, body in self.nested_java_classes(storage)
            if "renderWidget(" in body
            and re.search(r"graphics\.(?:blit|blitSprite|renderItem)\(", body)
        ]
        self.assertTrue(icon_controls, "shared shell must own a texture/item-backed icon control")
        for control in icon_controls:
            self.assertNotIn("graphics.fill(", control)

    def test_network_amount_renderer_uses_one_screen_wide_scale(self):
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        renderer = self.java_block(
            storage,
            r"\b(?:private|protected)\s+void\s+renderNetworkAmount\s*\(",
            "StorageTerminalScreen.renderNetworkAmount",
        )

        self.assertNotRegex(renderer, r"\b(?:large|small)\b")
        self.assertNotRegex(renderer, r"font\.width\(\s*text\s*\)\s*(?:<=|>=|<|>)")
        scale = re.search(
            r"graphics\.pose\(\)\.scale\(\s*([A-Za-z_][\w.]*)\s*,\s*\1\s*,\s*1(?:\.0)?F\s*\)",
            renderer,
        )
        self.assertIsNotNone(
            scale,
            "network amounts must always use one named screen-wide scale",
        )
        scale_name = scale.group(1).rsplit(".", 1)[-1]
        self.assertIsNone(
            re.search(rf"\b(?:float|double)\s+{re.escape(scale_name)}\b", renderer),
            f"{scale_name} must not be recomputed inside the per-value renderer",
        )
        self.assertGreaterEqual(
            (storage + layout).count(scale_name),
            2,
            f"{scale_name} must be declared outside the per-value renderer",
        )

    def test_shared_cycle_input_maps_click_and_wheel_directions(self):
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        direction = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalCycleDirection.java"
        )

        self.assertRegex(direction, r"case\s+0\s*->\s*NEXT\s*;")
        self.assertRegex(direction, r"case\s+1\s*->\s*PREVIOUS\s*;")
        self.assertRegex(
            direction,
            r"delta\s*<\s*0(?:\.0)?\s*\?\s*NEXT\s*:\s*PREVIOUS",
        )
        cycle_control = self.java_block(
            storage,
            r"\bclass\s+TerminalCycleButton\b",
            "StorageTerminalScreen.TerminalCycleButton",
        )
        self.assertRegex(cycle_control, r"button\s*==\s*0\s*\|\|\s*button\s*==\s*1")
        self.assertIn("TerminalCycleDirection.fromMouseButton(button)", cycle_control)
        self.assertIn("TerminalCycleDirection.fromScroll(scrollY)", cycle_control)
        self.assertRegex(
            cycle_control,
            r"(?:isMouseOver|clicked)\(\s*mouseX\s*,\s*mouseY\s*\)",
        )
        self.assertFalse(
            "import net.minecraft.client.gui.components.CycleButton;" in crafting,
            "CraftingTerminalScreen must use the shared cycle input instead of vanilla CycleButton",
        )
        self.assertFalse(
            "CycleButton<" in crafting,
            "CraftingTerminalScreen must not retain a vanilla CycleButton field",
        )
        self.assertRegex(crafting, r"\bTerminalCycleButton\s+fuelTargetSelector\b")

    def test_terminal_output_destination_is_distinct_from_emi_destination(self):
        destination = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalOutputDestination.java"
        )
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )

        self.assertRegex(destination, r"enum\s+TerminalOutputDestination\s*\{")
        self.assertRegex(destination, r"\bPLAYER\s*,")
        self.assertRegex(destination, r"\bSTORAGE\b")
        self.assertIn(
            "private TerminalOutputDestination outputDestination = TerminalOutputDestination.PLAYER;",
            menu,
        )
        self.assertIn("from(CraftingDestination destination)", menu)
        self.assertIn("from(TerminalOutputDestination destination)", menu)
        self.assertIn("DeliveryTarget.from(destination)", menu)
        self.assertIn("DeliveryTarget.from(outputDestination)", menu)

    def test_output_destination_is_a_server_synced_item_page_control(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        profile = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalProfile.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        self.assertIn("static final int OUTPUT_DESTINATION_BUTTON", menu)
        self.assertIn("case 7 -> outputDestination.ordinal();", menu)
        self.assertIn("case 7 -> outputDestination = TerminalOutputDestination.byId(value);", menu)
        self.assertIn(
            "buttonId == OUTPUT_DESTINATION_BUTTON",
            menu,
            "the server menu must own the output-destination transition",
        )
        self.assertIn("OUTPUT_DESTINATION", profile)
        self.assertIn("int outputDestinationIndex()", profile)
        self.assertIn("playerInventorySourceIndex() + 1", profile)
        self.assertIn("List.of(PAGE_CONTROL_COUNT, VIEW_CONTROL_COUNT, 2)", profile)
        self.assertRegex(screen, r"\bTerminalCycleButton\s+outputDestinationRailBtn\b")
        self.assertIn("MagicStorage.STORAGE_CORE_ITEM.get().getDefaultInstance()", screen)
        self.assertIn("CraftingTerminalMenu.OUTPUT_DESTINATION_BUTTON", screen)
        self.assertIn("setWidgetVisible(outputDestinationRailBtn, itemPage);", screen)

    def test_output_destination_tooltips_name_current_value_in_both_languages(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        en_us = json.loads(
            self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json")
        )
        zh_tw = json.loads(
            self.read_required("src/main/resources/assets/magic_storage/lang/zh_tw.json")
        )

        self.assertIn("gui.magic_storage.output_destination", screen)
        self.assertIn("gui.magic_storage.output_destination.player", screen)
        self.assertIn("gui.magic_storage.output_destination.storage", screen)
        expected = {
            "gui.magic_storage.output_destination",
            "gui.magic_storage.output_destination.player",
            "gui.magic_storage.output_destination.storage",
        }
        self.assertTrue(expected.issubset(en_us))
        self.assertTrue(expected.issubset(zh_tw))

    def test_fuel_page_distinguishes_stations_from_consumed_axe_energy(self):
        lang = json.loads(
            self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json")
        )
        self.assertEqual("Consumables", lang["gui.magic_storage.fuel_group.consumables"])
        self.assertEqual("Timed Stations", lang["gui.magic_storage.fuel_group.timed_stations"])
        self.assertEqual("Instant Stations", lang["gui.magic_storage.fuel_group.instant_stations"])
        self.assertEqual("Axe Energy", lang["gui.magic_storage.axe_energy"])
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        consumables_panel = screen[
            screen.index("private void renderConsumablesPanel"):
            screen.index("private void renderTimedStationsPanel")
        ]
        self.assertIn("MachineEnergyTable.Category.CONSUMABLE", consumables_panel)
        self.assertIn("menu.hasInfiniteDescriptor(entry.id())", consumables_panel)
        self.assertIn("menu.getDescriptorAmount(entry.id())", consumables_panel)

    def test_emi_uses_terminal_display_slot_contract_without_54_slot_hardcode(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java")
        self.assertNotIn("DISPLAY_SLOTS = 54", text)
        self.assertIn("StorageTerminalMenu.DISPLAY_SLOTS", text)
        self.assertNotRegex(text, r"canCraft\([^)]*\)\s*\{\s*return true;\s*\}")

    def test_emi_inventory_strips_terminal_display_metadata_and_keeps_exact_amount(self):
        text = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java"
        )
        self.assertIn("EmiPlayerInventory getInventory", text)
        self.assertIn("TerminalDisplayStack.strip(stack)", text)
        self.assertIn("TerminalDisplayStack.amount(stack)", text)

    def test_emi_requires_an_item_page_and_supported_exact_backing_recipe(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java")
        supports_recipe = text[text.index("public boolean supportsRecipe"):text.index("@Override", text.index("public boolean supportsRecipe"))]
        can_craft = text[text.index("public boolean canCraft"):text.index("@Override", text.index("public boolean canCraft"))]
        craft = text[text.index("public boolean craft"):]

        self.assertIn("RecipeHolder<?> backingRecipe = recipe.getBackingRecipe();", supports_recipe)
        self.assertIn("backingRecipe != null", supports_recipe)
        self.assertIn("RecipeHolder<?> currentHolder", supports_recipe)
        self.assertIn("byKey(backingRecipe.id())", supports_recipe)
        self.assertIn("currentHolder == backingRecipe", supports_recipe)
        self.assertIn("CraftingTerminalMenu.supportsRecipeHolder(currentHolder)", supports_recipe)
        self.assertNotIn("supportsRecipeContract", supports_recipe)
        self.assertIn("getPage().isItemPage()", can_craft)
        self.assertIn("supportsRecipe(recipe)", can_craft)
        self.assertIn("!menu.getPage().isItemPage()", craft)
        self.assertIn("supportsRecipe(recipe)", craft)

    def test_recipe_family_policy_is_owned_only_by_complete_built_in_adapter_matches(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        adapters = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/BuiltInRecipeAdapters.java"
        )
        match = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RecipeAdapterMatch.java"
        )

        self.assertNotIn("SUPPORTED_RECIPE_TYPES", menu)
        self.assertNotIn("CraftingStationTable.", menu)
        self.assertNotIn("RecipeEnergyTable.", menu)
        compatibility_start = menu.index("public static boolean supportsRecipeContract")
        compatibility_end = menu.index("\n    }", compatibility_start) + len("\n    }")
        operational_menu = menu[:compatibility_start] + menu[compatibility_end:]
        self.assertNotIn("supportsRecipeContract(", operational_menu)
        self.assertIn("BuiltInRecipeAdapters.registry().classify", menu[compatibility_start:compatibility_end])
        for family_policy in (
            "AbstractCookingRecipe",
            "ShapedRecipe",
            "ShapelessRecipe",
            "SmithingRecipeInput",
            "SmithingTransformRecipe",
            "SmithingTrimRecipe",
            "AxeTransformationRecipe",
        ):
            self.assertNotIn(family_policy, menu)
            self.assertIn(family_policy, adapters)
        for obligation in (
            "orderedInputs",
            "stationDescriptorId",
            "energyCost",
            "toolCost",
            "checkedOutput",
            "remainders",
            "presentation",
            "resolveVariants",
            "validatesSimulation",
            "validatesCommit",
        ):
            self.assertIn(obligation, match)
        self.assertIn("getCookingTime()", adapters)
        self.assertIn("SmithingRecipeInput", adapters)
        self.assertIn("MachineEnergyTable.AXE_ID", adapters)
        self.assertFalse((ROOT / "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingStationTable.java").exists())
        self.assertFalse((ROOT / "src/main/java/com/swearprom/magicstorage/magic_storage/RecipeEnergyTable.java").exists())

    def test_smithing_weak_cache_values_do_not_retain_recipe_keys(self):
        adapters = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/BuiltInRecipeAdapters.java"
        )

        self.assertRegex(
            adapters,
            r"Map<Recipe<\?>,\s*SmithingRepresentatives>\s+SMITHING_INPUT_CACHE",
        )
        self.assertRegex(
            adapters,
            r"private record SmithingRepresentatives\(\s*"
            r"List<ItemStack> templates,\s*"
            r"List<ItemStack> bases,\s*"
            r"List<ItemStack> additions\s*\)",
        )
        cache_values = self.java_block(
            adapters,
            r"private static SmithingRepresentatives smithingRepresentatives\(",
            "detached smithing cache value factory",
        )
        self.assertIn("SMITHING_INPUT_CACHE.computeIfAbsent", cache_values)
        self.assertNotIn("RecipeAdapterMatch.Input", cache_values)
        self.assertNotIn("SmithingInputIdentity", cache_values)
        self.assertNotRegex(cache_values, r"recipe::is(?:Template|Base|Addition)Ingredient")

        smithing_inputs = self.java_block(
            adapters,
            r"private static List<RecipeAdapterMatch\.Input> smithingInputs\(",
            "transient exact smithing inputs",
        )
        self.assertEqual(3, smithing_inputs.count("new SmithingInputIdentity(recipe,"))
        self.assertEqual(3, len(re.findall(
            r"recipe::is(?:Template|Base|Addition)Ingredient",
            smithing_inputs,
        )))

    def test_emi_sends_context_amount_and_destination_for_exact_backing_recipe(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java")
        self.assertIn("context.getAmount()", text)
        self.assertIn("context.getDestination()", text)
        self.assertIn("private static final int MAX_CRAFT_AMOUNT = 64;", text)
        self.assertIn(
            "int amount = Math.max(1, Math.min(context.getAmount(), MAX_CRAFT_AMOUNT));",
            text,
        )
        self.assertIn("case NONE -> CraftingDestination.NONE;", text)
        self.assertIn("case CURSOR -> CraftingDestination.CURSOR;", text)
        self.assertIn("case INVENTORY -> CraftingDestination.INVENTORY;", text)
        self.assertRegex(
            text,
            r"new CraftingRecipeSelectionPacket\(\s*menu\.containerId,\s*backingRecipe\.id\(\),\s*amount,\s*destination\s*\)",
        )

    def test_emi_recipe_request_has_no_visible_output_slot_gate(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java")
        self.assertNotIn("handleInventoryMouseClick", text)
        self.assertNotIn("findOutputSlot", text)
        self.assertNotIn("recipe.getOutputs()", text)
        self.assertNotIn("menu.getSlot(", text)
        self.assertNotIn("ItemStack.isSameItemSameComponents", text)

        entrypoint = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        self.assertIn("CraftingRecipeSelectionPacket.TYPE", entrypoint)
        self.assertIn(
            "menu.handleRecipeRequest(player.level(), packet.recipeId(), packet.amount(), packet.destination(), player);",
            entrypoint,
        )

    def test_release_requires_compatible_emi_client_range_without_exact_pin(self):
        build = self.read_required("build.gradle")
        properties = self.read_required("gradle.properties")
        metadata = self.read_required("src/main/templates/META-INF/neoforge.mods.toml")

        self.assertRegex(
            build,
            r'compileOnly\s+"dev\.emi:emi-neoforge:\$\{emi_version\}:api"',
        )
        self.assertRegex(
            build,
            r'fusionRuntimeRuntimeOnly\s+"maven\.modrinth:emi:\$\{emi_runtime_version\}"',
            "the isolated client/data runtime must use the Modrinth full EMI artifact",
        )
        self.assertNotRegex(
            build,
            r'(?m)^\s*runtimeOnly\s+"dev\.emi:emi-neoforge:',
            "dedicated server and GameTest must not receive the full EMI runtime",
        )
        self.assertIn("emiRuntime", build)
        self.assertRegex(
            build,
            r'emiRuntime\s+"maven\.modrinth:emi:\$\{emi_runtime_version\}"',
        )
        self.assertIn('tasks.register("stageEmiRuntime", Copy)', build)
        self.assertIn("from configurations.emiRuntime", build)
        self.assertIn('into layout.buildDirectory.dir("client-smoke-mods")', build)
        self.assertIn("def stagedEmiVersion = project.emi_version.toString()", build)
        self.assertIn('rename { "emi-neoforge-${stagedEmiVersion}.jar" }', build)
        self.assertNotIn('rename { "emi-neoforge-${emi_version}.jar" }', build)
        self.assertIn("emi_version=1.1.24+1.21.1", properties)
        self.assertIn("emi_runtime_version=5sIPA1To", properties)
        self.assertIn("emi_version_range=[1.1.24,2)", properties)
        self.assertNotRegex(
            build,
            r'(?m)^\s*(?:fusionRuntimeRuntimeOnly|emiRuntime)\s+"dev\.emi:',
            "TerraformersMC must supply only the dedicated compile API artifact",
        )
        self.assertIn("clientSmokePatchouli", build)
        self.assertIn("clientSmokeFusion", build)
        self.assertIn('tasks.register("stageClientSmokeSupportMods", Copy)', build)
        self.assertIn('rename { "patchouli-neoforge.jar" }', build)
        self.assertIn('rename { "fusion-connected-textures.jar" }', build)
        self.assertRegex(build, r"emi_version_range\s*:\s*emi_version_range")
        self.assertRegex(
            metadata,
            r'''(?s)\[\[dependencies\.\$\{mod_id\}\]\]\s*
\s*modId="emi"\s*
\s*type="required"\s*
\s*versionRange="\$\{emi_version_range\}"\s*
\s*ordering="NONE"\s*
\s*side="CLIENT"''',
        )
        self.assertNotIn('versionRange="[1.1.24]"', metadata)

    def test_build_script_uses_gradle_10_safe_repository_url_assignment(self):
        build = self.read_required("build.gradle")
        self.assertNotRegex(build, r'(?m)^\s*url\s+"')
        self.assertIn('url = uri("file://${project.projectDir}/repo")', build)

    def test_recipe_addon_gametest_gate_rejects_any_selftest_failure(self):
        build = self.read_required("build.gradle")
        self.assertIn("tasks.named('runGameTestServer').configure", build)
        self.assertIn("All 335 required tests passed", build)
        self.assertIn("All 5 required tests passed", build)
        self.assertIn("text.contains('TESTS FAILED!')", build)
        self.assertNotIn("SelfTest: 1 TESTS FAILED!", build)

    def test_mekanism_chemical_compat_is_optional_and_ci_exercised(self):
        build = self.read_required("build.gradle")
        properties = self.read_required("gradle.properties")
        metadata = self.read_required("src/main/templates/META-INF/neoforge.mods.toml")
        bootstrap = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/OptionalModCapabilities.java"
        )
        compat = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MekanismChemicalCompat.java"
        )
        fixture_metadata = self.read_required(
            "src/mekanismFixture/resources/META-INF/neoforge.mods.toml"
        )

        self.assertIn('compileOnly "maven.modrinth:mekanism:${mekanism_ci_version}"', build)
        self.assertIn(
            'mekanismFixtureRuntimeOnly "maven.modrinth:mekanism:${mekanism_ci_version}"',
            build,
        )
        self.assertNotRegex(build, r'(?m)^\s*runtimeOnly\s+"[^"]*mekanism')
        self.assertRegex(properties, r"(?m)^mekanism_ci_version=[A-Za-z0-9]+$")
        self.assertNotIn('modId="mekanism"', metadata)
        self.assertIn('ModList.get().isLoaded(MEKANISM_MOD_ID)', bootstrap)
        self.assertNotIn("import mekanism.", bootstrap)
        self.assertIn("Class.forName(MEKANISM_COMPAT_CLASS)", bootstrap)
        self.assertIn("WeakReference<IChemicalHandler>", compat)
        self.assertNotIn(
            "Map<StorageCoreBlockEntity, IChemicalHandler>",
            compat,
            "a weak-key map still leaks when each strongly held handler references its Core key",
        )
        self.assertIn("tasks.named('runMekanismGameTestServer').configure", build)
        self.assertIn("All 2 required tests passed", build)
        self.assertIn('modId="mekanism"', fixture_metadata)
        self.assertIn('versionRange="[10.7,)"', fixture_metadata)
        self.assertNotRegex(fixture_metadata, r'versionRange="\[10\.7\.\d')
        for source_set in ["recipeAddonFixture", "mekanismFixture"]:
            self.assertRegex(
                build,
                rf"(?s){source_set}\s*\{{.*?runtimeClasspath\s*\+=\s*"
                r"output\s*\+\s*sourceSets\.main\.runtimeClasspath.*?\}",
                f"{source_set} runtime must not inherit main compileOnly mods",
            )

    def test_items_share_the_universal_live_transaction_ledger(self):
        record = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRecord.java"
        )
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        bridge = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageResourceBridge.java"
        )
        self.assertNotIn("Object2LongOpenHashMap<ItemKey>", record)
        self.assertNotIn("Object2LongOpenHashMap<ItemKey>", core)
        self.assertNotIn("private Map<Item,", record)
        self.assertNotIn("private Map<Item,", core)
        self.assertIn("static final ResourceLocation ITEM_KIND", bridge)
        self.assertIn("StorageResourceBridge.itemKey(key", core)
        self.assertIn("resourceLedger.applyExact", core)

    def test_recipe_family_registry_freezes_before_selftests(self):
        entrypoint = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        self.assertRegex(
            entrypoint,
            r"event\.enqueueWork\(\(\) -> \{\s*RecipeAdapters\.snapshot\(\);\s*SelfTest\.runAll\(\);\s*}\);",
        )

    def test_recipe_renderer_boundary_keeps_emi_out_of_base_screen_and_native_path(self):
        interface = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RecipeDiagramRenderer.java"
        )
        native = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/NativeRecipeDiagramRenderer.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        setup = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/ClientSetup.java"
        )
        bootstrap = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/compat/EmiRecipeDiagramBootstrap.java"
        )

        for relative_path, text in [
            ("RecipeDiagramRenderer.java", interface),
            ("NativeRecipeDiagramRenderer.java", native),
            ("CraftingTerminalScreen.java", screen),
            ("ClientSetup.java", setup),
        ]:
            self.assertNotIn(
                "import dev.emi.", text,
                f"{relative_path} must not link EMI API classes",
            )
        self.assertIn('ModList.get().isLoaded("emi")', setup)
        self.assertIn("EmiRecipeDiagramBootstrap", setup)
        self.assertIn("RecipeDiagramRenderer", bootstrap)
        self.assertIn("EmiRecipeDiagramRenderer", bootstrap)
        self.assertIn("RecipeDiagramRenderer", screen)
        self.assertIn("NativeRecipeDiagramRenderer", screen)

    def test_emi_diagram_adapter_uses_only_public_recipe_widget_contracts(self):
        renderer = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/compat/EmiRecipeDiagramRenderer.java"
        )

        for public_api in [
            "dev.emi.emi.api.EmiApi",
            "dev.emi.emi.api.recipe.EmiRecipe",
            "dev.emi.emi.api.widget.Widget",
            "dev.emi.emi.api.widget.WidgetHolder",
        ]:
            self.assertIn(public_api, renderer)
        for internal_api in [
            "dev.emi.emi.screen",
            "WidgetGroup",
            "RecipeScreen",
            "EmiScreenManager",
            "EmiRenderHelper",
        ]:
            self.assertNotIn(internal_api, renderer)
        self.assertRegex(renderer, r"implements\s+WidgetHolder")
        self.assertRegex(renderer, r"List<Widget>")
        self.assertIn("recipe.addWidgets(", renderer)
        self.assertIn("widget.render(", renderer)
        self.assertIn("widget.getTooltip(", renderer)
        self.assertIn("widget.mouseClicked(", renderer)
        self.assertIn("widget.keyPressed(", renderer)

    def test_emi_compat_sources_never_link_internal_packages(self):
        compat_root = ROOT / "src/main/java/com/swearprom/magicstorage/magic_storage/compat"
        sources = "\n".join(path.read_text() for path in sorted(compat_root.glob("*.java")))

        self.assertNotIn("dev.emi.emi.bom", sources)
        self.assertNotIn("dev.emi.emi.screen", sources)
        self.assertNotIn("dev.emi.emi.runtime", sources)

    def test_emi_diagram_selection_is_exact_and_has_only_capability_fallbacks(self):
        setup = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/ClientSetup.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        native = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/NativeRecipeDiagramRenderer.java"
        )
        renderer = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/compat/EmiRecipeDiagramRenderer.java"
        )

        self.assertIn("NativeRecipeDiagramRenderer", setup)
        self.assertIn('ModList.get().isLoaded("emi")', setup)
        self.assertIn("preferredRecipeDiagramRenderer", screen)
        self.assertIn("nativeRecipeDiagramRenderer", screen)
        self.assertRegex(
            screen,
            r"preferredRecipeDiagramRenderer\.supports\([^)]*\)\s*"
            r"\?\s*preferredRecipeDiagramRenderer\s*:\s*nativeRecipeDiagramRenderer",
        )
        self.assertIn("return true;", native)
        self.assertIn("RecipePresentationKind.AXE", renderer)
        self.assertIn("EmiApi.getRecipeManager().getRecipe(presentation.recipeId())", renderer)
        self.assertIn("recipe.getId()", renderer)
        self.assertIn("recipe.getBackingRecipe()", renderer)
        self.assertIn("presentation.recipeId()", renderer)

    def test_emi_diagram_is_bounded_and_does_not_catch_into_native_rendering(self):
        renderer = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/compat/EmiRecipeDiagramRenderer.java"
        )

        self.assertIn("graphics.enableScissor(", renderer)
        self.assertIn("graphics.disableScissor()", renderer)
        self.assertIn("widget.getBounds()", renderer)
        self.assertRegex(renderer, r"diagram\.contains\(")
        self.assertNotRegex(
            renderer,
            r"catch\s*\(\s*(?:Throwable|Exception|RuntimeException)",
            "unexpected EMI failures must surface instead of silently selecting native rendering",
        )
        self.assertNotIn("new NativeRecipeDiagramRenderer", renderer)

    def test_crafting_screen_does_not_recompute_recipes_or_read_core_storage_client_side(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java")
        self.assertTrue(
            "menu.getRecipePresentation()" in text,
            "CraftingTerminalScreen must render the menu-synchronized RecipePresentation",
        )
        self.assertNotIn("findRecipesClient", text)
        self.assertNotIn("getCore(minecraft.level)", text)
        self.assertNotIn("RecipeManager mgr = minecraft.level.getRecipeManager()", text)
        self.assertNotIn("import net.minecraft.world.item.crafting.RecipeManager", text)
        self.assertNotIn("import net.minecraft.world.item.crafting.RecipeType", text)
        self.assertNotIn("StorageCoreBlockEntity", text)
        self.assertNotIn("level.getRecipeManager()", text)
        self.assertNotRegex(text, r"\bnew\s+RecipePresentation\b")
        self.assertNotRegex(
            text,
            r"\bRecipePresentation\.(?:build|create|fromRecipe)\s*\(",
        )

    def test_emi_does_not_expose_hidden_selection_slots_as_inputs(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java")
        self.assertNotIn("return handler.slots;", text)
        self.assertIn("PLAYER_INVENTORY_SLOTS", text)
        self.assertIn("StorageTerminalMenu.DISPLAY_SLOTS + PLAYER_INVENTORY_SLOTS", text)
        self.assertIn("CraftingTerminalPage.STORAGE", text)
        self.assertGreaterEqual(text.count("handler.getPage()"), 3)
        self.assertIn("handler.isUsePlayerInventory()", text)

    def test_terminal_resize_preserves_search_value_focus_and_debounce(self):
        text = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        self.assertIn("previousSearchValue", text)
        self.assertIn("previousSearchFocused", text)
        self.assertIn("searchBox.setValue(previousSearchValue)", text)
        self.assertIn("searchBox.setFocused(previousSearchFocused)", text)
        self.assertNotIn("this.searchTimer = 0;", text)

    def test_terminal_scrollbar_uses_real_texture_height_and_single_tooltip_pass(self):
        text = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        self.assertIn("SB_TEXTURE_HEIGHT = 16", text)
        self.assertIn("256, SB_TEXTURE_HEIGHT", text)
        self.assertNotIn("g.renderTooltip(font, hoveredSlot.getItem(), mx, my);", text)

    def test_terminal_scrollbar_sends_one_server_validated_absolute_packet(self):
        packet = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalScrollPacket.java"
        )
        self.assertIn('"terminal_scroll"', packet)
        self.assertGreaterEqual(packet.count("ByteBufCodecs.VAR_INT"), 2)

        entrypoint = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        self.assertIn("TerminalScrollPacket.TYPE", entrypoint)
        self.assertIn("menu.scrollTo(packet.offset())", entrypoint)

        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        self.assertIn("new TerminalScrollPacket(menu.containerId, target)", screen)
        self.assertNotIn("target = (target / 9) * 9", screen)
        self.assertNotIn("while (delta < 0)", screen)
        self.assertNotIn("while (delta >= 9)", screen)

    def test_terminal_packets_skip_identical_filter_and_layout_requests(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java"
        )
        self.assertIn("boolean applyFilter", menu)
        self.assertIn("boolean applySettings", menu)

        entrypoint = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        self.assertIn("menu.applyFilter(core, packet.filter())", entrypoint)
        self.assertIn("menu.applySettings(packet)", entrypoint)
        self.assertNotIn("menu.refreshDisplayItemsFiltered(core, packet.filter())", entrypoint)

    def test_terminal_open_buffers_use_core_access_remote_contract(self):
        menu = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java")
        self.assertGreaterEqual(menu.count("buf.readBlockPos()"), 2)
        self.assertIn("buf.readBoolean()", menu)
        terminal = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/TerminalBlock.java")
        self.assertIn("buf.writeBlockPos(core.getBlockPos())", terminal)
        self.assertIn("buf.writeBlockPos(pos)", terminal)
        self.assertIn("buf.writeBoolean(false)", terminal)
        remote = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/RemoteTerminalItem.java")
        self.assertIn("buf.writeBoolean(true)", remote)

    def test_buses_and_menus_use_action_actor_storage_contract(self):
        for relative_path in [
            "src/main/java/com/swearprom/magicstorage/magic_storage/ImportBusBlockEntity.java",
            "src/main/java/com/swearprom/magicstorage/magic_storage/ExportBusBlockEntity.java",
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java",
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java",
        ]:
            text = self.read_required(relative_path)
            self.assertIn("Action.", text, relative_path)
            self.assertIn("Actor.", text, relative_path)
        bus_text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/ImportBusBlockEntity.java")
        self.assertNotRegex(bus_text, r"core\.insertItem\([^;]+,\s*true\)")
        self.assertNotRegex(bus_text, r"core\.insertItem\([^;]+,\s*false\)")

    def test_selftest_does_not_reference_client_only_screens(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/SelfTest.java")
        self.assertNotIn("Screen", text)
        self.assertNotIn("AbstractContainerScreen", text)

    def test_client_screen_registration_avoids_deprecated_event_bus_subscriber_bus(self):
        client_setup = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/ClientSetup.java")
        self.assertNotIn("EventBusSubscriber", client_setup)
        self.assertNotIn("SubscribeEvent", client_setup)
        self.assertIn("import net.neoforged.bus.api.IEventBus;", client_setup)
        self.assertIn("public static void register(IEventBus modEventBus)", client_setup)
        self.assertIn("modEventBus.addListener(ClientSetup::registerScreens)", client_setup)

        magic_storage = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java")
        self.assertIn("import net.neoforged.api.distmarker.Dist;", magic_storage)
        self.assertIn("import net.neoforged.fml.loading.FMLEnvironment;", magic_storage)
        self.assertIn("FMLEnvironment.dist == Dist.CLIENT", magic_storage)
        self.assertIn("ClientSetup.register(modEventBus)", magic_storage)

    def test_directional_bus_mirror_avoids_deprecated_blockstate_rotate(self):
        for source in ["ImportBusBlock.java", "ExportBusBlock.java"]:
            text = self.read_required(f"src/main/java/com/swearprom/magicstorage/magic_storage/{source}")
            self.assertNotIn("state.rotate(", text, source)
            self.assertIn("state.setValue(FACING, mirror.mirror(state.getValue(FACING)))", text, source)

    def test_runtime_texture_family_is_complete_native_and_orphan_free(self):
        textures = ROOT / "src/main/resources/assets/magic_storage/textures"
        generation_artifacts = sorted(
            path.relative_to(ROOT).as_posix()
            for path in textures.rglob("*")
            if path.is_file() and (path.suffix == ".json" or path.name.endswith(".preview.png"))
        )
        self.assertEqual([], generation_artifacts)

        models = ROOT / "src/main/resources/assets/magic_storage/models"
        texture_ids = {
            texture_id
            for model_path in models.rglob("*.json")
            for texture_id in json.loads(model_path.read_text()).get("textures", {}).values()
            if isinstance(texture_id, str)
            and texture_id.startswith(("magic_storage:block/", "magic_storage:item/"))
        }
        self.assertTrue(texture_ids, "no gameplay texture references found in block/item models")
        expected_family = self.expected_texture_family()
        expected_connected = self.expected_connected_texture_family()
        expected_runtime = set(expected_family) | {"magic_storage:item/wrench"}
        self.assertEqual(expected_runtime, texture_ids)

        runtime_texture_ids = {
            f"magic_storage:{path.parent.name}/{path.stem}"
            for category in (textures / "block", textures / "item")
            for path in category.glob("*.png")
        }
        self.assertEqual(
            expected_runtime,
            runtime_texture_ids,
            "runtime block/item textures must contain exactly the model-referenced semantic family",
        )

        invalid_textures = []
        for texture_id in sorted(runtime_texture_ids):
            texture_path = textures / f"{texture_id.split(':', 1)[1]}.png"
            relative_path = texture_path.relative_to(ROOT).as_posix()
            if not texture_path.is_file():
                invalid_textures.append(f"missing {relative_path}")
                continue
            dimensions = self.png_dimensions(texture_path)
            expected_dimensions = (80, 16) if texture_id in expected_connected else (16, 16)
            if dimensions != expected_dimensions:
                invalid_textures.append(f"{relative_path} is {dimensions[0]}x{dimensions[1]}")

        self.assertEqual([], invalid_textures)
        overlay_textures = FUSION_PACK / "assets/magic_storage/textures"
        overlay_texture_ids = {
            f"magic_storage:{path.parent.name}/{path.stem}"
            for path in (overlay_textures / "block").glob("*.png")
        }
        self.assertEqual(expected_connected, overlay_texture_ids)
        runtime_gui = {
            path.name for path in (textures / "gui").glob("*.png")
        }
        self.assertEqual({"icons.png", "terminal_controls.png"}, runtime_gui)

    def test_texture_family_manifest_palette_chassis_and_control_atlas_are_reproducible(self):
        art = ROOT / "art/texture-generation/20260714-terminal-family"
        manifest_path = art / "selection.json"
        self.assertTrue(manifest_path.is_file(), f"missing {manifest_path.relative_to(ROOT)}")
        manifest = json.loads(manifest_path.read_text())
        absolute_metadata_paths = []
        for metadata_path in art.rglob("*.json"):
            def visit(value):
                if isinstance(value, dict):
                    for nested in value.values():
                        visit(nested)
                elif isinstance(value, list):
                    for nested in value:
                        visit(nested)
                elif isinstance(value, str) and value.startswith("/"):
                    absolute_metadata_paths.append(
                        f"{metadata_path.relative_to(ROOT)}: {value}"
                    )
            visit(json.loads(metadata_path.read_text()))
        self.assertEqual([], absolute_metadata_paths)
        expected_family = self.expected_texture_family()
        expected_connected = self.expected_connected_texture_family()
        self.assertEqual(2, manifest.get("schema"))
        self.assertEqual("retro-diffusion/rd-fast", manifest.get("model"))
        self.assertEqual([16, 16], manifest.get("runtime_size"))
        self.assertEqual(71421, manifest.get("settings", {}).get("seed"))
        self.assertEqual([71422, 71423, 71424, 71425], manifest.get("settings", {}).get("revision_seeds"))
        self.assertEqual(0.38, manifest.get("settings", {}).get("block_img2img_strength"))
        self.assertEqual(0.68, manifest.get("settings", {}).get("item_img2img_strength"))

        palette = {
            tuple(bytes.fromhex(color.removeprefix("#")))
            for color in manifest.get("palette", [])
        }
        self.assertGreaterEqual(len(palette), 8)
        chassis_source = art / manifest["chassis"]["source"]
        chassis_metadata = art / manifest["chassis"]["metadata"]
        self.assertTrue(chassis_source.is_file())
        self.assertTrue(chassis_metadata.is_file())
        self.assertEqual((16, 16), self.png_dimensions(chassis_source))

        members = manifest.get("members", {})
        self.assertEqual(set(expected_family), set(members))
        revised_seeds = {
            **{f"magic_storage:block/storage_unit_t{tier}": 71422 for tier in range(1, 7)},
            "magic_storage:block/creative_storage_unit": 71425,
            "magic_storage:block/import_bus_top": 71423,
            "magic_storage:block/import_bus_side": 71423,
            "magic_storage:block/export_bus_top": 71424,
            "magic_storage:block/export_bus_side": 71424,
        }
        for texture_id, expected_role in expected_family.items():
            member = members[texture_id]
            self.assertEqual(expected_role, member.get("role"), texture_id)
            runtime = ROOT / member["runtime"]
            source = art / member["source"]
            metadata_path = art / member["metadata"]
            self.assertTrue(runtime.is_file(), texture_id)
            self.assertTrue(source.is_file(), texture_id)
            self.assertTrue(metadata_path.is_file(), texture_id)
            self.assertEqual(hashlib.sha256(runtime.read_bytes()).hexdigest(), member.get("sha256"))
            metadata = json.loads(metadata_path.read_text())
            self.assertEqual("retro-diffusion/rd-fast", metadata.get("model"), texture_id)
            self.assertEqual(16, metadata.get("size"), texture_id)
            self.assertEqual(revised_seeds.get(texture_id, 71421), metadata.get("seed"), texture_id)
            self.assertTrue(metadata.get("img2img"), texture_id)
            self.assertEqual(manifest["chassis"]["source"], metadata.get("reference_image"), texture_id)

            width, height, pixels = self.rgba_png_pixels(runtime)
            self.assertEqual((16, 16), (width, height), texture_id)
            used_colors = {pixel[:3] for pixel in pixels if pixel[3] != 0}
            self.assertLessEqual(used_colors, palette, texture_id)

        _, _, chassis_pixels = self.rgba_png_pixels(chassis_source)
        chassis_points = [tuple(point) for point in manifest.get("chassis", {}).get("points", [])]
        self.assertGreaterEqual(len(chassis_points), 32)
        for texture_id in expected_family:
            if ":block/" not in texture_id:
                continue
            runtime = ROOT / members[texture_id]["runtime"]
            _, _, pixels = self.rgba_png_pixels(runtime)
            for x, y in chassis_points:
                self.assertEqual(chassis_pixels[y * 16 + x], pixels[y * 16 + x],
                                 f"{texture_id} does not share chassis pixel {(x, y)}")

        connected_members = manifest.get("connected_textures", {})
        self.assertEqual(expected_connected, set(connected_members))
        for texture_id, connected in connected_members.items():
            runtime = ROOT / connected["runtime"]
            source = art / connected["source"]
            metadata_path = ROOT / connected["metadata"]
            self.assertTrue(runtime.is_file(), texture_id)
            self.assertTrue(source.is_file(), texture_id)
            self.assertTrue(metadata_path.is_file(), texture_id)
            self.assertEqual((80, 16), self.png_dimensions(runtime), texture_id)
            self.assertEqual(runtime.read_bytes(), source.read_bytes(), texture_id)
            self.assertEqual(hashlib.sha256(runtime.read_bytes()).hexdigest(), connected.get("sha256"))
            self.assertEqual("pieced", connected.get("layout"), texture_id)
            self.assertEqual(5, connected.get("tiles"), texture_id)
            self.assertEqual(
                {"fusion": {"type": "connecting", "layout": "pieced"}},
                json.loads(metadata_path.read_text()),
                texture_id,
            )

        for contact_sheet in manifest.get("contact_sheets", []):
            self.assertTrue((art / contact_sheet).is_file(), contact_sheet)

        semantic_accents = {
            "magic_storage:block/storage_core": {"#3FDCE5", "#9A5CE8"},
            "magic_storage:block/storage_terminal": {"#3FDCE5"},
            "magic_storage:block/crafting_terminal": {"#3FDCE5", "#9A5CE8"},
            "magic_storage:block/creative_storage_unit": {"#3FDCE5", "#C083FF"},
            "magic_storage:block/import_bus_front": {"#2EA8FF"},
            "magic_storage:block/export_bus_front": {"#FF8A24"},
            "magic_storage:item/remote_terminal": {"#3FDCE5", "#9A5CE8"},
        }
        for texture_id, colors in semantic_accents.items():
            runtime = ROOT / members[texture_id]["runtime"]
            _, _, pixels = self.rgba_png_pixels(runtime)
            used = {pixel[:3] for pixel in pixels if pixel[3] != 0}
            required = {tuple(bytes.fromhex(color.removeprefix("#"))) for color in colors}
            self.assertLessEqual(required, used, texture_id)

        remote = ROOT / members["magic_storage:item/remote_terminal"]["runtime"]
        _, _, remote_pixels = self.rgba_png_pixels(remote)
        self.assertTrue(all(remote_pixels[y * 16 + x][3] == 0
                            for x, y in ((0, 0), (15, 0), (0, 15), (15, 15))))

        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        enum = re.search(r"enum TerminalControlIcon\s*\{(?P<body>.*?);", screen, re.DOTALL)
        self.assertIsNotNone(enum)
        icons = [
            {"name": name, "atlas_index": int(index)}
            for name, index in re.findall(r"\b([A-Z][A-Z0-9_]*)\((\d+)\)", enum.group("body"))
        ]
        self.assertEqual(list(range(len(icons))), [icon["atlas_index"] for icon in icons])
        self.assertEqual(icons, manifest.get("control_atlas", {}).get("icons"))
        atlas = ROOT / manifest["control_atlas"]["runtime"]
        self.assertEqual((256, 16), self.png_dimensions(atlas))
        _, _, atlas_pixels = self.rgba_png_pixels(atlas)
        for icon in icons:
            first = icon["atlas_index"] * 16
            alpha = [atlas_pixels[y * 256 + first + x][3] for y in range(16) for x in range(16)]
            self.assertGreaterEqual(sum(value != 0 for value in alpha), 12, icon["name"])
            self.assertTrue(all(
                atlas_pixels[y * 256 + first + x][:3] == (255, 255, 255)
                for y in range(16) for x in range(16)
                if atlas_pixels[y * 256 + first + x][3] != 0
            ), icon["name"])
        self.assertTrue(all(
            atlas_pixels[y * 256 + x][3] == 0
            for y in range(16) for x in range(len(icons) * 16, 256)
        ))

    def test_crafting_terminal_uses_dedicated_fuel_page_without_separate_popup_screen(self):
        terminal_screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        self.assertNotIn("FuelTable.getFuelValues", terminal_screen)
        self.assertNotIn("pushGuiLayer", terminal_screen)
        self.assertNotIn("FuelSelectionScreen", terminal_screen)
        self.assertNotIn("fuelButtonId", terminal_screen)

        popup = ROOT / "src/main/java/com/swearprom/magicstorage/magic_storage/FuelSelectionScreen.java"
        self.assertFalse(popup.exists(), "transient FuelSelectionScreen must be removed")

        terminal_menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java"
        )
        self.assertNotIn("FUEL_BUTTON_BASE", terminal_menu)
        self.assertNotIn("fuelButtonId", terminal_menu)
        self.assertNotIn("handleFuelButton", terminal_menu)

        crafting_screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        crafting_menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        self.assertIn("CraftingTerminalMenu.FUEL_PAGE_BUTTON", crafting_screen)
        self.assertIn("CraftingTerminalMenu.AUTO_FUEL_TARGET_BUTTON", crafting_screen)
        self.assertNotIn("PREVIOUS_FUEL_TARGET_BUTTON", crafting_screen + crafting_menu)
        self.assertNotIn("NEXT_FUEL_TARGET_BUTTON", crafting_screen + crafting_menu)
        self.assertNotIn("cycleFuelTarget", crafting_menu)
        self.assertIn("CraftingTerminalMenu.fuelTargetButtonId", crafting_screen)
        self.assertNotIn("autoFuelBtn", crafting_screen)
        self.assertNotIn("previousFuelTargetBtn", crafting_screen)
        self.assertNotIn("nextFuelTargetBtn", crafting_screen)
        self.assertIn("CraftingTerminalPage.FUEL", crafting_screen)
        self.assertIn("getEnergyAmount", crafting_screen)

        lang = self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json")
        self.assertNotIn("container.magic_storage.fuel_selection", lang)
        for key in [
            "gui.magic_storage.page_fuel",
            "gui.magic_storage.page_storage",
            "gui.magic_storage.page_craftable",
            "gui.magic_storage.fuel_target_auto",
            "gui.magic_storage.fuel_target",
            "gui.magic_storage.fuel_group.consumables",
            "gui.magic_storage.fuel_group.timed_stations",
            "gui.magic_storage.fuel_group.instant_stations",
        ]:
            self.assertIn(key, lang)
        self.assertNotIn("gui.magic_storage.previous_fuel_target", lang)
        self.assertNotIn("gui.magic_storage.next_fuel_target", lang)

    def test_fuel_target_selector_keeps_cycle_control_and_adds_scalable_popup(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        en_us = json.loads(
            self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json")
        )
        zh_tw = json.loads(
            self.read_required("src/main/resources/assets/magic_storage/lang/zh_tw.json")
        )

        self.assertIn("record PopupList", layout)
        self.assertIn("Rect fuelTargetListButton", layout)
        self.assertIn("PopupList fuelTargetPopup", layout)
        self.assertIn("int maxScrollOffset()", layout)
        self.assertIn("int clampScrollOffset(int", layout)
        self.assertIn("List<Rect> rows(int", layout)
        self.assertRegex(screen, r"\bTerminalCycleButton\s+fuelTargetSelector\b")
        self.assertRegex(screen, r"\bTerminalIconButton\s+fuelTargetListBtn\b")
        self.assertRegex(screen, r"\bFuelTargetPopup\s+fuelTargetPopup\b")
        self.assertIn("selectAdjacentFuelTarget", screen)
        self.assertIn("TerminalCycleDirection.NEXT", screen)
        self.assertIn("fuelTargetOptions()", screen)
        self.assertIn("menu.getSelectedFuelTarget()", screen)
        self.assertIn("CraftingTerminalMenu.AUTO_FUEL_TARGET_BUTTON", screen)
        self.assertIn("CraftingTerminalMenu.fuelTargetButtonId", screen)
        self.assertIn("geometry.fuelTargetListButton()", screen)
        self.assertIn("geometry.fuelTargetPopup()", screen)
        self.assertIn(
            'fuelTargetListBtn.setTooltip(Tooltip.create(Component.translatable('
            '"gui.magic_storage.fuel_target_list")))',
            screen,
        )
        popup_class = self.java_block(
            screen,
            r"\bclass\s+FuelTargetPopup\b",
            "FuelTargetPopup",
        )
        render_popup = self.java_block(
            popup_class,
            r"\bprotected\s+void\s+renderWidget\s*\(",
            "FuelTargetPopup.renderWidget",
        )
        self.assertIn("fuelTargetOptions()", render_popup)
        self.assertIn("option.icon()", render_popup)
        self.assertIn("option.label()", render_popup)
        self.assertIn(
            "Objects.equals(option.target(), menu.getSelectedFuelTarget())",
            render_popup,
        )
        self.assertIn("gui.magic_storage.fuel_target_list", en_us)
        self.assertIn("gui.magic_storage.fuel_target_list", zh_tw)

    def test_fuel_target_popup_closes_cleanly_and_excludes_emi(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        click = self.java_block(
            screen,
            r"\bpublic\s+boolean\s+mouseClicked\s*\(",
            "CraftingTerminalScreen.mouseClicked",
        )
        key = self.java_block(
            screen,
            r"\bpublic\s+boolean\s+keyPressed\s*\(",
            "CraftingTerminalScreen.keyPressed",
        )
        scroll = self.java_block(
            screen,
            r"^[ ]{4}public\s+boolean\s+mouseScrolled\s*\(",
            "CraftingTerminalScreen.mouseScrolled",
        )
        exclusions = self.java_block(
            screen,
            r"\bpublic\s+List<Rect2i>\s+getEmiExclusionAreas\s*\(",
            "CraftingTerminalScreen.getEmiExclusionAreas",
        )

        self.assertIn("closeFuelTargetPopup", click)
        self.assertIn("fuelTargetListBtn.isMouseOver", click)
        self.assertIn("fuelTargetPopup.onClick", click)
        self.assertLess(click.index("fuelTargetPopup.onClick"), click.index("super.mouseClicked"))
        self.assertIn("GLFW.GLFW_KEY_ESCAPE", key)
        self.assertIn("closeFuelTargetPopup", key)
        self.assertIn("fuelTargetPopup.mouseScrolled", scroll)
        self.assertIn("fuelTargetPopup.visible", exclusions)
        self.assertIn("geometry.fuelTargetPopup().bounds()", exclusions)
        toggle = self.java_block(
            screen,
            r"\bprivate\s+void\s+toggleFuelTargetPopup\s*\(",
            "CraftingTerminalScreen.toggleFuelTargetPopup",
        )
        close = self.java_block(
            screen,
            r"\bprivate\s+void\s+closeFuelTargetPopup\s*\(",
            "CraftingTerminalScreen.closeFuelTargetPopup",
        )
        page_update = self.java_block(
            screen,
            r"\bprivate\s+void\s+updatePageWidgets\s*\(",
            "CraftingTerminalScreen.updatePageWidgets",
        )
        self.assertIn("fuelTargetPopup.reveal(selected)", toggle)
        self.assertIn("setFocused(null)", toggle)
        self.assertIn("setFocused(null)", close)
        self.assertIn("if (!fuel) closeFuelTargetPopup()", page_update)

        tooltip = self.java_block(
            screen,
            r"\bprotected\s+void\s+renderTooltip\s*\(",
            "CraftingTerminalScreen.renderTooltip",
        )
        self.assertIn("fuelTargetPopup.isMouseOver", tooltip)
        self.assertLess(
            tooltip.index("fuelTargetPopup.isMouseOver"),
            tooltip.index("super.renderTooltip"),
            "popup rows must suppress tooltips from covered container slots",
        )

    def test_fuel_page_tooltips_use_only_station_slot_and_reserve_icon_bounds(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        machine_hit = screen[
            screen.index("private int machineEnergyIndexAt"):
            screen.index("private int storedFuelIndexAt")
        ]
        reserve_hit = self.java_block(
            screen,
            r"\bprivate\s+int\s+storedFuelIndexAt\s*\(",
            "CraftingTerminalScreen.storedFuelIndexAt",
        )

        self.assertIn("static Rect fuelSlot(Rect", layout)
        self.assertIn("static Rect fuelIcon(Rect", layout)
        self.assertIn("static Rect fuelAmountBounds(Rect", layout)
        self.assertIn("TerminalLayout.fuelSlot(cells.get(visibleIndex)).contains", machine_hit)
        self.assertNotIn("cell.contains", machine_hit)
        self.assertIn("TerminalLayout.fuelIcon(cells.get(visibleIndex)).contains", reserve_hit)
        self.assertNotIn("cell.contains", reserve_hit)

    def test_crafting_terminal_repositions_fuel_slots_without_sticky_checkbox_focus(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        shared_shell = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        self.assertNotIn("Checkbox", screen)
        self.assertIn("MACHINE_SLOT_COUNT", screen)
        self.assertIn("repositionFuelSlots", screen)
        self.assertIn("replaceSlot", screen)
        self.assertIn("geometry.consumablesGrid()", screen)
        self.assertIn("geometry.timedStationsGrid()", screen)
        self.assertIn("geometry.instantStationsGrid()", screen)
        self.assertIn("boolean handled = super.mouseClicked", shared_shell)
        handled = shared_shell.index("boolean handled = super.mouseClicked")
        self.assertGreater(shared_shell.index("setFocused(null)", handled), handled)
        self.assertNotIn("Preview is server-synced", screen)

        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        self.assertIn("MACHINE_SLOT_START", menu)
        self.assertIn("MACHINE_SLOT_COUNT", menu)
        self.assertIn("MachineEnergyTable", menu)

        import json
        lang = json.loads(self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json"))
        self.assertEqual("Brew Energy", lang["gui.magic_storage.energy.blaze_fuel"])

    def test_crafting_pages_fuel_flow_and_craft_actions_match_fullscreen_contract(self):
        page = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalPage.java"
        )
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        machine_table = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MachineEnergyTable.java"
        )

        for name in ["STORAGE", "CRAFTABLE", "FUEL"]:
            self.assertIn(name, page)
        self.assertNotIn("ITEMS", page)
        self.assertIn("isItemPage", page)
        self.assertNotIn("showOnlyCraftable", menu)
        self.assertIn("MAX_CRAFT_BUTTON", menu)
        self.assertIn("craftMaximum", menu)
        self.assertIn("RAIL_GROUP_GAP", layout)
        self.assertIn("record FlowGrid", layout)
        self.assertIn("CATEGORY_CELL_PREFERRED_WIDTH", layout)
        self.assertIn("int minimumColumns = (visible + maxRows - 1) / maxRows;", layout)
        self.assertIn("Math.clamp(preferredColumns, minimumColumns, largestColumns)", layout)
        self.assertIn("(column + 1) * bounds.width() / columns", layout)
        self.assertIn("entries()", machine_table)
        for stale in ["MACHINE_ENERGY_TYPES", "MACHINE_LABEL_KEYS", "STORED_FUEL_TYPES", "FUEL_LABEL_KEYS"]:
            self.assertNotIn(stale, screen)
        self.assertNotIn("machine_rate_hint", screen)
        fuel_rows = screen[
            screen.index("private void renderConsumablesPanel"):
            screen.index("private void drawFlowPageIndicator")
        ]
        self.assertNotIn("drawCenteredString", fuel_rows)
        self.assertIn("drawFlowAmount", fuel_rows)
        self.assertIn("craftMaxBtn", screen)
        self.assertIn("craft1Btn.active = craftable >= 1", screen)
        self.assertIn("craft8Btn.active = craftable >= 8", screen)
        self.assertIn("craft64Btn.active = craftable >= 64", screen)
        self.assertIn("craftMaxBtn.active = craftable >= 1", screen)
        self.assertNotIn("case SEARCH, SEARCH_TAG, SEARCH_MOD", screen)

    def test_terminal_amounts_fit_their_slots_and_fuel_uses_representative_items(self):
        formatter_path = ROOT / "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalAmountFormatter.java"
        self.assertTrue(formatter_path.exists(), "terminal amount formatter must be dist-neutral and testable")

        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        self.assertIn("protected void renderSlotContents", storage)
        self.assertIn("TerminalAmountFormatter.formatCompact", storage)
        self.assertIn("copyWithCount(1)", storage)
        self.assertIn("TerminalDisplayStack.amount(stack)", storage)
        self.assertIn("if (amount <= 0) return;", storage)
        self.assertIn("getTooltipFromContainerItem", storage)
        self.assertIn("gui.magic_storage.stored_amount", storage)
        self.assertIn("protected void drawTypeCapacity", storage)
        self.assertIn("menu.getTypeCount()", storage)
        self.assertIn("menu.getMaxTypes()", storage)

        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        fuel_panel = crafting[
            crafting.index("private void renderConsumablesPanel"):
            crafting.index("private void drawFlowPageIndicator")
        ]
        self.assertIn("type.representativeStack()", fuel_panel)
        self.assertNotIn("drawEnergyIcon", fuel_panel)
        self.assertNotIn("nextFuelTargetBtn", crafting)
        self.assertIn("menu.getPage() == CraftingTerminalPage.FUEL", crafting)
        self.assertIn("renderFuelTypeCapacity", crafting)
        self.assertIn("geometry.fuelStatus()", crafting)
        flow_amount = self.java_block(
            crafting,
            r"\bprivate\s+void\s+drawFlowAmount\s*\(",
            "CraftingTerminalScreen.drawFlowAmount",
        )
        self.assertIn("TerminalLayout.fuelAmountBounds(cell)", flow_amount)
        self.assertIn("bounds.x() + bounds.width() / 2.0F", flow_amount)
        self.assertIn("graphics.drawString(font, text, -textWidth / 2", flow_amount)
        self.assertNotIn("cell.right()", flow_amount)
        type_capacity = self.java_block(
            crafting,
            r"\bprivate\s+void\s+renderFuelTypeCapacity\s*\(",
            "CraftingTerminalScreen.renderFuelTypeCapacity",
        )
        self.assertIn("geometry.fuelStatus()", type_capacity)
        self.assertIn("drawRaisedPanel(graphics, leftPos, topPos, status)", type_capacity)
        self.assertIn('"gui.magic_storage.type_capacity"', type_capacity)
        self.assertNotIn("drawFlowAmount(graphics, status", type_capacity)
        labels = self.java_block(
            crafting,
            r"\bprotected\s+void\s+renderLabels\s*\(",
            "CraftingTerminalScreen.renderLabels",
        )
        self.assertNotIn("drawTypeCapacity", labels)

    def test_terminal_display_amount_is_exact_server_metadata_not_stack_count(self):
        helper = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalDisplayStack.java"
        )
        key = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/ItemKey.java"
        )
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        self.assertIn("putLong(AMOUNT_KEY, amount)", helper)
        self.assertIn("static ItemStack strip", helper)
        self.assertIn("TerminalDisplayStack.strip(stack)", key)
        self.assertIn("TerminalDisplayStack.create(stack, count)", core)
        self.assertIn("core.getItemCount(key)", crafting)
        self.assertNotIn("preview.craftable() * output.getCount()", crafting)
        self.assertNotIn("Math.min(count, Integer.MAX_VALUE)", core)

    def test_compact_grid_is_removed_instead_of_hidden(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        lang = self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json")
        for stale in [
            "compactMode",
            "isCompactMode",
            "toggleCompactMode",
            "compactDisplayStacks",
            "compactRailBtn",
            "gui.magic_storage.compact_mode",
        ]:
            self.assertNotIn(stale, menu + screen + lang)

    def test_recipe_presentation_kind_covers_every_supported_native_diagram(self):
        kind = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RecipePresentationKind.java"
        )
        self.assertRegex(kind, r"\bpublic\s+enum\s+RecipePresentationKind\b")
        for family in ["CRAFTING", "COOKING", "STONECUTTING", "SMITHING", "AXE"]:
            self.assertRegex(
                kind,
                rf"\b[A-Z0-9_]*{family}[A-Z0-9_]*\b",
                f"RecipePresentationKind must identify the {family.lower()} diagram",
            )

    def test_recipe_presentation_model_is_exact_immutable_and_bounded(self):
        presentation = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RecipePresentation.java"
        )

        self.assertRegex(presentation, r"\bpublic\s+final\s+class\s+RecipePresentation\b")
        required_fields = {
            "exact recipe id": r"\bprivate\s+final\s+ResourceLocation\s+recipeId\s*;",
            "presentation kind": r"\bprivate\s+final\s+RecipePresentationKind\s+kind\s*;",
            "shaped width": r"\bprivate\s+final\s+int\s+width\s*;",
            "shaped height": r"\bprivate\s+final\s+int\s+height\s*;",
            "shapeless state": r"\bprivate\s+final\s+boolean\s+shapeless\s*;",
            "positioned inputs": r"\bprivate\s+final\s+List<ItemStack>\s+inputs\s*;",
            "exact output stack": r"\bprivate\s+final\s+ItemStack\s+output\s*;",
            "station identity": r"\bprivate\s+final\s+ItemStack\s+station\s*;",
            "typed ledger rows": r"\bprivate\s+final\s+List<Resource>\s+resources\s*;",
        }
        self.assertEqual(
            [],
            [label for label, pattern in required_fields.items()
             if re.search(pattern, presentation) is None],
        )

        self.assertEqual(9, self.java_int_constant(presentation, "MAX_INPUTS"))
        self.assertGreater(self.java_int_constant(presentation, "MAX_ITEM_RESOURCES"), 0)
        self.assertIn("inputs.size() != MAX_INPUTS", presentation)
        self.assertIn("itemResourceCount > MAX_ITEM_RESOURCES", presentation)
        self.assertIn("toolRows > 1", presentation)
        for resource_kind in ["ITEM", "ENERGY", "TOOL"]:
            self.assertRegex(presentation, rf"\b{resource_kind}\b")
        self.assertIn("record Metadata(", presentation)
        self.assertIn("this.inputs = inputs.stream().map(ItemStack::copy).toList()", presentation)
        self.assertIn("this.output = output.copy()", presentation)
        self.assertIn("this.resources = List.copyOf(resources)", presentation)
        self.assertIn("return output.copy()", presentation)
        self.assertIn("metadataCarrier(Metadata metadata)", presentation)
        self.assertIn("metadataFromCarrier(ItemStack carrier)", presentation)
        self.assertNotIn("output.copyWithCount(1)", presentation)

    def test_recipe_presentation_is_built_server_side_and_uses_bounded_menu_sync(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        self.assertRegex(
            menu,
            r"\bpublic\s+RecipePresentation\s+getRecipePresentation\s*\(\s*\)",
        )
        presentation_getter = self.java_block(
            menu,
            r"\bpublic\s+RecipePresentation\s+getRecipePresentation\s*\(",
            "CraftingTerminalMenu.getRecipePresentation",
        )
        presentation_sync = self.java_block(
            menu,
            r"\bprivate\s+void\s+syncRecipePresentation\s*\(",
            "CraftingTerminalMenu.syncRecipePresentation",
        )
        self.assertIn("RecipePresentation.metadataFromCarrier(", presentation_getter)
        self.assertIn("RecipePresentation.MAX_INPUTS", presentation_getter)
        self.assertIn("metadata.itemResourceCount()", presentation_getter)
        self.assertIn("getEnergyPreview()", presentation_getter)
        self.assertIn("metadata.toolRequired() > 0", presentation_getter)
        self.assertIn("return new RecipePresentation(", presentation_getter)
        self.assertRegex(
            menu,
            r"private\s+void\s+syncRecipePresentation\s*\(\s*RecipeAdapterMatch\s+match",
        )
        self.assertIn("RecipeAdapterMatch.Presentation semantics = match.presentation()", presentation_sync)
        self.assertIn("match.presentationOutput(inputs, core.getLevel())", presentation_sync)
        self.assertIn("match.holder().id()", presentation_sync)
        self.assertIn("output.copy()", presentation_sync)
        self.assertIn("RecipePresentation.metadataCarrier(metadata)", presentation_sync)
        self.assertIn("SELECTION_SLOTS = PRESENTATION_METADATA_SLOT + 1", menu)
        self.assertIn("new SimpleContainer(SELECTION_SLOTS)", menu)
        self.assertIn("new ArrayList<>(2)", menu)

    def test_recipe_resources_keep_explicit_infinity_and_bulk_long_count_commits(self):
        presentation = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RecipePresentation.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        self.assertRegex(presentation, r"record Resource\([\s\S]*boolean infinite")
        self.assertIn('resource.infinite() ? "∞"', screen)
        self.assertIn("insertItemCount(", core)
        self.assertIn("extractItemCount(", core)
        commit = self.java_block(
            menu,
            r"\bprivate\s+boolean\s+commitCraft\s*\(",
            "CraftingTerminalMenu.commitCraft",
        )
        transaction = self.java_block(
            menu,
            r"\bprivate\s+static\s+boolean\s+applyCoreResourceDeltas\s*\(",
            "CraftingTerminalMenu.applyCoreResourceDeltas",
        )
        self.assertIn("applyCoreResourceDeltas(", commit)
        self.assertIn("StorageResourceTransaction.builder()", transaction)
        self.assertIn("core.applyResourceTransaction(", transaction)
        self.assertNotIn("while (remaining > 0)", commit)
        self.assertNotIn("while (remaining > 0)", transaction)

    def test_smithing_variant_paths_bind_same_id_to_exact_selected_output(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        adapters = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/BuiltInRecipeAdapters.java"
        )
        match_contract = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RecipeAdapterMatch.java"
        )
        commit = self.java_block(
            menu,
            r"\bprivate\s+boolean\s+commitCraft\s*\(",
            "CraftingTerminalMenu.commitCraft",
        )
        variant_lookup = self.java_block(
            menu,
            r"\bprivate\s+static\s+RecipeAdapterMatch\s+resolveAvailableRecipeVariant\s*\(",
            "CraftingTerminalMenu.resolveAvailableRecipeVariant",
        )
        variant_resolution = self.java_block(
            match_contract,
            r"\bList<RecipeAdapterMatch>\s+resolveVariants\s*\(",
            "RecipeAdapterMatch.resolveVariants",
        )
        self.assertIn("resolveAvailableRecipeVariantById(", commit)
        self.assertNotIn("resolveAvailableRecipeMatchById(", menu)
        self.assertIn("plannedMatch.presentationOutput(List.of(), level)", commit)
        self.assertIn("ItemStack.isSameItemSameComponents", variant_lookup)
        self.assertNotIn("presentationOutput(List.of(), level)", variant_resolution)
        self.assertNotIn("SMITHING_TRANSFORM_ID", menu)
        self.assertIn("matchesLookupOutput", match_contract)
        self.assertIn("matchesLookupOutput", adapters)
        self.assertRegex(
            adapters,
            r"SMITHING_TRANSFORM_ID[\s\S]*?BuiltInRecipeAdapters::smithingVariants",
        )
        self.assertRegex(
            adapters,
            r"SMITHING_TRIM_ID[\s\S]*?BuiltInRecipeAdapters::smithingVariants",
        )

    def test_recipe_workspace_stacks_diagram_above_ledger_and_footer(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        required_layout_regions = [
            "Rect recipeDiagram",
            "Rect recipeLedger",
            "Rect recipeFooter",
            "List<Rect> recipeNavigationButtons",
            "List<Rect> recipeCraftButtons",
            "Rect consumablesPanel",
            "Rect timedStationsPanel",
            "Rect instantStationsPanel",
            "Rect fuelInput",
        ]
        self.assertEqual([], [region for region in required_layout_regions if region not in layout])
        self.assertIn("static final int CONTROL_SIZE = SLOT_SIZE", layout)
        self.assertNotIn("RESOURCE_COUNT = 9", layout)
        self.assertNotIn("recipeResourceCells", layout)
        self.assertRegex(
            layout,
            r"\bdiagram\.bottom\(\)\s*>\s*ledger\.y\(\)"
            r"[\s\S]{0,200}\bledger\.bottom\(\)\s*>\s*footer\.y\(\)",
            "recipe geometry must reject diagram/ledger/footer overlap",
        )
        for usage in [
            "geometry.recipeDiagram()",
            "geometry.recipeLedger()",
            "geometry.recipeFooter()",
            "geometry.recipeNavigationButtons()",
            "geometry.recipeCraftButtons()",
        ]:
            self.assertIn(usage, screen)

    def test_recipe_navigation_uses_two_explicit_previous_next_arrow_buttons(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        init = self.java_block(
            screen,
            r"\bprotected\s+void\s+init\s*\(",
            "CraftingTerminalScreen.init",
        )
        self.assertEqual(1, init.count("TerminalControlIcon.PREVIOUS"))
        self.assertEqual(1, init.count("TerminalControlIcon.NEXT"))
        self.assertIn("navigationButtons.get(0)", init)
        self.assertIn("navigationButtons.get(1)", init)
        self.assertIn("prevRecipeBtn = addRecipeNavigationButton(", init)
        self.assertIn("nextRecipeBtn = addRecipeNavigationButton(", init)
        self.assertNotIn("addTextCycleButton", init[:init.index("List<TerminalLayout.Rect> craftButtons")])

    def test_recipe_output_renders_the_exact_server_synced_stack_count(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        native = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/NativeRecipeDiagramRenderer.java"
        )
        recipe_panel = self.java_block(
            screen,
            r"\bprivate\s+void\s+renderRecipePanel\s*\(",
            "CraftingTerminalScreen.renderRecipePanel",
        )
        presentation = re.search(
            r"\bRecipePresentation\s+([A-Za-z_]\w*)\s*=\s*"
            r"menu\.getRecipePresentation\(\)\s*;",
            recipe_panel,
        )
        self.assertIsNotNone(
            presentation,
            "renderRecipePanel must read the server-synced RecipePresentation",
        )
        self.assertIn("activeRecipeDiagramRenderer(", recipe_panel)
        self.assertIn(".render(", recipe_panel)
        output = re.search(
            rf"\bItemStack\s+([A-Za-z_]\w*)\s*=\s*"
            rf"{re.escape(presentation.group(1))}\.output\(\)\s*;",
            native,
        )
        self.assertIsNotNone(output, "recipe output must come from RecipePresentation.output()")
        output_name = output.group(1)
        self.assertRegex(native, rf"graphics\.renderItem\(\s*{re.escape(output_name)}\s*,")
        self.assertRegex(
            native,
            rf"graphics\.renderItemDecorations\(\s*font\s*,\s*{re.escape(output_name)}\s*,",
        )
        self.assertNotIn("output.copyWithCount(1)", native)

    def test_terminal_semantic_workspaces_use_vanilla_container_grammar(self):
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        native = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/NativeRecipeDiagramRenderer.java"
        )

        for source in [storage, screen, native]:
            self.assertNotRegex(source, r"\bTERMINAL_(?:SURFACE|CARD|BORDER|ACCENT|TEXT)")
            self.assertNotIn("drawTerminalSurface", source)
            self.assertNotIn("drawTerminalCard", source)
            self.assertNotIn("drawTerminalControl", source)
            self.assertNotIn("drawTerminalSlot", source)
        for palette_name in [
            "ITEM_ROW_BACKGROUND", "ITEM_ROW_BORDER",
            "ENERGY_ROW_BACKGROUND", "ENERGY_ROW_BORDER",
            "TOOL_ROW_BACKGROUND", "TOOL_ROW_BORDER",
        ]:
            self.assertNotIn(palette_name, screen)
        self.assertIn("drawRaisedPanel(graphics, leftPos, topPos, bar)", screen)
        self.assertIn("drawInsetPanel(graphics, leftPos, topPos, panel)", screen)
        self.assertIn("drawVanillaSlot(graphics, x, y)", screen)
        self.assertIn("super.renderWidget(graphics, mouseX, mouseY, partialTick)", storage)
        self.assertRegex(screen, r"\.available\(\)\s*>=\s*[A-Za-z_]\w*\.required\(\)")

    def test_recipe_presentation_tolerates_in_flight_slot_sync(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        presentation = self.java_block(
            menu,
            r"\bpublic\s+RecipePresentation\s+getRecipePresentation\s*\(",
            "CraftingTerminalMenu.getRecipePresentation",
        )
        self.assertNotIn("Recipe presentation item resource is missing", presentation)
        self.assertNotIn("Recipe presentation tool resource is missing", presentation)
        self.assertGreaterEqual(
            presentation.count("return RecipePresentation.empty();"),
            3,
            "metadata and dependent hidden slots arrive in separate packets; partial snapshots must not crash rendering",
        )

    def test_recipe_ledger_is_top_aligned_and_never_exceeds_four_columns(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        cells = self.java_block(
            layout,
            r"\bprivate\s+static\s+List<Rect>\s+recipeLedgerCells\s*\(",
            "TerminalLayout.recipeLedgerCells",
        )
        self.assertIn("RECIPE_LEDGER_MAX_COLUMNS = 4", layout)
        self.assertRegex(cells, r"int\s+columns\s*=\s*Math\.min\(.+RECIPE_LEDGER_MAX_COLUMNS")
        self.assertRegex(cells, r"int\s+rows\s*=\s*\(resourceCount\s*\+\s*columns\s*-\s*1\)\s*/\s*columns")
        self.assertIn("int top = bounds.y();", cells)
        self.assertNotIn("(bounds.height() - rows * cellHeight) / 2", cells)
        self.assertIn("RECIPE_LEDGER_MAX_HEIGHT = SLOT_SIZE * 3", layout)

    def test_recipe_ledger_reduces_columns_when_the_viewport_is_too_narrow(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        cells = self.java_block(
            layout,
            r"\bprivate\s+static\s+List<Rect>\s+recipeLedgerCells\s*\(",
            "TerminalLayout.recipeLedgerCells",
        )
        self.assertIn("RECIPE_LEDGER_MIN_CELL_WIDTH", layout)
        self.assertRegex(cells, r"bounds\.width\(\)\s*/\s*RECIPE_LEDGER_MIN_CELL_WIDTH")

    def test_available_recipe_amount_uses_high_contrast_dark_green(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        self.assertIn("0xFF176B2C", screen)
        self.assertNotIn("0xFF75D58A", screen)

    def test_recipe_presentation_keeps_data_and_container_slot_parity_guarded(self):
        tests = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/gametest/TerminalFlowTests.java"
        )
        parity = self.java_block(
            tests,
            r"\bpublic\s+static\s+void\s+"
            r"crafting_menu_data_slot_parity_server_vs_buf_ctor\s*\(",
            "crafting menu data/container slot parity GameTest",
        )
        self.assertIn("serverCount != bufCount", parity)
        self.assertIn("serverMenu.slots.size()", parity)
        self.assertIn("bufMenu.slots.size()", parity)
        self.assertIn("metadataSlots", parity)
        self.assertIn("metadata slot", parity)
        self.assertIn("slot.isActive()", parity)
        self.assertIn("slot.mayPlace", parity)
        self.assertIn("slot.mayPickup", parity)

    def test_terminal_screens_use_shared_adaptive_geometry_and_original_slot_delegates(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        self.assertIn("record Geometry", layout)
        for stale_constant in ["SB_X", "SEARCH_X", "GRID_TOP", "SIDE_RAIL_X", "CRAFTING_BOTTOM_HEIGHT"]:
            self.assertNotIn(stale_constant, storage + crafting)

        self.assertIn("List.copyOf(menu.slots)", storage)
        self.assertIn("semanticSlots.get(menuIndex)", storage)
        self.assertIn("delegate.isActive()", storage)
        self.assertIn("delegate.mayPlace(stack)", storage)
        self.assertIn("delegate.mayPickup(player)", storage)
        self.assertNotIn("Slot old = menu.slots.get", storage + crafting)

    def test_emi_registers_adaptive_crafting_terminal_exclusion_areas(self):
        plugin = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        self.assertIn("registry.addExclusionArea(CraftingTerminalScreen.class", plugin)
        self.assertIn("screen.getEmiExclusionAreas()", plugin)
        self.assertIn("new Bounds(", plugin)
        self.assertIn("public List<Rect2i> getEmiExclusionAreas()", screen)

    def test_furnace_fuel_is_named_fuel_and_guide_uses_runtime_recipe_values(self):
        lang = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/en_us.json"
        ))
        self.assertEqual("Fuel", lang["gui.magic_storage.energy.furnace_fuel"])
        self.assertNotIn("gui.magic_storage.fuel_target_furnace", lang)
        self.assertNotIn("gui.magic_storage.fuel.cooking", lang)

        guide_paths = [
            "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/energy_overview.json",
            "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/fuel_conversion.json",
            "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/recipe_costs.json",
        ]
        guide = "\n".join(self.read_required(path) for path in guide_paths)
        self.assertNotIn("Cooking Energy", guide)
        self.assertNotIn("200 Smelting", guide)
        self.assertNotIn("100 Blasting", guide)
        self.assertNotIn("second page button", guide)
        self.assertNotIn("displays all eight totals live", guide)
        self.assertIn("cooking time", guide)
        self.assertIn("runtime burn time", guide)
        self.assertIn("third page", guide)
        self.assertNotIn("previous/next", guide)
        self.assertIn("Fuel Target", guide)
        self.assertNotIn("Energy Reserves header", guide)
        self.assertNotIn("all currently registered totals", guide)
        self.assertIn("Consumables", guide)
        self.assertIn("Timed Stations", guide)
        self.assertIn("Instant Stations", guide)

    def test_remote_access_is_pinned_to_exact_loaded_core_identity(self):
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        record = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRecord.java"
        )
        self.assertIn("UUID.randomUUID()", record)
        self.assertIn("tag.putUUID(TAG_NETWORK_ID, networkId)", record)
        self.assertIn("tag.getUUID(TAG_NETWORK_ID)", record)
        self.assertIn("tag.putUUID(TAG_STORAGE_ID, storageId)", core)
        self.assertNotIn("TAG_NETWORK_ID", core)

        remote = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RemoteTerminalItem.java"
        )
        self.assertIn("TAG_CORE_ID", remote)
        self.assertIn("tag.hasUUID(TAG_CORE_ID)", remote)
        self.assertIn("core.getNetworkId().equals(getBoundCoreId(stack))", remote)

        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java"
        )
        self.assertIn("core.getNetworkId().equals(coreId)", menu)
        self.assertIn("level.dimension().equals(coreDimension)", menu)
        self.assertIn("level.hasChunkAt(corePos)", menu)

        entrypoint = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        self.assertGreaterEqual(entrypoint.count("menu.getCore(player.level())"), 2)

    def test_local_menus_and_buses_validate_a_current_loaded_network_path(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java"
        )
        self.assertIn("MagicStorage.hasLoadedNetworkPath", menu)
        self.assertIn("MagicStorage.findLoadedNetworkPath", menu)

        for relative_path in [
            "src/main/java/com/swearprom/magicstorage/magic_storage/ImportBusBlockEntity.java",
            "src/main/java/com/swearprom/magicstorage/magic_storage/ExportBusBlockEntity.java",
        ]:
            text = self.read_required(relative_path)
            self.assertIn("cachedPath", text, relative_path)
            self.assertIn("MagicStorage.hasLoadedNetworkPath", text, relative_path)
            self.assertIn("MagicStorage.findLoadedNetworkPath", text, relative_path)

        entrypoint = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        self.assertIn("level.hasChunkAt(pos)", entrypoint)
        self.assertIn("isValidNetworkPath", entrypoint)

    def test_storage_unit_guide_matches_self_drop_and_capacity_contract(self):
        for tier in range(1, 7):
            block_id = f"magic_storage:storage_unit_t{tier}"
            loot = json.loads(self.read_required(
                f"src/main/resources/data/magic_storage/loot_table/blocks/storage_unit_t{tier}.json"
            ))
            entries = loot["pools"][0]["entries"]
            self.assertEqual([block_id], [entry["name"] for entry in entries])

        guide = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/unit_tiers.json"
        ))
        text = " ".join(page.get("text", "") for page in guide["pages"]).lower()
        self.assertIn("drops itself", text)
        self.assertIn("available type capacity decreases", text)
        self.assertIn("stored items stay in the core", text)

    def test_retired_bottle_energy_has_no_runtime_or_migration_surface(self):
        runtime_without_migration = "\n".join(
            self.read_required(path)
            for path in [
                "src/main/java/com/swearprom/magicstorage/magic_storage/EnergyType.java",
                "src/main/java/com/swearprom/magicstorage/magic_storage/FuelTable.java",
                "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java",
                "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java",
                "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java",
                "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRecord.java",
                "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRepository.java",
            ]
        )
        self.assertNotIn("BOTTLE_FUEL", runtime_without_migration)
        self.assertNotIn("bottle_fuel", runtime_without_migration)
        self.assertNotIn("legacyBottleFuel", runtime_without_migration)

        player_facing_surfaces = "\n".join(
            self.read_required(path)
            for path in [
                "scripts/prepare_prism_gui_world.py",
                "scripts/run_prism_gui_session.py",
                "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/energy_overview.json",
                "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/fuel_conversion.json",
                "src/main/resources/assets/magic_storage/patchouli_books/guide/en_us/entries/crafting_terminal.json",
            ]
        )
        self.assertNotIn("bottle_fuel", player_facing_surfaces)
        self.assertNotIn("Bottle Energy", player_facing_surfaces)
        self.assertNotIn("Coal, Blaze Rod, and Glass Bottle", player_facing_surfaces)

    def test_player_facing_terminal_text_is_concise_localized_and_locale_complete(self):
        agents = self.read_required("AGENTS.md")
        self.assertIn("Simple is better", agents)
        self.assertIn("player-facing", agents)

        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        tooltip = self.java_block(
            storage,
            r"\b(?:protected\s+)?static\s+Tooltip\s+createCycleTooltip\s*\(",
            "concise cycle tooltip",
        )
        self.assertNotIn("cycle_hint", tooltip)
        self.assertNotIn('"\\n"', tooltip)

        crafting = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        self.assertNotIn('Component.literal("Previous recipe")', crafting)
        self.assertNotIn('Component.literal("Next recipe")', crafting)
        self.assertIn('Component.translatable("gui.magic_storage.previous_recipe")', crafting)
        self.assertIn('Component.translatable("gui.magic_storage.next_recipe")', crafting)

        en_us = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/en_us.json"
        ))
        zh_tw = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/zh_tw.json"
        ))
        self.assertEqual(set(en_us), set(zh_tw))
        self.assertNotIn("gui.magic_storage.energy.bottle_fuel", en_us)
        self.assertNotIn("tooltip.magic_storage.cycle_hint", en_us)
        self.assertIn("gui.magic_storage.previous_recipe", en_us)
        self.assertIn("gui.magic_storage.next_recipe", en_us)
        self.assertIn("gui.magic_storage.recipe_station", en_us)
        self.assertEqual(zh_tw["gui.magic_storage.energy.blaze_fuel"], "釀造能量")

    def test_recipe_empty_state_station_badge_and_output_icon_match_current_state(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        native = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/NativeRecipeDiagramRenderer.java"
        )
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        storage = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )

        recipe_panel = self.java_block(
            screen,
            r"\bprivate\s+void\s+renderRecipePanel\s*\(",
            "recipe panel renderer",
        )
        self.assertIn("menu.getSelectedStack()", recipe_panel)
        self.assertIn('"gui.magic_storage.no_recipe"', recipe_panel)
        clear_presentation = self.java_block(
            menu,
            r"\bprivate\s+void\s+clearRecipePresentation\s*\(",
            "recipe presentation clear",
        )
        self.assertIn("selectedKey", clear_presentation)
        self.assertIn("PRESENTATION_OUTPUT_SLOT", clear_presentation)

        self.assertIn("renderRecipeStationHint", screen)
        self.assertIn("presentation.station()", screen)
        self.assertNotIn("presentation.station()", native)
        recipe_geometry = self.java_block(
            layout,
            r"\bprivate\s+static\s+RecipeGeometry\s+recipeGeometry\s*\(",
            "recipe geometry",
        )
        station_declaration = recipe_geometry[recipe_geometry.index("Rect station"):
                                              recipe_geometry.index("Rect shapelessMarker")]
        self.assertIn("diagram.right()", station_declaration)
        self.assertIn("diagram.bottom()", station_declaration)

        icon_button = self.java_block(
            storage,
            r"\bclass\s+TerminalIconButton\b",
            "mutable terminal item-icon control",
        )
        self.assertIn("setItemIcon", icon_button)
        self.assertNotIn("final ItemStack itemIcon", icon_button)
        self.assertIn("Items.PLAYER_HEAD", screen)
        self.assertIn("MagicStorage.STORAGE_CORE_ITEM", screen)
        self.assertIn("outputDestinationRailBtn.setItemIcon", screen)

    def test_fuel_workspace_uses_three_descriptor_category_rows(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        machine_table = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MachineEnergyTable.java"
        )

        for declaration in [
            "record FuelDescriptorCounts",
            "Rect consumablesPanel",
            "Rect timedStationsPanel",
            "Rect instantStationsPanel",
            "Rect fuelStatus",
            "FlowGrid consumablesGrid",
            "FlowGrid timedStationsGrid",
            "FlowGrid instantStationsGrid",
        ]:
            self.assertIn(declaration, layout)
        for legacy in [
            "MACHINE_FLOW_ROWS",
            "Rect machinePanel",
            "Rect fuelPanel",
            "Rect fuelControlPanel",
            "FlowGrid machineGrid",
            "FlowGrid reserveGrid",
        ]:
            self.assertNotIn(legacy, layout)

        self.assertIn("pagedFlowGrid", layout)
        self.assertNotIn("horizontalFlowGrid", layout)
        self.assertIn("renderConsumablesPanel", screen)
        self.assertIn("renderTimedStationsPanel", screen)
        self.assertIn("renderInstantStationsPanel", screen)
        self.assertNotIn("renderMachinePanel", screen)
        self.assertNotIn("renderFuelPanel", screen)
        self.assertNotIn("renderFuelControlPanel", screen)
        self.assertNotIn('"gui.magic_storage.installed_machines"', screen)
        self.assertNotIn('"gui.magic_storage.energy_reserves"', screen)
        for category in ["PROCESS", "INSTANT", "CONSUMABLE"]:
            self.assertIn(category, machine_table)

    def test_fuel_panels_fill_vertical_space_and_type_capacity_is_inventory_side(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        assembly = self.java_block(
            layout,
            r"\bprivate\s+static\s+Geometry\s+assembleCraftingGeometry\s*\(",
            "TerminalLayout.assembleCraftingGeometry",
        )
        self.assertIn("fuelAreaBottom - TOP_HEIGHT", assembly)
        self.assertIn("playerInventory.right() + CONTROL_GAP", assembly)
        self.assertNotIn("fuelStatus.x() - CONTROL_GAP", assembly)
        status = self.java_block(
            screen,
            r"\bprivate\s+void\s+renderFuelTypeCapacity\s*\(",
            "CraftingTerminalScreen.renderFuelTypeCapacity",
        )
        self.assertIn("drawRaisedPanel", status)
        self.assertRegex(
            status,
            r'Component\.translatable\(\s*"gui\.magic_storage\.type_capacity"',
        )

    def test_fuel_descriptor_grids_are_multi_row_and_paged_for_large_integrations(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        grid = self.java_block(
            layout,
            r"\bprivate\s+static\s+FlowGrid\s+pagedFlowGrid\s*\(",
            "TerminalLayout.pagedFlowGrid",
        )
        self.assertIn("bounds.height() / FUEL_CATEGORY_CELL_HEIGHT", grid)
        self.assertIn("columns * rows", grid)

        en_us = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/en_us.json"
        ))
        zh_tw = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/zh_tw.json"
        ))
        self.assertEqual("Consumables", en_us["gui.magic_storage.fuel_group.consumables"])
        self.assertEqual("Timed Stations", en_us["gui.magic_storage.fuel_group.timed_stations"])
        self.assertEqual("Instant Stations", en_us["gui.magic_storage.fuel_group.instant_stations"])
        self.assertEqual(set(en_us), set(zh_tw))
        self.assertNotIn("Stations & Axe Energy", en_us.values())

    def test_cycle_controls_middle_reset_and_only_boolean_controls_have_status_lights(self):
        direction = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalCycleDirection.java"
        )
        storage_screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java"
        )
        storage_menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java"
        )
        crafting_screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        crafting_menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )

        self.assertNotIn("RESET", direction)
        cycle_button = self.java_block(
            storage_screen,
            r"\bclass\s+TerminalCycleButton\b",
            "terminal cycle button",
        )
        self.assertIn("Runnable resetAction", cycle_button)
        self.assertRegex(cycle_button, r"button\s*==\s*2")
        self.assertIn("resetAction.run()", cycle_button)
        for constant in [
            "RESET_SORT_ORDER_BUTTON",
            "RESET_SORT_MODE_BUTTON",
            "RESET_SEARCH_MODE_BUTTON",
        ]:
            self.assertIn(constant, storage_menu)
        self.assertIn("sortOrder = SortOrder.ASCENDING", storage_menu)
        self.assertIn("sortMode = SortMode.NAME", storage_menu)
        self.assertIn("searchMode = SearchMode.NORMAL", storage_menu)
        self.assertIn("RESET_OUTPUT_DESTINATION_BUTTON", crafting_menu)
        self.assertIn("RESET_PLAYER_INVENTORY_BUTTON", crafting_menu)
        self.assertIn("outputDestination = TerminalOutputDestination.PLAYER", crafting_menu)
        self.assertIn("selectedFuelTarget = null", crafting_menu)
        self.assertIn("usePlayerInventory = false", crafting_menu)

        side_rail = self.java_block(
            crafting_screen,
            r"\bprivate\s+void\s+renderSideRail\s*\(",
            "CraftingTerminalScreen.renderSideRail",
        )
        self.assertIn("menu.isUsePlayerInventory()", side_rail)
        self.assertNotIn("menu.getOutputDestination()", side_rail)
        self.assertNotIn("outputDestinationIndex", side_rail)

    def test_recipe_prompt_wraps_and_amount_actions_form_one_segmented_strip(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        recipe_panel = self.java_block(
            screen,
            r"\bprivate\s+void\s+renderRecipePanel\s*\(",
            "CraftingTerminalScreen.renderRecipePanel",
        )
        empty_start = recipe_panel.index("if (presentation.isEmpty())")
        empty_branch = recipe_panel[empty_start:recipe_panel.index("return;", empty_start)]
        self.assertIn("geometry.recipeContent()", recipe_panel)
        self.assertIn("font.split", empty_branch)
        self.assertNotIn("plainSubstrByWidth", empty_branch)
        self.assertIn("renderWrappedPrompt", empty_branch)
        self.assertIn("content", empty_branch)
        self.assertNotIn("ledger", empty_branch)
        self.assertLess(
            recipe_panel.index("return;", empty_start),
            recipe_panel.index("leftPos + ledger.x()"),
        )

        self.assertIn("class RecipeAmountButton", screen)
        self.assertIn("RecipeAmountSegment", screen)
        self.assertIn("addRecipeAmountButton", screen)
        init = self.java_block(
            screen,
            r"\bprotected\s+void\s+init\s*\(",
            "CraftingTerminalScreen.init",
        )
        amount_controls = init[init.index("List<TerminalLayout.Rect> craftButtons"):]
        self.assertNotIn("Button.builder", amount_controls)
        self.assertEqual(4, amount_controls.count("addRecipeAmountButton("))
        self.assertIn("contiguousSegmentRects", layout)

    def test_fuel_target_popup_renders_once_after_container_foreground(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        self.assertIn("addWidget(fuelTargetPopup)", screen)
        self.assertNotIn("addRenderableWidget(fuelTargetPopup)", screen)
        foreground = self.java_block(
            screen,
            r"\bpublic\s+void\s+render\s*\(\s*GuiGraphics",
            "Crafting Terminal foreground overlay pass",
        )
        self.assertLess(foreground.index("super.render"), foreground.index("fuelTargetPopup.render"))
        self.assertRegex(foreground, r"translate\([^;]*[3-9]\d\d(?:\.0)?F?\s*\)")

    def test_terminal_control_name_icon_is_even_grid_centered(self):
        atlas = ROOT / "src/main/resources/assets/magic_storage/textures/gui/terminal_controls.png"
        width, height, pixels = self.rgba_png_pixels(atlas)
        self.assertEqual((256, 16), (width, height))
        first = 2 * 16
        points = [
            (x, y)
            for y in range(16)
            for x in range(16)
            if pixels[y * width + first + x][3] != 0
        ]
        self.assertEqual((4, 11), (min(x for x, _ in points), max(x for x, _ in points)))
        self.assertEqual(
            sorted(points),
            sorted((15 - x, y) for x, y in points),
            "the A glyph must be mirrored around the even-grid axis x=7.5",
        )

    def test_machine_descriptors_have_a_public_server_owned_registry_and_fixed_menu_bank(self):
        api = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MachineDescriptorApi.java"
        )
        descriptor = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MachineDescriptor.java"
        )
        table = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MachineEnergyTable.java"
        )
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        packet = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MachineDescriptorStatePacket.java"
        )
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        record = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRecord.java"
        )

        self.assertIn("REGISTRY_KEY", api)
        self.assertIn("createDeferredRegister", api)
        self.assertRegex(api, r"MAX_DESCRIPTORS\s*=\s*256")
        self.assertIn("Ingredient", descriptor)
        self.assertIn("ConsumableValue", descriptor)
        self.assertIn("writeSnapshot", table)
        self.assertIn("readSnapshot", table)
        self.assertIn(
            "MACHINE_SLOT_COUNT = MachineDescriptorApi.MAX_DESCRIPTORS",
            menu,
        )
        self.assertIn("playToClient", self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        ))
        self.assertIn("descriptorId", packet)
        self.assertIn('TAG_MACHINE_DESCRIPTORS = "machineDescriptors"', record)
        self.assertIn("unresolvedMachineEntries", record)
        self.assertNotIn("recoverUnregisteredMachine", core)

    def test_storage_core_breaking_is_tool_independent_creative_safe_and_recoverable(self):
        registration = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java"
        )
        block = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlock.java"
        )
        item = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockItem.java"
        )
        repository = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRepository.java"
        )
        wrench = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/WrenchActions.java"
        )

        core_registration = self.java_block(
            registration,
            r"\bpublic\s+static\s+final\s+DeferredBlock<Block>\s+STORAGE_CORE\b",
            "Storage Core registration",
        )
        self.assertNotIn("requiresCorrectToolForDrops", core_registration)
        self.assertIn("playerWillDestroy", block)
        self.assertIn("prepareRecoveryDrop", block)
        self.assertIn("onExplosionHit", block)
        self.assertIn("RECOVERY_ID", item)
        self.assertIn("CoreStorageRepository", item)
        self.assertIn("extends SavedData", repository)
        self.assertIn("reissueLatest", repository)
        self.assertIn("claimIntoFresh", repository)
        self.assertIn("RegisterCommandsEvent", registration)
        self.assertIn("prepareRecoveryDrop", wrench)

    def test_remote_terminal_uses_its_own_container_title(self):
        remote = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/RemoteTerminalItem.java"
        )
        en_us = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/en_us.json"
        ))
        zh_tw = json.loads(self.read_required(
            "src/main/resources/assets/magic_storage/lang/zh_tw.json"
        ))
        self.assertIn('Component.translatable("container.magic_storage.remote_terminal")', remote)
        self.assertEqual("Remote Terminal", en_us["container.magic_storage.remote_terminal"])
        self.assertEqual("遠端終端機", zh_tw["container.magic_storage.remote_terminal"])

    def test_core_payload_is_owned_by_bounded_world_repository(self):
        repository = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRepository.java"
        )
        record = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CoreStorageRecord.java"
        )
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        block = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlock.java"
        )

        self.assertIn('DATA_NAME = MagicStorage.MODID + "_core_storages"', repository)
        self.assertIn("extends SavedData", repository)
        self.assertRegex(record, r"MAX_SEGMENT_TYPES\s*=\s*63")
        self.assertIn('TAG_INVENTORY_SEGMENTS = "inventorySegments"', record)
        self.assertIn('TAG_STORAGE_ID = "storageId"', core)
        self.assertIn('TAG_STORAGE_SCHEMA = "storageSchema"', core)
        self.assertIn(".tryCreateFresh(", core)
        self.assertNotIn('tag.put("inventory"', core)
        self.assertNotIn('tag.put("energy"', core)
        self.assertNotIn("BlockItem.setBlockEntityData", block)
        self.assertIn("Unsupported Core storage repository root", repository)
        self.assertIn("Orphan Core storage record", repository)
        self.assertIn("Corrupt Core storage record", repository)
        self.assertIn(
            "Duplicate Core storage record at index {} for storageId={}; "
            "preserving all copies as unavailable data",
            repository,
        )
        self.assertIn("Unresolved Core recovery entry", repository)
        self.assertFalse((ROOT / (
            "src/main/java/com/swearprom/magicstorage/magic_storage/"
            "CoreRecoverySavedData.java"
        )).exists())

    def test_texture_family_encodes_distinct_roles_direction_and_declared_symmetry(self):
        art = ROOT / "art/texture-generation/20260714-terminal-family"
        manifest = json.loads((art / "selection.json").read_text())
        members = manifest["members"]

        def member_pixels(texture_id: str):
            path = ROOT / members[texture_id]["runtime"]
            width, height, pixels = self.rgba_png_pixels(path)
            self.assertEqual((16, 16), (width, height), texture_id)
            return pixels

        x_symmetric = [
            "magic_storage:block/storage_core",
            "magic_storage:block/storage_terminal",
            "magic_storage:block/crafting_terminal",
            *[f"magic_storage:block/storage_unit_t{tier}" for tier in range(1, 7)],
            "magic_storage:block/creative_storage_unit",
            "magic_storage:block/import_bus_top",
            "magic_storage:block/import_bus_side",
            "magic_storage:block/import_bus_front",
            "magic_storage:block/export_bus_top",
            "magic_storage:block/export_bus_side",
            "magic_storage:block/export_bus_front",
        ]
        for texture_id in x_symmetric:
            self.assertIn("x", members[texture_id].get("symmetry_axes", []), texture_id)
            pixels = member_pixels(texture_id)
            self.assertTrue(all(
                pixels[y * 16 + x] == pixels[y * 16 + 15 - x]
                for y in range(16) for x in range(8)
            ), texture_id)

        terminal_ids = [
            "magic_storage:block/storage_core",
            "magic_storage:block/storage_terminal",
            "magic_storage:block/crafting_terminal",
        ]
        for left_index, left_id in enumerate(terminal_ids):
            for right_id in terminal_ids[left_index + 1:]:
                changed = sum(
                    left != right
                    for left, right in zip(member_pixels(left_id), member_pixels(right_id))
                )
                self.assertGreaterEqual(changed, 24, f"{left_id} and {right_id} are too similar")

        for tier in range(1, 6):
            left = member_pixels(f"magic_storage:block/storage_unit_t{tier}")
            right = member_pixels(f"magic_storage:block/storage_unit_t{tier + 1}")
            self.assertGreaterEqual(
                sum(a != b for a, b in zip(left, right)),
                8,
                f"adjacent storage tiers {tier}/{tier + 1} need a readable main-face change",
            )

        for face in ("top", "side"):
            imported = member_pixels(f"magic_storage:block/import_bus_{face}")
            exported = member_pixels(f"magic_storage:block/export_bus_{face}")
            self.assertGreaterEqual(
                sum(a != b for a, b in zip(imported, exported)),
                8,
                f"Import and Export Bus {face} faces must remain distinguishable",
            )


if __name__ == "__main__":
    unittest.main()
