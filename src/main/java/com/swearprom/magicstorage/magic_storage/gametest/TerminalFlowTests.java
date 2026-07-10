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

            var testStack = new ItemStack(Items.STONE, 64);
            long directInserted = core.insertItem(testStack.copy(), false);
            if (directInserted == 0) helper.fail("Direct insert returned 0 (slots=" + core.getTotalTypeSlots() + " types=" + core.getTypeCount() + ")");
            if (directInserted != 64) helper.fail("Direct insert should insert 64, got " + directInserted);
            core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), directInserted);

            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var stone = new ItemStack(Items.STONE, 64);
            player.getInventory().add(stone.copy());

            var menu = new StorageTerminalMenu(0, player.getInventory(), core);
            menu.refreshDisplayItems(core);

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
        var termPos = unitPos.east();

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
            var stack = new ItemStack(Items.STONE, 999);
            core.insertItem(stack);
            var actualCount = core.getItemCount(ItemKey.of(new ItemStack(Items.STONE)));
            if (actualCount != 999) helper.fail("Should store actual count 999, got " + actualCount);
            var display = core.getDisplayStacks();
            if (display.isEmpty()) helper.fail("Display should not be empty");
            if (display.get(0).getCount() != 999) helper.fail("Display count should show 999, got " + display.get(0).getCount());
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(7, player.getInventory(), core);
            menu.refreshDisplayItems(core);
            if (menu.getSlot(0).getItem().getCount() != 999)
                helper.fail("Menu display slot should show 999, got " + menu.getSlot(0).getItem().getCount());
            var extracted = core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), 500);
            if (extracted.getCount() != 500) helper.fail("Extracted wrong amount: " + extracted.getCount());
            var remaining = core.getItemCount(ItemKey.of(new ItemStack(Items.STONE)));
            if (remaining != 499) helper.fail("Remaining should be 499, got " + remaining);
            helper.succeed();
        });
    }

    // ===== New tests for sort/search/visible rows =====

    @GameTest(template = "platform")
    public static void sort_items_by_name_ascending(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.DIAMOND, 1));
            core.insertItem(new ItemStack(Items.STONE, 1));
            core.insertItem(new ItemStack(Items.APPLE, 1));
            var sorted = core.getDisplayStacks("", SortMode.NAME, SortOrder.ASCENDING);
            if (sorted.size() != 3) helper.fail("Should have 3 items, got " + sorted.size());
            if (!sorted.get(0).is(Items.APPLE)) helper.fail("First should be Apple");
            if (!sorted.get(1).is(Items.DIAMOND)) helper.fail("Second should be Diamond");
            if (!sorted.get(2).is(Items.STONE)) helper.fail("Third should be Stone");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void sort_items_by_name_descending(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.APPLE, 1));
            core.insertItem(new ItemStack(Items.DIAMOND, 1));
            core.insertItem(new ItemStack(Items.STONE, 1));
            var sorted = core.getDisplayStacks("", SortMode.NAME, SortOrder.DESCENDING);
            if (sorted.size() != 3) helper.fail("Should have 3 items, got " + sorted.size());
            if (!sorted.get(0).is(Items.STONE)) helper.fail("First desc should be Stone");
            if (!sorted.get(1).is(Items.DIAMOND)) helper.fail("Second desc should be Diamond");
            if (!sorted.get(2).is(Items.APPLE)) helper.fail("Third desc should be Apple");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void sort_items_by_quantity(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 10));
            core.insertItem(new ItemStack(Items.DIRT, 50));
            core.insertItem(new ItemStack(Items.DIAMOND, 3));
            var sorted = core.getDisplayStacks("", SortMode.QUANTITY, SortOrder.ASCENDING);
            if (sorted.size() != 3) helper.fail("Should have 3 items, got " + sorted.size());
            if (!sorted.get(0).is(Items.DIAMOND)) helper.fail("First (fewest) should be Diamond");
            if (!sorted.get(1).is(Items.STONE)) helper.fail("Second should be Stone");
            if (!sorted.get(2).is(Items.DIRT)) helper.fail("Third (most) should be Dirt");
            var descSorted = core.getDisplayStacks("", SortMode.QUANTITY, SortOrder.DESCENDING);
            if (!descSorted.get(0).is(Items.DIRT)) helper.fail("First desc should be Dirt (most)");
            if (!descSorted.get(2).is(Items.DIAMOND)) helper.fail("Last desc should be Diamond (fewest)");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void sort_items_by_id(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            core.insertItem(new ItemStack(Items.DIRT, 1));
            var sorted = core.getDisplayStacks("", SortMode.ID, SortOrder.ASCENDING);
            if (sorted.size() != 2) helper.fail("Should have 2 items, got " + sorted.size());
            var firstId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sorted.get(0).getItem()).toString();
            var secondId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sorted.get(1).getItem()).toString();
            if (firstId.compareTo(secondId) >= 0) helper.fail("ID sort asc: " + firstId + " before " + secondId);
            var descSorted = core.getDisplayStacks("", SortMode.ID, SortOrder.DESCENDING);
            var descFirst = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(descSorted.get(0).getItem()).toString();
            var descSecond = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(descSorted.get(1).getItem()).toString();
            if (descFirst.compareTo(descSecond) <= 0) helper.fail("ID sort desc: " + descFirst + " after " + descSecond);
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void search_filter_by_mod(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            core.insertItem(new ItemStack(Items.DIAMOND, 1));
            var modFiltered = core.getDisplayStacks("@minecraft");
            if (modFiltered.size() != 2) helper.fail("@minecraft should match 2 vanilla items, got " + modFiltered.size());
            var noMatch = core.getDisplayStacks("@nonexistentmod");
            if (!noMatch.isEmpty()) helper.fail("@nonexistentmod should match nothing, got " + noMatch.size());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void search_filter_by_tag(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            core.insertItem(new ItemStack(Items.DIAMOND, 1));
            core.insertItem(new ItemStack(Items.OAK_LOG, 1));
            var tagFiltered = core.getDisplayStacks("#minecraft:logs");
            if (tagFiltered.isEmpty()) helper.fail("#minecraft:logs should match at least OAK_LOG");
            for (var s : tagFiltered) {
                if (s.is(Items.STONE) || s.is(Items.DIAMOND)) helper.fail("Stone/Diamond should not match logs tag");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void menu_apply_settings_changes_sort(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(2, player.getInventory(), core);
            var packet = new TerminalSettingsPacket(0, 9);
            menu.applySettings(packet);
            if (menu.getVisibleRows() != 9) helper.fail("visibleRows should be 9, got " + menu.getVisibleRows());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void visible_rows_clamped_minimum(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(3, player.getInventory(), core);
            var packet = new TerminalSettingsPacket(0, 1);
            menu.applySettings(packet);
            if (menu.getVisibleRows() != 3) helper.fail("visibleRows under 3 clamp to 3, got " + menu.getVisibleRows());
            var packetMax = new TerminalSettingsPacket(0, 20);
            menu.applySettings(packetMax);
            if (menu.getVisibleRows() < 3) helper.fail("visibleRows should stay >= 3, got " + menu.getVisibleRows());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void display_respects_visible_rows(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.insertItem(new ItemStack(Items.DIRT, 64));
            core.insertItem(new ItemStack(Items.DIAMOND, 64));
            core.insertItem(new ItemStack(Items.APPLE, 64));
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(4, player.getInventory(), core);
            var packet = new TerminalSettingsPacket(0, 1);
            menu.applySettings(packet);
            menu.refreshDisplayItems(core);
            if (!menu.getSlot(0).hasItem()) helper.fail("Slot 0 should have item with visibleRows=1");
            if (menu.getSlot(9).hasItem()) helper.fail("Slot 9 should be empty beyond row 0 with visibleRows=1");
            var packet3 = new TerminalSettingsPacket(0, 3);
            menu.applySettings(packet3);
            menu.refreshDisplayItems(core);
            int filled = 0;
            for (int i = 0; i < 27; i++) {
                if (menu.getSlot(i).hasItem()) filled++;
            }
            if (filled < 4) helper.fail("visibleRows=3 should show up to 27 slots, got " + filled + " (need 4 items)");
            if (menu.getSlot(27).hasItem()) helper.fail("Slot 27 should be empty beyond visibleRows=3");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void sort_mode_cycles_through_menu_button(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.DIAMOND, 5));
            core.insertItem(new ItemStack(Items.STONE, 63));
            core.insertItem(new ItemStack(Items.APPLE, 1));
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(5, player.getInventory(), core);
            menu.refreshDisplayItems(core);
            if (menu.getSlot(0).getItem().isEmpty()) helper.fail("Slot 0 should have items");
            menu.clickMenuButton(player, 12);
            menu.refreshDisplayItems(core);
            if (menu.getSlot(0).getItem().isEmpty()) helper.fail("Slot 0 should still have items after sort cycle");
            menu.clickMenuButton(player, 11);
            menu.refreshDisplayItems(core);
            if (!menu.getSlot(0).hasItem()) helper.fail("Slot 0 empty after sort order toggle");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void apply_settings_preserves_sort_state(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            core.insertItem(new ItemStack(Items.APPLE, 64));
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(6, player.getInventory(), core);
            menu.refreshDisplayItems(core);
            if (!menu.getSlot(0).getItem().is(Items.APPLE))
                helper.fail("Default NAME sort: slot 0 should be Apple, got " + menu.getSlot(0).getItem());
            menu.clickMenuButton(player, 12); // NAME -> QUANTITY (ascending: fewest first)
            menu.refreshDisplayItems(core);
            if (!menu.getSlot(0).getItem().is(Items.STONE))
                helper.fail("QUANTITY asc: slot 0 should be Stone (fewest), got " + menu.getSlot(0).getItem());
            menu.applySettings(new TerminalSettingsPacket(0, 9)); // resize must NOT reset sort
            menu.refreshDisplayItems(core);
            if (!menu.getSlot(0).getItem().is(Items.STONE))
                helper.fail("applySettings must preserve QUANTITY sort: slot 0 should still be Stone, got " + menu.getSlot(0).getItem());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void view_settings_synced_to_data_slots(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(7, player.getInventory(), core);
            if (menu.getSortMode() != SortMode.NAME) helper.fail("default sortMode should be NAME, got " + menu.getSortMode());
            if (menu.getSortOrder() != SortOrder.ASCENDING) helper.fail("default sortOrder should be ASCENDING, got " + menu.getSortOrder());
            if (menu.getSearchMode() != SearchMode.NORMAL) helper.fail("default searchMode should be NORMAL, got " + menu.getSearchMode());
            menu.clickMenuButton(player, 12);
            if (menu.getSortMode() != SortMode.QUANTITY)
                helper.fail("clickMenuButton(12) should sync sortMode=QUANTITY, got " + menu.getSortMode());
            menu.clickMenuButton(player, 11);
            if (menu.getSortOrder() != SortOrder.DESCENDING)
                helper.fail("clickMenuButton(11) should sync sortOrder=DESCENDING, got " + menu.getSortOrder());
            menu.clickMenuButton(player, 13);
            if (menu.getSearchMode() != SearchMode.TAG)
                helper.fail("clickMenuButton(13) should sync searchMode=TAG, got " + menu.getSearchMode());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void data_slot_parity_server_vs_buf_ctor(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var serverMenu = new StorageTerminalMenu(8, player.getInventory(), core);

            var byteBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            byteBuf.writeBlockPos(corePos);
            byteBuf.writeBlockPos(corePos);
            byteBuf.writeBoolean(false);
            StorageTerminalMenu bufMenu;
            try {
                var ctor = StorageTerminalMenu.class.getDeclaredConstructor(
                        net.minecraft.world.inventory.MenuType.class, int.class,
                        net.minecraft.world.entity.player.Inventory.class,
                        net.minecraft.network.RegistryFriendlyByteBuf.class);
                ctor.setAccessible(true);
                bufMenu = ctor.newInstance(
                        MagicStorage.STORAGE_TERMINAL_MENU.get(), 9, player.getInventory(), byteBuf);
            } catch (ReflectiveOperationException e) {
                helper.fail("Could not build buf-ctor menu: " + e);
                return;
            }

            int serverCount = dataSlotCount(serverMenu);
            int bufCount = dataSlotCount(bufMenu);
            if (serverCount != bufCount)
                helper.fail("data-slot count mismatch: server=" + serverCount + " buf=" + bufCount);
            if (serverCount < 5)
                helper.fail("expected >=5 synced data slots (types + view settings), got " + serverCount);
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void incremental_add_matches_full_rebuild(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var u1 = corePos.east();
        var u2 = u1.east();
        var u3 = u2.east();
        var u4 = u3.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u1, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u2, MagicStorage.STORAGE_UNIT_T2.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u3, MagicStorage.STORAGE_UNIT_T3.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            long baseline = core.getTotalTypeSlots();
            int baseMembers = core.getConnectedBlocks().size();
            if (baseline != 10 + 25 + 50) helper.fail("baseline capacity wrong, got " + baseline);

            level.setBlock(u4, MagicStorage.STORAGE_UNIT_T4.get().defaultBlockState(), Block.UPDATE_ALL);
            boolean grew = core.tryIncrementalAdd(level, u4);
            if (!grew) helper.fail("tryIncrementalAdd should grow for a neighbour of a connected member");
            long afterIncremental = core.getTotalTypeSlots();
            int afterMembers = core.getConnectedBlocks().size();
            if (afterMembers != baseMembers + 1) helper.fail("incremental should add exactly 1 member, got " + afterMembers);

            core.rebuildNetwork(level);
            long authoritative = core.getTotalTypeSlots();
            if (afterIncremental != authoritative)
                helper.fail("incremental capacity " + afterIncremental + " != full-rebuild " + authoritative);
            if (authoritative != 10 + 25 + 50 + 100) helper.fail("full-rebuild capacity wrong, got " + authoritative);
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void incremental_add_rejects_non_adjacent(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var u1 = corePos.east();
        var detached = corePos.south().south();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u1, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);

            level.setBlock(detached, MagicStorage.STORAGE_UNIT_T2.get().defaultBlockState(), Block.UPDATE_ALL);
            boolean grew = core.tryIncrementalAdd(level, detached);
            if (grew) helper.fail("tryIncrementalAdd must reject a block not touching the connected set");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void break_middle_unit_full_rebuild_capacity(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var u1 = corePos.east();
        var u2 = u1.east();
        var u3 = u2.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u1, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u2, MagicStorage.STORAGE_UNIT_T2.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(u3, MagicStorage.STORAGE_UNIT_T3.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            if (core.getTotalTypeSlots() != 10 + 25 + 50) helper.fail("pre-break capacity wrong, got " + core.getTotalTypeSlots());

            level.removeBlock(u2, false);
            core.rebuildNetwork(level);
            if (core.getTotalTypeSlots() != 10) helper.fail("after breaking middle unit, only u1 stays connected: expected 10, got " + core.getTotalTypeSlots());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void multi_core_network_is_disabled(GameTestHelper helper) {
        var level = helper.getLevel();
        var coreA = helper.absolutePos(new BlockPos(1, 3, 1));
        var unit = coreA.east();
        var coreB = unit.east();
        level.setBlock(coreA, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unit, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(coreB, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(coreA);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core A not found"); return; }
            core.rebuildNetwork(level);
            if (!core.isConflicted()) { helper.fail("multi-core network should be conflicted"); return; }
            long inserted = core.insertItem(new ItemStack(Items.STONE, 64));
            if (inserted != 0) { helper.fail("conflicted network must not accept items, got " + inserted); return; }
            ItemStack extracted = core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), 1);
            if (!extracted.isEmpty()) { helper.fail("conflicted network must not extract items, got " + extracted); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void import_bus_stops_after_network_disconnect(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();
        var busPos = unitPos.east();
        var chestPos = busPos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos,
                MagicStorage.IMPORT_BUS.get().defaultBlockState().setValue(ImportBusBlock.FACING, net.minecraft.core.Direction.EAST),
                Block.UPDATE_ALL);
        level.setBlock(chestPos, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var coreBe = level.getBlockEntity(corePos);
            if (!(coreBe instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var chestBe = level.getBlockEntity(chestPos);
            if (!(chestBe instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity chest)) {
                helper.fail("Barrel not found"); return;
            }
            chest.setItem(0, new ItemStack(Items.STONE, 1));
            chest.setChanged();
            var busBe = level.getBlockEntity(busPos);
            if (!(busBe instanceof ImportBusBlockEntity bus)) { helper.fail("Import bus not found"); return; }
            for (int i = 0; i < 11; i++) bus.tick();
            var key = ItemKey.of(new ItemStack(Items.STONE));
            if (core.getItemCount(key) != 1) { helper.fail("initial import should put 1 stone in core, got " + core.getItemCount(key)); return; }

            level.removeBlock(unitPos, false);
            core.rebuildNetwork(level);
            chest.setItem(0, new ItemStack(Items.STONE, 1));
            chest.setChanged();
            for (int i = 0; i < 11; i++) bus.tick();
            if (core.getItemCount(key) != 1) { helper.fail("disconnected import bus must not keep importing via cached core, got " + core.getItemCount(key)); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void import_bus_does_not_extract_when_type_slots_full(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var busPos = corePos.east();
        var chestPos = busPos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos,
                MagicStorage.IMPORT_BUS.get().defaultBlockState().setValue(ImportBusBlock.FACING, net.minecraft.core.Direction.EAST),
                Block.UPDATE_ALL);
        level.setBlock(chestPos, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var coreBe = level.getBlockEntity(corePos);
            if (!(coreBe instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            ItemStack[] tenTypes = {
                    new ItemStack(Items.STONE), new ItemStack(Items.DIRT), new ItemStack(Items.COBBLESTONE),
                    new ItemStack(Items.GRANITE), new ItemStack(Items.DIORITE), new ItemStack(Items.ANDESITE),
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.SPRUCE_LOG), new ItemStack(Items.BIRCH_LOG),
                    new ItemStack(Items.SAND)
            };
            for (ItemStack stack : tenTypes) {
                long inserted = core.insertItem(stack.copy());
                if (inserted != 1) { helper.fail("setup should fill each T1 type slot, got " + inserted + " for " + stack); return; }
            }
            if (core.getTypeCount() != core.getTotalTypeSlots()) {
                helper.fail("setup should fill all type slots: types=" + core.getTypeCount() + " max=" + core.getTotalTypeSlots());
                return;
            }
            var chestBe = level.getBlockEntity(chestPos);
            if (!(chestBe instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel)) {
                helper.fail("Barrel not found"); return;
            }
            barrel.setItem(0, new ItemStack(Items.GOLD_INGOT, 16));
            barrel.setChanged();
            var busBe = level.getBlockEntity(busPos);
            if (!(busBe instanceof ImportBusBlockEntity bus)) { helper.fail("Import bus not found"); return; }
            for (int i = 0; i < 11; i++) bus.tick();
            if (barrel.getItem(0).getCount() != 16) {
                helper.fail("full type storage must not make import bus extract and lose items, got " + barrel.getItem(0));
                return;
            }
            if (core.getItemCount(ItemKey.of(new ItemStack(Items.GOLD_INGOT))) != 0) {
                helper.fail("new type should not be inserted when all type slots are full");
                return;
            }
            if (core.getTypeCount() != core.getTotalTypeSlots()) {
                helper.fail("type count should remain full without overflowing");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void search_filter_invalid_tag_tokens_return_empty(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            try {
                if (!core.getDisplayStacks("#").isEmpty()) { helper.fail("bare # tag token should match nothing"); return; }
                if (!core.getDisplayStacks("#:").isEmpty()) { helper.fail("invalid #: tag token should match nothing"); return; }
            } catch (RuntimeException e) {
                helper.fail("invalid tag tokens must not throw: " + e);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void incremental_growth_rebuilds_when_placed_block_bridges_detached_segment(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var connected = corePos.east();
        var bridge = corePos.east(2);
        var detached = corePos.east(3);
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(connected, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(detached, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            if (core.getConnectedBlocks().contains(detached)) { helper.fail("test setup invalid: detached segment already connected"); return; }
            level.setBlock(bridge, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
            MagicStorage.findCoresAndGrow(level, bridge);
            if (!core.getConnectedBlocks().contains(bridge)) { helper.fail("placed bridge should connect to core"); return; }
            if (!core.getConnectedBlocks().contains(detached)) { helper.fail("bridge placement must rebuild and include detached segment"); return; }
            if (core.getTotalTypeSlots() != 30) { helper.fail("all three T1 units should contribute 30 slots, got " + core.getTotalTypeSlots()); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void incremental_bridge_between_two_cores_conflicts_both_networks(GameTestHelper helper) {
        var level = helper.getLevel();
        var coreA = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitA = coreA.east();
        var bridge = coreA.east(2);
        var unitB = coreA.east(3);
        var coreB = coreA.east(4);
        level.setBlock(coreA, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitA, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitB, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(coreB, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var beA = level.getBlockEntity(coreA);
            var beB = level.getBlockEntity(coreB);
            if (!(beA instanceof StorageCoreBlockEntity a)) { helper.fail("Core A not found"); return; }
            if (!(beB instanceof StorageCoreBlockEntity b)) { helper.fail("Core B not found"); return; }
            a.rebuildNetwork(level);
            b.rebuildNetwork(level);
            if (a.isConflicted() || b.isConflicted()) { helper.fail("test setup invalid: cores conflicted before bridge"); return; }
            level.setBlock(bridge, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
            MagicStorage.findCoresAndGrow(level, bridge);
            if (!a.isConflicted()) { helper.fail("core A should conflict when bridge joins two cores"); return; }
            if (!b.isConflicted()) { helper.fail("core B should conflict when bridge joins two cores"); return; }
            if (a.insertItem(new ItemStack(Items.STONE, 1)) != 0) { helper.fail("conflicted core A must not accept items"); return; }
            if (b.insertItem(new ItemStack(Items.STONE, 1)) != 0) { helper.fail("conflicted core B must not accept items"); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "range_platform")
    public static void terminal_validates_against_access_block_not_core_distance(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var terminalPos = corePos.east(10);
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        for (int i = 1; i < 10; i++) {
            level.setBlock(corePos.east(i), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(terminalPos, MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            player.moveTo(terminalPos.getX() + 0.5, terminalPos.getY() + 0.5, terminalPos.getZ() + 0.5, 0, 0);
            var menu = new StorageTerminalMenu(0, player.getInventory(), core, terminalPos, false);
            if (!menu.stillValid(player)) helper.fail("terminal menu should remain valid near terminal even when core is far");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void remote_terminal_valid_away_from_bound_core(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            player.moveTo(corePos.getX() + 100.5, corePos.getY() + 0.5, corePos.getZ() + 100.5, 0, 0);
            var menu = new CraftingTerminalMenu(0, player.getInventory(), core, corePos, true);
            if (!menu.stillValid(player)) helper.fail("remote terminal should remain valid away from bound core");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void terminal_pickup_click_amounts_match_vanilla_like_stacks(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var key = ItemKey.of(new ItemStack(Items.STONE));
            core.insertItem(new ItemStack(Items.STONE, 100));
            var leftPlayer = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var leftMenu = new StorageTerminalMenu(30, leftPlayer.getInventory(), core);
            leftMenu.refreshDisplayItems(core);
            leftMenu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, leftPlayer);
            if (!leftMenu.getCarried().is(Items.STONE) || leftMenu.getCarried().getCount() != 64) {
                helper.fail("left click should carry one stack of 64, got " + leftMenu.getCarried());
                return;
            }
            if (core.getItemCount(key) != 36) { helper.fail("left click should leave 36, got " + core.getItemCount(key)); return; }

            core.extractItem(key, 36);
            core.insertItem(new ItemStack(Items.STONE, 100));
            var rightPlayer = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var rightMenu = new StorageTerminalMenu(31, rightPlayer.getInventory(), core);
            rightMenu.refreshDisplayItems(core);
            rightMenu.clicked(0, 1, net.minecraft.world.inventory.ClickType.PICKUP, rightPlayer);
            if (!rightMenu.getCarried().is(Items.STONE) || rightMenu.getCarried().getCount() != 32) {
                helper.fail("right click should carry half stack of 32, got " + rightMenu.getCarried());
                return;
            }
            if (core.getItemCount(key) != 68) { helper.fail("right click should leave 68, got " + core.getItemCount(key)); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void terminal_invalidates_when_access_block_is_removed(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var terminalPos = corePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(terminalPos, MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            player.moveTo(terminalPos.getX() + 0.5, terminalPos.getY() + 0.5, terminalPos.getZ() + 0.5, 0, 0);
            var menu = new StorageTerminalMenu(32, player.getInventory(), core, terminalPos, false);
            if (!menu.stillValid(player)) { helper.fail("menu should start valid while access terminal exists"); return; }
            level.removeBlock(terminalPos, false);
            if (menu.stillValid(player)) { helper.fail("menu must invalidate when the access terminal block is removed"); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void remote_terminal_invalidates_when_bound_core_is_removed(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            player.moveTo(corePos.getX() + 100.5, corePos.getY() + 0.5, corePos.getZ() + 100.5, 0, 0);
            var menu = new CraftingTerminalMenu(33, player.getInventory(), core, corePos, true);
            if (!menu.stillValid(player)) { helper.fail("remote menu should start valid while core exists"); return; }
            level.removeBlock(corePos, false);
            if (menu.stillValid(player)) { helper.fail("remote menu must invalidate when the bound core block is removed"); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void export_bus_only_extracts_what_target_can_accept(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var busPos = corePos.east();
        var chestPos = busPos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos,
                MagicStorage.EXPORT_BUS.get().defaultBlockState().setValue(ExportBusBlock.FACING, net.minecraft.core.Direction.EAST),
                Block.UPDATE_ALL);
        level.setBlock(chestPos, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var coreBe = level.getBlockEntity(corePos);
            if (!(coreBe instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 10));
            var chestBe = level.getBlockEntity(chestPos);
            if (!(chestBe instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel)) { helper.fail("Barrel not found"); return; }
            barrel.setItem(0, new ItemStack(Items.STONE, 63));
            for (int i = 1; i < barrel.getContainerSize(); i++) {
                barrel.setItem(i, new ItemStack(Items.DIRT, 64));
            }
            barrel.setChanged();
            var busBe = level.getBlockEntity(busPos);
            if (!(busBe instanceof ExportBusBlockEntity bus)) { helper.fail("Export bus not found"); return; }
            bus.setFilter(new ItemStack(Items.STONE));
            for (int i = 0; i < 11; i++) bus.tick();
            if (barrel.getItem(0).getCount() != 64) { helper.fail("barrel should receive exactly one stone, got " + barrel.getItem(0)); return; }
            long remaining = core.getItemCount(ItemKey.of(new ItemStack(Items.STONE)));
            if (remaining != 9) { helper.fail("core should only lose one stone, got remaining=" + remaining); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void export_bus_stops_after_network_disconnect(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();
        var busPos = unitPos.east();
        var chestPos = busPos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos,
                MagicStorage.EXPORT_BUS.get().defaultBlockState().setValue(ExportBusBlock.FACING, net.minecraft.core.Direction.EAST),
                Block.UPDATE_ALL);
        level.setBlock(chestPos, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var coreBe = level.getBlockEntity(corePos);
            if (!(coreBe instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            var chestBe = level.getBlockEntity(chestPos);
            if (!(chestBe instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel)) { helper.fail("Barrel not found"); return; }
            var busBe = level.getBlockEntity(busPos);
            if (!(busBe instanceof ExportBusBlockEntity bus)) { helper.fail("Export bus not found"); return; }
            bus.setFilter(new ItemStack(Items.STONE));
            for (int i = 0; i < 11; i++) bus.tick();
            if (core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 0) { helper.fail("initial export should remove one stone"); return; }
            if (barrel.getItem(0).getCount() != 1) { helper.fail("initial export should place one stone in barrel, got " + barrel.getItem(0)); return; }
            level.removeBlock(unitPos, false);
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            for (int i = 0; i < 11; i++) bus.tick();
            if (core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 1) { helper.fail("disconnected export bus must not keep exporting via cached core"); return; }
            if (barrel.getItem(0).getCount() != 1) { helper.fail("disconnected export bus should not add another stone, got " + barrel.getItem(0)); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void crafting_menu_data_slot_parity_server_vs_buf_ctor(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var serverMenu = new CraftingTerminalMenu(34, player.getInventory(), core);
            var byteBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            byteBuf.writeBlockPos(corePos);
            byteBuf.writeBlockPos(corePos);
            byteBuf.writeBoolean(false);
            var bufMenu = new CraftingTerminalMenu(35, player.getInventory(), byteBuf);
            int serverCount = dataSlotCount(serverMenu);
            int bufCount = dataSlotCount(bufMenu);
            if (serverCount != bufCount) { helper.fail("crafting data-slot count mismatch: server=" + serverCount + " buf=" + bufCount); return; }
            if (serverCount < 12) { helper.fail("crafting menu should sync base 5 + crafting 7 data slots, got " + serverCount); return; }
            int metadataStart = StorageTerminalMenu.DISPLAY_SLOTS + 36;
            int metadataSlots = serverMenu.slots.size() - metadataStart;
            if (metadataSlots != 10) { helper.fail("crafting selection/missing metadata should be 10 hidden slots after player inventory, got " + metadataSlots); return; }
            for (int i = metadataStart; i < serverMenu.slots.size(); i++) {
                var slot = serverMenu.getSlot(i);
                if (slot.isActive()) { helper.fail("metadata slot " + i + " must be inactive"); return; }
                if (slot.mayPlace(new ItemStack(Items.STONE))) { helper.fail("metadata slot " + i + " must reject manual placement"); return; }
                if (slot.mayPickup(player)) { helper.fail("metadata slot " + i + " must reject pickup"); return; }
                if (slot.x != -9999 || slot.y != -9999) { helper.fail("metadata slot " + i + " should live offscreen, got " + slot.x + "," + slot.y); return; }
            }
            helper.succeed();
        });
    }

    private static int dataSlotCount(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        try {
            var f = net.minecraft.world.inventory.AbstractContainerMenu.class.getDeclaredField("dataSlots");
            f.setAccessible(true);
            return ((java.util.List<?>) f.get(menu)).size();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
