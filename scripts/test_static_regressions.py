from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StaticRegressionTests(unittest.TestCase):
    def read_required(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.exists(), f"missing {relative_path}")
        return path.read_text()

    def test_emi_uses_terminal_display_slot_contract_without_54_slot_hardcode(self):
        text = self.read_required("src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java")
        self.assertNotIn("DISPLAY_SLOTS = 54", text)
        self.assertIn("StorageTerminalMenu.DISPLAY_SLOTS", text)
        self.assertNotRegex(text, r"canCraft\([^)]*\)\s*\{\s*return true;\s*\}")

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


if __name__ == "__main__":
    unittest.main()
