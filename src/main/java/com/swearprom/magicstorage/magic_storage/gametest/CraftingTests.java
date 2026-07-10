package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(MagicStorage.MODID)
public class CraftingTests {

    @GameTest(template = "platform")
    public static void iron_ingot_has_smelting_and_blasting_recipes(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(0, player.getInventory(), core);
            menu.lookUpRecipes(level, new ItemStack(Items.IRON_INGOT));
            if (menu.getRecipeCount() < 2) helper.fail("Iron ingot should have >=2 recipes (smelt+blast)");
            boolean foundSmelting = false, foundBlasting = false;
            for (int r = 0; r < menu.getRecipeCount(); r++) {
                var type = menu.getCurrentRecipes().get(r).value().getType();
                if (type == RecipeType.SMELTING) foundSmelting = true;
                if (type == RecipeType.BLASTING) foundBlasting = true;
            }
            if (!foundSmelting) helper.fail("Smelting recipe not found for iron ingot");
            if (!foundBlasting) helper.fail("Blasting recipe not found for iron ingot");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void recipe_navigation_next_and_prev(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(1, player.getInventory(), core);
            menu.lookUpRecipes(level, new ItemStack(Items.IRON_INGOT));
            if (menu.getRecipeCount() < 2) { helper.fail("Need >=2 recipes for nav test"); return; }
            int start = menu.getCurrentRecipeIndex();
            menu.nextRecipe();
            int afterNext = menu.getCurrentRecipeIndex();
            if (afterNext == start) helper.fail("nextRecipe should change index");
            menu.prevRecipe();
            if (menu.getCurrentRecipeIndex() != start) helper.fail("prevRecipe should return to start");
            // Wrap around should go to first recipe
            for (int i = 0; i < menu.getRecipeCount(); i++) menu.nextRecipe();
            if (menu.getCurrentRecipeIndex() != 0) helper.fail("Wrapping should return to recipe 0");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void toggle_show_only_craftable(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(2, player.getInventory(), core);
            if (menu.isShowOnlyCraftable()) helper.fail("showOnlyCraftable should default false");
            menu.toggleShowOnlyCraftable();
            if (!menu.isShowOnlyCraftable()) helper.fail("toggle should enable showOnlyCraftable");
            menu.toggleShowOnlyCraftable();
            if (menu.isShowOnlyCraftable()) helper.fail("toggle again should disable");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void craftable_only_filters_grid_to_craftable_outputs(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.OAK_PLANKS, 64));
            core.insertItem(new ItemStack(Items.STICK, 1));
            core.insertItem(new ItemStack(Items.DIAMOND, 1));
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(20, player.getInventory(), core);

            menu.refreshDisplayItems(core);
            if (menu.getTotalItemTypes() != 3) { helper.fail("unfiltered grid should show 3 item types, got " + menu.getTotalItemTypes()); return; }

            menu.toggleShowOnlyCraftable();
            menu.refreshDisplayItems(core);
            if (menu.getTotalItemTypes() != 1) { helper.fail("craftable-only grid should show 1 item type, got " + menu.getTotalItemTypes()); return; }
            if (!menu.getSlot(0).getItem().is(Items.STICK)) { helper.fail("stick should be the craftable output, got " + menu.getSlot(0).getItem()); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void constructor_applies_compact_mode_to_initial_grid(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.DIAMOND_SWORD));
            ItemStack damagedSword = new ItemStack(Items.DIAMOND_SWORD);
            damagedSword.setDamageValue(1);
            core.insertItem(damagedSword);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(21, player.getInventory(), core);

            if (!menu.isCompactMode()) { helper.fail("Crafting terminal default should be compact"); return; }
            if (menu.getTotalItemTypes() != 1) {
                helper.fail("Initial compact grid should group sword variants into 1 item type, got " + menu.getTotalItemTypes());
                return;
            }
            if (menu.getSlot(0).getItem().getCount() != 2) {
                helper.fail("Initial compact grid should show combined sword count 2, got " + menu.getSlot(0).getItem());
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void toggle_use_player_inventory(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(3, player.getInventory(), core);
            if (menu.isUsePlayerInventory()) helper.fail("should default false");
            menu.toggleUsePlayerInventory();
            if (!menu.isUsePlayerInventory()) helper.fail("toggle should enable");
            menu.toggleUsePlayerInventory();
            if (menu.isUsePlayerInventory()) helper.fail("toggle again should disable");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void toggle_compact_mode(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(4, player.getInventory(), core);
            if (!menu.isCompactMode()) helper.fail("compactMode should default true");
            menu.toggleCompactMode();
            if (menu.isCompactMode()) helper.fail("toggle should disable compact mode");
            menu.toggleCompactMode();
            if (!menu.isCompactMode()) helper.fail("toggle again should re-enable");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void stonecutting_recipe_lookup(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(5, player.getInventory(), core);
            menu.lookUpRecipes(level, new ItemStack(Items.STONE_BRICKS));
            if (menu.getRecipeCount() < 1) helper.fail("Stone bricks should have at least crafting recipe");
            boolean foundStonecutting = false;
            for (int r = 0; r < menu.getRecipeCount(); r++) {
                if (menu.getCurrentRecipes().get(r).value().getType() == RecipeType.STONECUTTING)
                    foundStonecutting = true;
            }
            if (!foundStonecutting) helper.fail("Stone bricks should have stonecutting recipe");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void campfire_recipe_lookup(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(6, player.getInventory(), core);
            menu.lookUpRecipes(level, new ItemStack(Items.COOKED_BEEF));
            if (menu.getRecipeCount() < 1) helper.fail("Cooked beef should have smelting recipe");
            boolean foundCampfire = false;
            for (int r = 0; r < menu.getRecipeCount(); r++) {
                if (menu.getCurrentRecipes().get(r).value().getType() == RecipeType.CAMPFIRE_COOKING)
                    foundCampfire = true;
            }
            if (!foundCampfire) helper.fail("Cooked beef should have campfire recipe");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void recipe_energy_cost_per_type(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(7, player.getInventory(), core);

            // Smelting cost
            var smeltCost = RecipeEnergyTable.getCost(RecipeType.SMELTING);
            if (smeltCost == null) helper.fail("Smelting should have cost");
            if (smeltCost.processType() != EnergyType.SMELTING_ENERGY) helper.fail("Smelting uses smelting_energy");
            if (smeltCost.fuelType() != EnergyType.FURNACE_FUEL) helper.fail("Smelting uses furnace_fuel");

            // Blasting cost (different process energy)
            var blastCost = RecipeEnergyTable.getCost(RecipeType.BLASTING);
            if (blastCost == null) helper.fail("Blasting should have cost");
            if (blastCost.processType() != EnergyType.BLASTING_ENERGY) helper.fail("Blasting uses blasting_energy");

            // Crafting has no cost
            if (RecipeEnergyTable.getCost(RecipeType.CRAFTING) != null) helper.fail("Crafting should be free");
            if (RecipeEnergyTable.getCost(RecipeType.STONECUTTING) != null) helper.fail("Stonecutting should be free");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void multi_ingredient_recipe_lookup(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(8, player.getInventory(), core);

            // Pickaxe needs stick + material (multiple ingredients)
            menu.lookUpRecipes(level, new ItemStack(Items.IRON_PICKAXE));
            if (menu.getRecipeCount() < 1) { helper.fail("Iron pickaxe should have crafting recipe"); return; }
            var recipe = menu.getCurrentRecipes().get(0).value();
            if (recipe.getType() != RecipeType.CRAFTING) helper.fail("Pickaxe is crafting type");
            var ingredients = recipe.getIngredients();
            if (ingredients.size() < 2) helper.fail("Pickaxe should have multiple ingredients");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void selection_tracks_item_identity_after_grid_reorder(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.APPLE, 1));
            core.insertItem(new ItemStack(Items.IRON_INGOT, 1));
            core.insertItem(new ItemStack(Items.STONE, 1));
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(0, player.getInventory(), core);
            menu.refreshDisplayItems(core);
            // Compact mode sorts by name: Apple(0), Iron Ingot(1), Stone(2).
            int ironSlot = -1;
            for (int s = 0; s < StorageTerminalMenu.DISPLAY_SLOTS; s++) {
                if (menu.getSlot(s).getItem().is(Items.IRON_INGOT)) { ironSlot = s; break; }
            }
            if (ironSlot < 0) { helper.fail("Iron ingot not in grid"); return; }
            menu.clicked(ironSlot, 0, ClickType.PICKUP, player);
            if (!menu.getSelectedStack().is(Items.IRON_INGOT)) helper.fail("Selected should be iron ingot");
            // Reorder grid: remove Apple (sorts before iron), so iron's slot index shifts down.
            core.extractItem(ItemKey.of(new ItemStack(Items.APPLE)), 1);
            menu.refreshDisplayItems(core);
            ItemStack nowAtIronSlot = menu.getSlot(ironSlot).getItem();
            if (nowAtIronSlot.is(Items.IRON_INGOT))
                helper.fail("Grid did not reorder; test setup invalid");
            // Selection must still be iron ingot, recipes still iron ingot's.
            if (!menu.getSelectedStack().is(Items.IRON_INGOT))
                helper.fail("Selection should still be iron ingot after reorder, was " + menu.getSelectedStack());
            boolean foundSmelting = false;
            for (int r = 0; r < menu.getRecipeCount(); r++) {
                if (menu.getCurrentRecipes().get(r).value().getType() == RecipeType.SMELTING) foundSmelting = true;
            }
            if (!foundSmelting) helper.fail("Recipes should still resolve iron ingot (smelting) after reorder");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void craft_preview_reports_craftable_and_missing(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(0, player.getInventory(), core);
            // Iron block = 9 iron ingots, crafting (no energy).
            menu.lookUpRecipes(level, new ItemStack(Items.IRON_BLOCK));
            if (menu.getRecipeCount() < 1) { helper.fail("Iron block should have crafting recipe"); return; }
            // No materials -> craftable 0, missing non-empty.
            var empty = menu.computeCraftPreview(core, player);
            if (empty.craftable() != 0) helper.fail("With no materials craftable should be 0, got " + empty.craftable());
            if (empty.missing().isEmpty()) helper.fail("With no materials missing should be non-empty");
            // Enough materials -> craftable >= 1, missing empty.
            core.insertItem(new ItemStack(Items.IRON_INGOT, 64));
            var ok = menu.computeCraftPreview(core, player);
            if (ok.craftable() < 1) helper.fail("With 64 ingots craftable should be >=1, got " + ok.craftable());
            if (!ok.missing().isEmpty()) helper.fail("With enough materials missing should be empty");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void duplicate_ingredients_are_aggregated_before_crafting(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.IRON_INGOT, 8));

            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(0, player.getInventory(), core);
            menu.lookUpRecipes(level, new ItemStack(Items.IRON_BLOCK));
            if (menu.getRecipeCount() < 1) { helper.fail("Iron block should have a crafting recipe"); return; }

            var preview = menu.computeCraftPreview(core, player);
            if (preview.craftable() != 0) {
                helper.fail("Eight ingots must not preview as craftable iron blocks, got " + preview.craftable());
                return;
            }

            menu.craftItem(1, player);
            if (core.getItemCount(ItemKey.of(new ItemStack(Items.IRON_INGOT))) != 8) {
                helper.fail("Failed craft must leave the eight ingots in storage");
                return;
            }
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (player.getInventory().getItem(i).is(Items.IRON_BLOCK)) {
                    helper.fail("Failed craft must not create an iron block");
                    return;
                }
            }
            helper.succeed();
        });
    }
}
