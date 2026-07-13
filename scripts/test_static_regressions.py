from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StaticRegressionTests(unittest.TestCase):
    def read_required(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.exists(), f"missing {relative_path}")
        return path.read_text()

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

    def test_crafting_screen_does_not_recompute_recipes_or_read_core_storage_client_side(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java")
        self.assertNotIn("findRecipesClient", text)
        self.assertNotIn("getCore(minecraft.level)", text)
        self.assertNotIn("RecipeManager mgr = minecraft.level.getRecipeManager()", text)
        self.assertNotIn("import net.minecraft.world.item.crafting.RecipeManager", text)
        self.assertNotIn("import net.minecraft.world.item.crafting.RecipeType", text)
        self.assertNotIn("StorageCoreBlockEntity", text)
        self.assertNotIn("level.getRecipeManager()", text)

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
        self.assertNotIn("fuelTargetButtons", crafting_screen)
        self.assertIn("CycleButton<FuelTargetOption> fuelTargetSelector", crafting_screen)
        self.assertNotIn(".displayOnlyValue()", crafting_screen)
        self.assertNotIn("autoFuelBtn", crafting_screen)
        self.assertNotIn("previousFuelTargetBtn", crafting_screen)
        self.assertNotIn("nextFuelTargetBtn", crafting_screen)
        self.assertIn("CraftingTerminalPage.FUEL", crafting_screen)
        self.assertIn("TerminalLayout.crafting", crafting_screen)
        self.assertIn("railButton", crafting_screen)
        self.assertIn("setSearchControlVisible", crafting_screen)
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

    def test_crafting_terminal_redesign_uses_sidebar_without_sticky_checkbox_focus(self):
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        self.assertNotIn("Checkbox", screen)
        self.assertIn("geometry.railPanel()", screen)
        self.assertIn("MACHINE_SLOT_COUNT", screen)
        self.assertIn("repositionFuelSlots", screen)
        self.assertIn("replaceSlot", screen)
        self.assertIn("geometry.machineGrid()", screen)
        self.assertIn("boolean handled = super.mouseClicked", screen)
        handled = screen.index("boolean handled = super.mouseClicked")
        self.assertGreater(screen.index("setFocused(null)", handled), handled)
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
        self.assertIn("fuelRailButtons", layout)
        self.assertIn("fuelRailPanel", layout)
        self.assertIn("railButtons(imageHeight, List.of(3))", layout)
        self.assertIn("MACHINE_CELL_PREFERRED_WIDTH", layout)
        self.assertIn("int minimumColumns = (visible + maxRows - 1) / maxRows;", layout)
        self.assertIn("Math.clamp(preferredColumns, minimumColumns, largestColumns)", layout)
        self.assertIn("(column + 1) * bounds.width() / columns", layout)
        self.assertIn("CycleButton<FuelTargetOption> fuelTargetSelector", screen)
        self.assertIn("fuelTargetSelector.isMouseOver(mouseX, mouseY)", screen)
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
        self.assertIn("font.width(text) <= 16", storage)
        self.assertIn("graphics.pose().scale(0.5F, 0.5F, 1.0F)", storage)
        self.assertIn("30 - font.width(text)", storage)
        self.assertIn("copyWithCount(1)", storage)
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
        self.assertIn("CycleButton<FuelTargetOption> fuelTargetSelector", crafting)
        self.assertNotIn("nextFuelTargetBtn", crafting)
        self.assertNotIn("target.representativeStack()", crafting)
        self.assertNotIn("private void setItemIcon", crafting)
        self.assertNotIn("railIconForEnergy", crafting)
        self.assertIn("menu.getPage() == CraftingTerminalPage.FUEL", crafting)
        self.assertIn("drawTypeCapacity(graphics)", crafting)

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

    def test_recipe_resources_are_server_synced_and_rail_icons_use_one_canvas(self):
        menu = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        self.assertIn("record IngredientPreview", menu)
        self.assertIn("record EnergyPreview", menu)
        self.assertIn("getIngredientPreview()", screen)
        self.assertIn("getEnergyPreview()", screen)
        self.assertIn("geometry.recipeResourceCells()", screen)
        self.assertIn("class TerminalIconButton", screen)
        self.assertIn("static final int ICON_CANVAS_SIZE = 12", layout)
        self.assertIn("TerminalLayout.ICON_CANVAS_SIZE", screen)
        self.assertIn("recipeResourceCells", layout)
        self.assertIn("geometry.railPanel()", screen)
        self.assertNotIn("RecipeManager", screen)
        self.assertNotIn("StorageCoreBlockEntity", screen)

    def test_gui_sidecar_uses_layout_owned_native_recipe_grammar_and_18px_controls(self):
        layout = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java"
        )
        screen = self.read_required(
            "src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java"
        )

        required_layout_regions = [
            "Rect recipeHeader",
            "Rect recipeInputRegion",
            "Rect recipeArrow",
            "Rect recipeOutput",
            "Rect recipeAvailableHeader",
            "Rect recipeStatus",
            "Rect recipeQuantityFooter",
            "List<Rect> recipeNavigationButtons",
            "List<Rect> recipeCraftButtons",
            "Rect fuelControlPanel",
            "Rect fuelInput",
        ]
        self.assertEqual([], [region for region in required_layout_regions if region not in layout])
        self.assertIn("static final int CONTROL_SIZE = SLOT_SIZE", layout)

        required_screen_usage = [
            "geometry.fuelControlPanel()",
            "geometry.recipeHeader()",
            "geometry.recipeInputRegion()",
            "geometry.recipeArrow()",
            "geometry.recipeOutput()",
            "geometry.recipeAvailableHeader()",
            "geometry.recipeStatus()",
            "geometry.recipeQuantityFooter()",
            "geometry.recipeNavigationButtons()",
            "geometry.recipeCraftButtons()",
            "drawRecipeArrow",
            "menu.getCurrentRecipeTypeLabel()",
            "menu.getIngredientPreview()",
            "menu.getEnergyPreview()",
            "menu.getSelectedStack()",
        ]
        self.assertEqual([], [usage for usage in required_screen_usage if usage not in screen])
        self.assertNotIn("int navigationY =", screen)
        self.assertNotRegex(screen, r"\.bounds\([^\n]*,\s*16\)\.build\(\)")
        self.assertNotIn("RecipeManager", screen)

    def test_terminal_screens_use_one_adaptive_geometry_and_original_slot_delegates(self):
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
        self.assertIn("static Geometry storage", layout)
        self.assertIn("static Geometry crafting", layout)
        self.assertIn("TerminalLayout.storage", storage)
        self.assertIn("TerminalLayout.crafting", crafting)
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
