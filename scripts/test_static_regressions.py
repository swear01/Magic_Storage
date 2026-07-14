from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StaticRegressionTests(unittest.TestCase):
    def read_required(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.exists(), f"missing {relative_path}")
        return path.read_text()

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

    def test_fuel_page_names_mixed_machine_station_tool_slots_as_installed_stations(self):
        lang = json.loads(
            self.read_required("src/main/resources/assets/magic_storage/lang/en_us.json")
        )
        self.assertEqual(
            "Installed Stations",
            lang["gui.magic_storage.installed_machines"],
        )

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
        self.assertIn("CraftingTerminalMenu.supportsRecipeContract(backingRecipe.value())", supports_recipe)
        self.assertIn("getPage().isItemPage()", can_craft)
        self.assertIn("supportsRecipe(recipe)", can_craft)
        self.assertIn("!menu.getPage().isItemPage()", craft)
        self.assertIn("supportsRecipe(recipe)", craft)

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

    def test_development_runtime_includes_full_emi_without_release_loader_dependency(self):
        build = self.read_required("build.gradle")
        metadata = self.read_required("src/main/templates/META-INF/neoforge.mods.toml")

        self.assertRegex(
            build,
            r'compileOnly\s+"dev\.emi:emi-neoforge:\$\{emi_version\}:api"',
        )
        self.assertRegex(
            build,
            r'runtimeOnly\s+"dev\.emi:emi-neoforge:\$\{emi_version\}"',
            "the normal development client must exercise the full EMI runtime",
        )
        self.assertNotRegex(
            metadata,
            r'modId\s*=\s*"emi"',
            "EMI remains an optional released-mod integration",
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

    def test_runtime_textures_exclude_generation_metadata_and_previews(self):
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

        invalid_textures = []
        for texture_id in sorted(texture_ids):
            texture_path = textures / f"{texture_id.split(':', 1)[1]}.png"
            relative_path = texture_path.relative_to(ROOT).as_posix()
            if not texture_path.is_file():
                invalid_textures.append(f"missing {relative_path}")
                continue

            with texture_path.open("rb") as texture_file:
                header = texture_file.read(24)
            if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
                invalid_textures.append(f"not a PNG {relative_path}")
                continue

            dimensions = (
                int.from_bytes(header[16:20], "big"),
                int.from_bytes(header[20:24], "big"),
            )
            if dimensions != (16, 16):
                invalid_textures.append(f"{relative_path} is {dimensions[0]}x{dimensions[1]}")

        self.assertEqual([], invalid_textures)

    def test_crafting_terminal_uses_dedicated_fuel_page_without_popup_path(self):
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
            "gui.magic_storage.energy_reserves",
        ]:
            self.assertIn(key, lang)
        self.assertNotIn("gui.magic_storage.previous_fuel_target", lang)
        self.assertNotIn("gui.magic_storage.next_fuel_target", lang)

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
        self.assertIn("geometry.machineGrid()", screen)
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
        self.assertIn("MACHINE_CELL_PREFERRED_WIDTH", layout)
        self.assertIn("int minimumColumns = (visible + maxRows - 1) / maxRows;", layout)
        self.assertIn("Math.clamp(preferredColumns, minimumColumns, largestColumns)", layout)
        self.assertIn("(column + 1) * bounds.width() / columns", layout)
        self.assertIn("entries()", machine_table)
        for stale in ["MACHINE_ENERGY_TYPES", "MACHINE_LABEL_KEYS", "STORED_FUEL_TYPES", "FUEL_LABEL_KEYS"]:
            self.assertNotIn(stale, screen)
        self.assertNotIn("machine_rate_hint", screen)
        machine_panel = screen[screen.index("private void renderMachinePanel"):screen.index("private void renderFuelPanel")]
        fuel_panel = screen[screen.index("private void renderFuelPanel"):screen.index("private void drawFlowPageIndicator")]
        self.assertNotIn("drawCenteredString", machine_panel + fuel_panel)
        self.assertIn("drawCenteredNoShadow", machine_panel + fuel_panel)
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
            crafting.index("private void renderFuelPanel"):
            crafting.index("private void drawFlowPageIndicator")
        ]
        self.assertIn("type.representativeStack()", fuel_panel)
        self.assertNotIn("drawEnergyIcon", fuel_panel)
        self.assertNotIn("nextFuelTargetBtn", crafting)
        self.assertIn("menu.getPage() == CraftingTerminalPage.FUEL", crafting)
        self.assertIn("drawTypeCapacity(graphics)", crafting)

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
        self.assertIn("RecipeHolder<?> holder", menu)
        self.assertIn("Recipe<?> recipe = holder.value()", presentation_sync)
        self.assertIn("holder.id()", presentation_sync)
        self.assertIn("output.copy()", presentation_sync)
        self.assertIn("RecipePresentation.metadataCarrier(metadata)", presentation_sync)
        self.assertIn("SELECTION_SLOTS = PRESENTATION_METADATA_SLOT + 1", menu)
        self.assertIn("new SimpleContainer(SELECTION_SLOTS)", menu)
        self.assertIn("new ArrayList<>(2)", menu)

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
            "Rect fuelControlPanel",
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

    def test_recipe_ledger_uses_neutral_items_and_dark_red_energy_tool_rows(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        constants = {
            name: int(value, 16) & 0xFFFFFF
            for name, value in re.findall(
                r"\bstatic\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*"
                r"(0x[0-9A-Fa-f]{8})\s*;",
                screen,
            )
        }

        def palette_names(kind: str, role: str) -> list[str]:
            role_tokens = [role] if role == "BORDER" else ["BACKGROUND", "FILL"]
            return [
                name for name in constants
                if kind in name and any(token in name for token in role_tokens)
            ]

        item_names = palette_names("ITEM", "BACKGROUND") + palette_names("ITEM", "BORDER")
        self.assertGreaterEqual(
            len(set(item_names)),
            2,
            "item rows need explicit neutral background/fill and border palette constants",
        )
        for name in set(item_names):
            red = constants[name] >> 16 & 0xFF
            green = constants[name] >> 8 & 0xFF
            blue = constants[name] & 0xFF
            self.assertLessEqual(max(red, green, blue) - min(red, green, blue), 12)
            self.assertGreaterEqual(screen.count(name), 2, f"{name} must be used by row rendering")

        special_names = set()
        for kind in ["ENERGY", "TOOL"]:
            kind_names = palette_names(kind, "BACKGROUND") + palette_names(kind, "BORDER")
            self.assertGreaterEqual(
                len(set(kind_names)),
                2,
                f"{kind.lower()} rows need explicit dark-red background/fill and border constants",
            )
            special_names.update(kind_names)
        for name in special_names:
            red = constants[name] >> 16 & 0xFF
            green = constants[name] >> 8 & 0xFF
            blue = constants[name] & 0xFF
            self.assertGreater(red, green * 4 // 3, name)
            self.assertGreater(red, blue * 4 // 3, name)
            self.assertGreaterEqual(screen.count(name), 2, f"{name} must be used by row rendering")

        self.assertRegex(screen, r"\bITEM\b")
        self.assertRegex(screen, r"\bENERGY\b")
        self.assertRegex(screen, r"\bTOOL\b")
        self.assertRegex(screen, r"\.available\(\)\s*>=\s*[A-Za-z_]\w*\.required\(\)")

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
        self.assertIn("third page button", guide)
        self.assertNotIn("previous/next", guide)
        self.assertIn("Fuel Target", guide)
        self.assertIn("Energy Reserves header", guide)
        self.assertIn("all currently registered totals", guide)

    def test_remote_access_is_pinned_to_exact_loaded_core_identity(self):
        core = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java"
        )
        self.assertIn("UUID.randomUUID()", core)
        self.assertIn("tag.putUUID(TAG_NETWORK_ID, networkId)", core)
        self.assertIn("tag.hasUUID(TAG_NETWORK_ID)", core)

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


if __name__ == "__main__":
    unittest.main()
