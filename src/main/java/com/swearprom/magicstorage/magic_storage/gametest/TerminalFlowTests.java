package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(MagicStorage.MODID)
public class TerminalFlowTests {

    @GameTest(template = "platform")
    public static void terminal_insert_via_quick_move(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);

            // Direct insert test
            var testStack = new ItemStack(Items.STONE, 64);
            long directInserted = core.insertItem(testStack.copy(), false);
            if (directInserted == 0) helper.fail("Direct insert returned 0 (slots=" + core.getTotalTypeSlots() + " types=" + core.getTypeCount() + ")");
            if (directInserted != 64) helper.fail("Direct insert should insert 64, got " + directInserted);
            core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), directInserted); // clean up

            // Now test via menu
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var stone = new ItemStack(Items.STONE, 64);
            player.getInventory().add(stone.copy());

            var menu = new StorageTerminalMenu(0, player.getInventory(), core);
            menu.refreshDisplayItems(core);

            // Find the slot index of the stone in player inventory
            int stoneSlot = -1;
            for (int s = StorageTerminalMenu.DISPLAY_SLOTS; s < menu.slots.size(); s++) {
                if (menu.getSlot(s).hasItem() && menu.getSlot(s).getItem().is(Items.STONE)) {
                    stoneSlot = s; break;
                }
            }
            if (stoneSlot < 0) { helper.fail("Could not find stone slot in menu"); return; }

            var result = menu.quickMoveStack(player, stoneSlot);
            if (!result.isEmpty()) helper.fail("quickMove returned: " + result);

            var playerStillHasStone = player.getInventory().getItem(0).getCount() > 0;
            if (playerStillHasStone && core.getTypeCount() == 1) {
                helper.fail("Insertion partially worked (core has items but player still has stone)");
            } else if (!playerStillHasStone && core.getTypeCount() == 0) {
                helper.fail("Player stone removed but core is empty");
            } else if (playerStillHasStone && core.getTypeCount() == 0) {
                helper.fail("Insert completely failed - stone still in player inv, core empty");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void terminal_extract_via_quick_move(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 64));

            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(1, player.getInventory(), core);
            menu.refreshDisplayItems(core);

            // Simulate shift-clicking network slot 0 to extract
            var result = menu.quickMoveStack(player, 0);
            if (!result.isEmpty()) helper.fail("quickMove from network should return empty");
            boolean foundStone = false;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (player.getInventory().getItem(i).is(Items.STONE)) { foundStone = true; break; }
            }
            if (!foundStone) helper.fail("Stone should be in player inventory after extract");
            if (core.getTypeCount() != 0) helper.fail("Core should be empty after full extract");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void terminal_empty_display_when_no_core(GameTestHelper helper) {
        var level = helper.getLevel();
        var termPos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(termPos, MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            // Terminal without a nearby core should not open (BFS returns null)
            var core = MagicStorage.bfsFindCore(level, termPos);
            if (core != null) helper.fail("Should not find core for isolated terminal");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void terminal_finds_core_through_chain(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();
        var termPos = unitPos.east(); // Terminal 2 blocks away from core, through unit

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(termPos, MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var found = MagicStorage.bfsFindCore(level, termPos);
            if (found == null) helper.fail("BFS should find core through chain of blocks");
            if (!found.getBlockPos().equals(corePos)) helper.fail("Found wrong core");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void search_filter_finds_items(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var stone = new ItemStack(Items.STONE, 64);
            var dirt = new ItemStack(Items.DIRT, 64);
            core.insertItem(stone);
            core.insertItem(dirt);
            var stoneFiltered = core.getDisplayStacks("stone");
            if (stoneFiltered.size() != 1) helper.fail("'stone' filter should return 1 item");
            if (!stoneFiltered.get(0).is(Items.STONE)) helper.fail("Filtered item should be stone");
            var emptyFiltered = core.getDisplayStacks("");
            if (emptyFiltered.size() != 2) helper.fail("Empty filter should return all items");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void large_item_count_display(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            // Insert 999 items (more than max stack size 64)
            var stack = new ItemStack(Items.STONE, 999);
            core.insertItem(stack);
            var actualCount = core.getItemCount(ItemKey.of(new ItemStack(Items.STONE)));
            if (actualCount != 999) helper.fail("Should store actual count 999, got " + actualCount);
            var display = core.getDisplayStacks();
            if (display.isEmpty()) helper.fail("Display should not be empty");
            // Extract half
            var extracted = core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), 500);
            if (extracted.getCount() != 500) helper.fail("Extracted wrong amount: " + extracted.getCount());
            var remaining = core.getItemCount(ItemKey.of(new ItemStack(Items.STONE)));
            if (remaining != 499) helper.fail("Remaining should be 499, got " + remaining);
            helper.succeed();
        });
    }
}
