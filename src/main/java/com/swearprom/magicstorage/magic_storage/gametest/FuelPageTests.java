package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MagicStorage.MODID)
@PrefixGameTestTemplate(false)
public class FuelPageTests {

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void fullscreen_fuel_machine_grid_uses_two_rows_for_current_descriptors(GameTestHelper helper) {
        int machineCount = MachineEnergyTable.entries().size();
        var geometry = TerminalLayout.forProfile(
                TerminalProfile.CRAFTING,
                423, 291, machineCount, CraftingTerminalMenu.fuelTargets().size());
        var cells = geometry.machineGrid().cells(0);
        long visibleRows = cells.stream().map(TerminalLayout.Rect::y).distinct().count();

        if (!geometry.wide() || geometry.machineGrid().pageCount() != 1
                || cells.size() != machineCount || visibleRows != 2) {
            helper.fail("Fullscreen Installed Stations must show every current descriptor in two rows: wide="
                    + geometry.wide() + ", descriptors=" + machineCount + ", cells=" + cells.size()
                    + ", rows=" + visibleRows + ", pages=" + geometry.machineGrid().pageCount());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void fullscreen_fuel_lower_panel_aligns_with_player_inventory(GameTestHelper helper) {
        var geometry = TerminalLayout.forProfile(
                TerminalProfile.CRAFTING,
                423, 291, MachineEnergyTable.entries().size(), CraftingTerminalMenu.fuelTargets().size());
        TerminalLayout.Rect playerInventory = geometry.playerInventory();
        TerminalLayout.Rect fuelPanel = geometry.fuelControlPanel();

        if (fuelPanel.y() != playerInventory.y()
                || fuelPanel.bottom() != playerInventory.bottom()
                || fuelPanel.x() < playerInventory.right()
                || fuelPanel.overlaps(playerInventory)) {
            helper.fail("Fullscreen Fuel controls must fill the inventory-aligned right column: inventory="
                    + playerInventory + ", fuel=" + fuelPanel);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void crafting_items_page_stores_fuel_without_conversion(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            player.getInventory().setItem(0, new ItemStack(Items.BLAZE_ROD, 2));
            var menu = new CraftingTerminalMenu(101, player.getInventory(), core);
            int slot = findPlayerMenuSlot(menu, Items.BLAZE_ROD);
            menu.quickMoveStack(player, slot);

            if (!menu.getSlot(slot).getItem().isEmpty()) {
                helper.fail("Items-page quick move should store the complete fuel stack");
                return;
            }
            if (core.getItemCount(ItemKey.of(new ItemStack(Items.BLAZE_ROD))) != 2) {
                helper.fail("Crafting Items page should store Blaze Rods as ordinary items");
                return;
            }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 0 || core.getEnergy(EnergyType.BLAZE_FUEL) != 0) {
                helper.fail("Items-page storage must not convert any fuel");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void items_page_rejects_forged_hidden_fuel_slot_click(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(109, player.getInventory(), core);
            menu.setCarried(new ItemStack(Items.COAL));
            menu.clicked(CraftingTerminalMenu.FUEL_INPUT_SLOT, 0, ClickType.PICKUP, player);

            if (!menu.getCarried().is(Items.COAL) || menu.getCarried().getCount() != 1) {
                helper.fail("Items page must preserve carried fuel after a forged hidden-slot click");
                return;
            }
            if (!menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).getItem().isEmpty()) {
                helper.fail("Items page must not accept fuel into its hidden dedicated slot");
                return;
            }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 0 || core.getTypeCount() != 0) {
                helper.fail("Forged hidden-slot click must not mutate energy or storage");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void legacy_popup_button_ids_are_noops(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            player.getInventory().setItem(0, new ItemStack(Items.COAL));
            var storageMenu = new StorageTerminalMenu(110, player.getInventory(), core);
            int storageSlot = findPlayerMenuSlot(storageMenu, Items.COAL);
            int oldStorageButton = 1000 + storageSlot * EnergyType.values().length
                    + EnergyType.FURNACE_FUEL.ordinal();
            storageMenu.clickMenuButton(player, oldStorageButton);

            if (!storageMenu.getSlot(storageSlot).getItem().is(Items.COAL)
                    || core.getEnergy(EnergyType.FURNACE_FUEL) != 0) {
                helper.fail("Storage terminal must ignore retired popup fuel button ids");
                return;
            }

            var craftingMenu = new CraftingTerminalMenu(111, player.getInventory(), core);
            int craftingSlot = findPlayerMenuSlot(craftingMenu, Items.COAL);
            int oldCraftingButton = 1000 + craftingSlot * EnergyType.values().length
                    + EnergyType.FURNACE_FUEL.ordinal();
            craftingMenu.clickMenuButton(player, oldCraftingButton);
            if (!craftingMenu.getSlot(craftingSlot).getItem().is(Items.COAL)
                    || core.getEnergy(EnergyType.FURNACE_FUEL) != 0) {
                helper.fail("Crafting terminal must ignore retired popup fuel button ids");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void retired_previous_next_target_button_ids_are_noops(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(123, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.BLAZE_FUEL));

            if (menu.clickMenuButton(player, 17)
                    || menu.getSelectedFuelTarget() != EnergyType.BLAZE_FUEL) {
                helper.fail("Retired previous-target button id must not mutate the explicit target");
                return;
            }
            if (menu.clickMenuButton(player, 18)
                    || menu.getSelectedFuelTarget() != EnergyType.BLAZE_FUEL) {
                helper.fail("Retired next-target button id must not mutate the explicit target");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void crafting_items_page_delegates_shared_view_controls(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(113, player.getInventory(), core);
            menu.clickMenuButton(player, 12);
            menu.clickMenuButton(player, 11);
            menu.clickMenuButton(player, 13);

            if (menu.getSortMode() != SortMode.QUANTITY
                    || menu.getSortOrder() != SortOrder.DESCENDING
                    || menu.getSearchMode() != SearchMode.TAG) {
                helper.fail("Crafting Items page must delegate sort, order, and search controls");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void auto_overflow_does_not_fallback_to_second_pool(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var energy = new CompoundTag();
            energy.putLong(EnergyType.BLAZE_FUEL.getId(), Long.MAX_VALUE);
            var tag = new CompoundTag();
            tag.put("energy", energy);
            core.loadAdditional(tag, helper.getLevel().registryAccess());
            core.insertItem(new ItemStack(Items.STONE));
            core.insertItem(new ItemStack(Items.DIRT));

            player.getInventory().setItem(0, new ItemStack(Items.BLAZE_ROD));
            var menu = new CraftingTerminalMenu(112, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int rodSlot = findPlayerMenuSlot(menu, Items.BLAZE_ROD);
            menu.quickMoveStack(player, rodSlot);

            if (!menu.getSlot(rodSlot).getItem().is(Items.BLAZE_ROD)) {
                helper.fail("Auto overflow must preserve the complete source stack");
                return;
            }
            if (core.getEnergy(EnergyType.BLAZE_FUEL) != Long.MAX_VALUE
                    || core.getEnergy(EnergyType.FURNACE_FUEL) != 0) {
                helper.fail("Auto overflow must not fallback from scarce Blaze to Furnace");
                return;
            }

            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            menu.quickMoveStack(player, rodSlot);
            if (!menu.getSlot(rodSlot).getItem().isEmpty()
                    || core.getEnergy(EnergyType.FURNACE_FUEL) != 2400) {
                helper.fail("Explicit Furnace target should convert the preserved Blaze Rod");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void fuel_page_slot_converts_oak_logs_using_runtime_burn_time_per_item(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack oakLogs = new ItemStack(Items.OAK_LOG, 3);
            int burnTime = oakLogs.getBurnTime(null);
            if (burnTime <= 0) {
                helper.fail("Test setup requires Oak Logs to have a positive runtime burn time");
                return;
            }

            var menu = new CraftingTerminalMenu(120, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            var input = menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT);
            if (!input.mayPlace(oakLogs)) {
                helper.fail("Fuel-page input must accept Oak Logs from runtime burn time; hardcoded whitelist rejected them");
                return;
            }

            input.set(oakLogs.copy());
            long expected = Math.multiplyExact((long) burnTime, oakLogs.getCount());
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != expected) {
                helper.fail("Three Oak Logs must add runtime burn time per item: expected "
                        + expected + " got " + core.getEnergy(EnergyType.FURNACE_FUEL));
                return;
            }
            if (!input.getItem().isEmpty()) {
                helper.fail("Successful Fuel-page conversion must consume the complete Oak Log stack");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void fuel_page_shift_click_converts_oak_logs_using_runtime_burn_time(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack oakLogs = new ItemStack(Items.OAK_LOG, 2);
            int burnTime = oakLogs.getBurnTime(null);
            if (burnTime <= 0) {
                helper.fail("Test setup requires Oak Logs to have a positive runtime burn time");
                return;
            }

            player.getInventory().setItem(0, oakLogs.copy());
            var menu = new CraftingTerminalMenu(121, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            int playerSlot = findPlayerMenuSlot(menu, Items.OAK_LOG);
            ItemStack moved = menu.quickMoveStack(player, playerSlot);

            if (moved.isEmpty()) {
                helper.fail("Fuel-page Shift-click must convert Oak Logs; hardcoded whitelist rejected them");
                return;
            }
            if (!menu.getSlot(playerSlot).getItem().isEmpty()) {
                helper.fail("Fuel-page Shift-click must consume the complete Oak Log source stack");
                return;
            }
            long expected = Math.multiplyExact((long) burnTime, oakLogs.getCount());
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != expected) {
                helper.fail("Shift-clicked Oak Logs must add runtime burn time per item: expected "
                        + expected + " got " + core.getEnergy(EnergyType.FURNACE_FUEL));
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void fuel_page_rejects_zero_runtime_burn_time_item(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack stone = new ItemStack(Items.STONE, 2);
            if (stone.getBurnTime(null) != 0) {
                helper.fail("Test setup requires Stone to have zero runtime burn time");
                return;
            }

            var menu = new CraftingTerminalMenu(122, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            var input = menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT);
            if (input.mayPlace(stone)) {
                helper.fail("Fuel-page input must reject a zero-burn-time item");
                return;
            }

            menu.setCarried(stone.copy());
            menu.clicked(CraftingTerminalMenu.FUEL_INPUT_SLOT, 0, ClickType.PICKUP, player);
            if (!menu.getCarried().is(Items.STONE) || menu.getCarried().getCount() != 2
                    || !input.getItem().isEmpty()) {
                helper.fail("Rejected non-fuel must remain carried and leave the Fuel input empty");
                return;
            }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 0) {
                helper.fail("Rejected non-fuel must not mutate Fuel energy");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void core_runtime_fuel_overflow_preserves_stack_and_energy(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack oakLog = new ItemStack(Items.OAK_LOG);
            int burnTime = oakLog.getBurnTime(null);
            if (burnTime <= 0) {
                helper.fail("Test setup requires Oak Logs to have a positive runtime burn time");
                return;
            }
            if (!core.addFuel(oakLog, EnergyType.FURNACE_FUEL)) {
                helper.fail("Core runtime Fuel commit must accept Oak Logs before overflow validation; hardcoded whitelist rejected them");
                return;
            }
            if (!oakLog.isEmpty() || core.getEnergy(EnergyType.FURNACE_FUEL) != burnTime) {
                helper.fail("Core runtime Fuel precondition did not add exactly one Oak Log burn time");
                return;
            }

            ItemStack overflowStack = new ItemStack(Items.OAK_LOG, 2);
            long stackValue = Math.multiplyExact((long) burnTime, overflowStack.getCount());
            long originalEnergy = Long.MAX_VALUE - stackValue + 1;
            var energy = new CompoundTag();
            energy.putLong(EnergyType.FURNACE_FUEL.getId(), originalEnergy);
            var tag = new CompoundTag();
            tag.put("energy", energy);
            core.loadAdditional(tag, helper.getLevel().registryAccess());

            if (core.addFuel(overflowStack, EnergyType.FURNACE_FUEL)) {
                helper.fail("Runtime Fuel addition must fail closed when the exact stack value overflows the pool");
                return;
            }
            if (!overflowStack.is(Items.OAK_LOG) || overflowStack.getCount() != 2) {
                helper.fail("Overflow rejection must preserve the complete runtime Fuel stack");
                return;
            }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != originalEnergy) {
                helper.fail("Overflow rejection must preserve the original Fuel energy");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void dedicated_input_converts_and_keeps_stacked_container_remainders(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(102, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));

            ItemStack lavaBuckets = new ItemStack(Items.LAVA_BUCKET);
            lavaBuckets.set(DataComponents.MAX_STACK_SIZE, 3);
            lavaBuckets.setCount(3);
            menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).set(lavaBuckets);

            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 60000) {
                helper.fail("Dedicated input should convert all three Lava Buckets");
                return;
            }
            ItemStack remainder = menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).getItem();
            if (!remainder.is(Items.BUCKET) || remainder.getCount() != 3) {
                helper.fail("Dedicated input should keep three empty bucket remainders, got " + remainder);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void dedicated_input_rejects_nonfuel_incompatible_target_and_overflow(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(103, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            var input = menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT);
            if (input.mayPlace(new ItemStack(Items.STONE))) {
                helper.fail("Auto fuel input must reject non-fuel items");
                return;
            }

            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.BLAZE_FUEL));
            if (input.mayPlace(new ItemStack(Items.COAL))) {
                helper.fail("Blaze target must reject Furnace-only fuel");
                return;
            }

            var energy = new CompoundTag();
            energy.putLong(EnergyType.FURNACE_FUEL.getId(), Long.MAX_VALUE);
            var tag = new CompoundTag();
            tag.put("energy", energy);
            core.loadAdditional(tag, helper.getLevel().registryAccess());
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            input.set(new ItemStack(Items.COAL));
            if (!input.getItem().is(Items.COAL) || input.getItem().getCount() != 1) {
                helper.fail("Overflow failure must preserve fuel in the dedicated input");
                return;
            }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != Long.MAX_VALUE) {
                helper.fail("Overflow failure must not wrap or mutate the full pool");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void menu_close_returns_leftover_fuel_input_to_player(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var energy = new CompoundTag();
            energy.putLong(EnergyType.FURNACE_FUEL.getId(), Long.MAX_VALUE);
            var tag = new CompoundTag();
            tag.put("energy", energy);
            core.loadAdditional(tag, helper.getLevel().registryAccess());

            var menu = new CraftingTerminalMenu(104, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).set(new ItemStack(Items.COAL, 2));
            menu.removed(player);

            if (!menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).getItem().isEmpty()) {
                helper.fail("Closing menu must clear the dedicated fuel input");
                return;
            }
            if (player.getInventory().countItem(Items.COAL) != 2) {
                helper.fail("Closing menu must return rejected fuel to the player");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void leaving_fuel_page_returns_unconverted_input_before_hiding_slot(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var energy = new CompoundTag();
            energy.putLong(EnergyType.FURNACE_FUEL.getId(), Long.MAX_VALUE);
            var tag = new CompoundTag();
            tag.put("energy", energy);
            core.loadAdditional(tag, helper.getLevel().registryAccess());

            var menu = new CraftingTerminalMenu(108, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            menu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.FURNACE_FUEL));
            menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).set(new ItemStack(Items.COAL, 2));
            menu.clickMenuButton(player, CraftingTerminalMenu.STORAGE_PAGE_BUTTON);

            if (menu.getPage() != CraftingTerminalPage.STORAGE) {
                helper.fail("Storage page button should complete after returning fuel input");
                return;
            }
            if (!menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).getItem().isEmpty()) {
                helper.fail("Leaving Fuel page must not hide occupied input");
                return;
            }
            if (player.getInventory().countItem(Items.COAL) != 2) {
                helper.fail("Leaving Fuel page must return rejected fuel to player");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void page_target_and_all_long_energy_values_sync_to_buf_menu(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var energy = new CompoundTag();
            long[] expectedAmounts = {
                    0L,
                    0xFFFFL,
                    0x1_0000L,
                    0xFFFF_FFFFL,
                    0x1_0000_0000L,
                    0x1234_8000_FFFE_FFFFL,
                    Long.MAX_VALUE - 1,
                    Long.MAX_VALUE
            };
            for (EnergyType type : EnergyType.values()) {
                energy.putLong(type.getId(), expectedAmounts[type.ordinal()]);
            }
            var tag = new CompoundTag();
            tag.put("energy", energy);
            tag.putLong("axeEnergy", Long.MAX_VALUE - 2);
            core.loadAdditional(tag, helper.getLevel().registryAccess());
            long stoneInserted = core.insertItem(new ItemStack(Items.STONE));
            long dirtInserted = core.insertItem(new ItemStack(Items.DIRT));
            if (stoneInserted != 1 || dirtInserted != 1 || core.getTypeCount() != 2) {
                helper.fail("Fuel type-capacity fixture could not store two item types");
                return;
            }

            var serverMenu = new CraftingTerminalMenu(105, player.getInventory(), core);
            serverMenu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            serverMenu.clickMenuButton(player, CraftingTerminalMenu.fuelTargetButtonId(EnergyType.BOTTLE_FUEL));

            var byteBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            byteBuf.writeBlockPos(core.getBlockPos());
            byteBuf.writeBlockPos(core.getBlockPos());
            byteBuf.writeBoolean(false);
            var clientMenu = new CraftingTerminalMenu(106, player.getInventory(), byteBuf);

            var serverData = dataSlots(serverMenu);
            var clientData = dataSlots(clientMenu);
            if (serverData.size() != 100 || clientData.size() != 100) {
                helper.fail("Crafting fuel/resource/output/Axe Energy sync requires exact 100-slot parity, server="
                        + serverData.size() + " client=" + clientData.size());
                return;
            }
            for (int i = 0; i < serverData.size(); i++) {
                var wire = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                var packet = new net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket(
                        serverMenu.containerId, i, serverData.get(i).get());
                net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket.STREAM_CODEC.encode(wire, packet);
                var decoded = net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket.STREAM_CODEC.decode(wire);
                if (decoded.getContainerId() != serverMenu.containerId || decoded.getId() != i) {
                    helper.fail("Container data packet identity changed at slot " + i);
                    return;
                }
                clientData.get(decoded.getId()).set(decoded.getValue());
            }
            if (clientMenu.getPage() != CraftingTerminalPage.FUEL
                    || clientMenu.getSelectedFuelTarget() != EnergyType.BOTTLE_FUEL) {
                helper.fail("Client menu did not receive page and selected target");
                return;
            }
            if (clientMenu.getTypeCount() != 2
                    || clientMenu.getMaxTypes() != core.getTotalTypeSlots()) {
                helper.fail("Fuel page did not retain base storage type capacity: types="
                        + clientMenu.getTypeCount() + " max=" + clientMenu.getMaxTypes());
                return;
            }
            for (EnergyType type : EnergyType.values()) {
                long expected = expectedAmounts[type.ordinal()];
                if (clientMenu.getEnergyAmount(type) != expected) {
                    helper.fail(type.getId() + " long sync mismatch: expected " + expected
                            + " got " + clientMenu.getEnergyAmount(type));
                    return;
                }
            }
            if (clientMenu.getAxeEnergyAmount() != Long.MAX_VALUE - 2
                    || clientMenu.hasInfiniteAxeEnergy()) {
                helper.fail("Finite Axe Energy long sync mismatch");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void recipe_resource_longs_survive_signed_short_wire(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            long available = 0x1234_8000_FFFE_FFFFL;
            var root = new CompoundTag();
            var inventory = new net.minecraft.nbt.ListTag();
            var cobble = new CompoundTag();
            cobble.put("item", new ItemStack(Items.COBBLESTONE).save(helper.getLevel().registryAccess()));
            cobble.putLong("count", available);
            inventory.add(cobble);
            var stone = new CompoundTag();
            stone.put("item", new ItemStack(Items.STONE).save(helper.getLevel().registryAccess()));
            stone.putLong("count", 1);
            inventory.add(stone);
            root.put("inventory", inventory);
            var energy = new CompoundTag();
            energy.putLong(EnergyType.SMELTING_ENERGY.getId(), available);
            energy.putLong(EnergyType.FURNACE_FUEL.getId(), available);
            root.put("energy", energy);
            core.loadAdditional(root, helper.getLevel().registryAccess());
            core.getMachineContainer().setItem(
                    MachineEnergyTable.FURNACE_SLOT, new ItemStack(Items.FURNACE));

            var serverMenu = new CraftingTerminalMenu(120, player.getInventory(), core);
            int stoneSlot = -1;
            for (int slot = 0; slot < StorageTerminalMenu.DISPLAY_SLOTS; slot++) {
                if (serverMenu.getSlot(slot).getItem().is(Items.STONE)) {
                    stoneSlot = slot;
                    break;
                }
            }
            var smelting = helper.getLevel().getRecipeManager()
                    .getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING).stream()
                    .filter(holder -> holder.value().getResultItem(helper.getLevel().registryAccess()).is(Items.STONE))
                    .filter(holder -> holder.value().getIngredients().stream()
                            .anyMatch(ingredient -> ingredient.test(new ItemStack(Items.COBBLESTONE))))
                    .findFirst().orElse(null);
            if (stoneSlot < 0 || smelting == null
                    || !serverMenu.selectRecipe(helper.getLevel(), stoneSlot, smelting.id(), player)) {
                helper.fail("Could not select the Stone smelting recipe for resource wire test");
                return;
            }

            var byteBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            byteBuf.writeBlockPos(core.getBlockPos());
            byteBuf.writeBlockPos(core.getBlockPos());
            byteBuf.writeBoolean(false);
            var clientMenu = new CraftingTerminalMenu(121, player.getInventory(), byteBuf);
            var serverData = dataSlots(serverMenu);
            var clientData = dataSlots(clientMenu);
            for (int i = 0; i < serverData.size(); i++) {
                var wire = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                var packet = new net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket(
                        serverMenu.containerId, i, serverData.get(i).get());
                net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket.STREAM_CODEC.encode(wire, packet);
                var decoded = net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket.STREAM_CODEC.decode(wire);
                clientData.get(decoded.getId()).set(decoded.getValue());
            }
            int metadataStart = CraftingTerminalMenu.MACHINE_SLOT_START + CraftingTerminalMenu.MACHINE_SLOT_COUNT;
            for (int slot = metadataStart; slot < serverMenu.slots.size(); slot++) {
                clientMenu.getSlot(slot).set(serverMenu.getSlot(slot).getItem().copy());
            }

            var ingredients = clientMenu.getIngredientPreview();
            var energies = clientMenu.getEnergyPreview();
            if (ingredients.size() != 1
                    || !ingredients.getFirst().stack().is(Items.COBBLESTONE)
                    || ingredients.getFirst().available() != available
                    || ingredients.getFirst().required() != 1) {
                helper.fail("Ingredient long wire mismatch: " + ingredients);
                return;
            }
            if (energies.size() != 2
                    || energies.get(0).type() != EnergyType.SMELTING_ENERGY
                    || energies.get(0).available() != available
                    || energies.get(0).required() != 200
                    || energies.get(1).type() != EnergyType.FURNACE_FUEL
                    || energies.get(1).available() != available
                    || energies.get(1).required() != 200) {
                helper.fail("Energy resource wire mismatch: " + energies);
                return;
            }
            var presentation = clientMenu.getRecipePresentation();
            if (!presentation.recipeId().equals(smelting.id())
                    || presentation.kind() != RecipePresentationKind.COOKING
                    || presentation.inputs().stream()
                    .noneMatch(stack -> stack.is(Items.COBBLESTONE))
                    || !presentation.output().is(Items.STONE)
                    || !presentation.station().is(Items.FURNACE)
                    || presentation.resources().size() != 3) {
                helper.fail("Exact recipe presentation did not survive menu wire sync: " + presentation);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void open_fuel_page_tracks_live_energy_listener_updates(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(107, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            ItemStack coal = new ItemStack(Items.COAL, 2);
            if (!core.addFuel(coal, EnergyType.FURNACE_FUEL)) {
                helper.fail("Test setup could not add coal fuel");
                return;
            }
            menu.broadcastChanges();
            if (menu.getEnergyAmount(EnergyType.FURNACE_FUEL) != 3200) {
                helper.fail("Open Fuel page should track listener-driven pool updates");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void open_fuel_page_tracks_live_type_count_and_capacity(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(120, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int initialCapacity = core.getTotalTypeSlots();

            if (core.insertItem(new ItemStack(Items.STONE)) != 1) {
                helper.fail("Test setup could not insert a stored type");
                return;
            }
            menu.broadcastChanges();
            if (menu.getTypeCount() != 1) {
                helper.fail("Open Fuel page should track live stored type count");
                return;
            }

            helper.getLevel().setBlock(core.getBlockPos().west(),
                    MagicStorage.STORAGE_UNIT_T2.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(helper.getLevel());
            menu.broadcastChanges();
            if (core.getTotalTypeSlots() <= initialCapacity
                    || menu.getMaxTypes() != core.getTotalTypeSlots()) {
                helper.fail("Open Fuel page should track live total type capacity: menu="
                        + menu.getMaxTypes() + " core=" + core.getTotalTypeSlots());
                return;
            }

            core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), 1, Action.EXECUTE, Actor.EMPTY);
            menu.broadcastChanges();
            if (menu.getTypeCount() != 0) {
                helper.fail("Open Fuel page should track removal of the final stored type");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void fuel_page_shift_move_installs_and_returns_machine_stack(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            player.getInventory().setItem(0, new ItemStack(Items.FURNACE, 3));
            var menu = new CraftingTerminalMenu(114, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int playerSlot = findPlayerMenuSlot(menu, Items.FURNACE);
            int furnaceMachineSlot = CraftingTerminalMenu.FUEL_INPUT_SLOT + 1;
            menu.quickMoveStack(player, playerSlot);

            ItemStack installed = menu.getSlot(furnaceMachineSlot).getItem();
            if (!installed.is(Items.FURNACE) || installed.getCount() != 3) {
                helper.fail("Fuel-page shift move must install all three Furnaces, got " + installed);
                return;
            }
            if (!menu.getSlot(playerSlot).getItem().isEmpty()) {
                helper.fail("Installing machines must consume the source player stack");
                return;
            }

            long before = core.getEnergy(EnergyType.SMELTING_ENERGY);
            core.tick();
            if (core.getEnergy(EnergyType.SMELTING_ENERGY) - before != 3) {
                helper.fail("Three installed Furnaces must generate exactly +3 in one tick");
                return;
            }

            menu.quickMoveStack(player, furnaceMachineSlot);
            if (!menu.getSlot(furnaceMachineSlot).getItem().isEmpty()
                    || player.getInventory().countItem(Items.FURNACE) != 3) {
                helper.fail("Shift-moving an installed machine must return the complete stack to the player");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void instant_station_accepts_one_and_rejects_second_without_consuming(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            player.getInventory().setItem(0, new ItemStack(Items.CRAFTING_TABLE, 2));
            var menu = new CraftingTerminalMenu(122, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int playerSlot = findPlayerMenuSlot(menu, Items.CRAFTING_TABLE);
            int stationSlot = CraftingTerminalMenu.MACHINE_SLOT_START
                    + MachineEnergyTable.CRAFTING_TABLE_SLOT;

            menu.quickMoveStack(player, playerSlot);
            ItemStack installed = menu.getSlot(stationSlot).getItem();
            ItemStack rejected = menu.getSlot(playerSlot).getItem();
            if (!installed.is(Items.CRAFTING_TABLE) || installed.getCount() != 1) {
                helper.fail("Instant station must install exactly one Crafting Table: " + installed);
                return;
            }
            if (!rejected.is(Items.CRAFTING_TABLE) || rejected.getCount() != 1) {
                helper.fail("Second instant station must remain in the source slot: " + rejected);
                return;
            }

            menu.quickMoveStack(player, playerSlot);
            if (menu.getSlot(stationSlot).getItem().getCount() != 1
                    || menu.getSlot(playerSlot).getItem().getCount() != 1) {
                helper.fail("A full instant-station slot must reject a second insertion without consuming it");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void axes_convert_to_exact_finite_energy_and_never_remain_installed(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack axe = new ItemStack(Items.IRON_AXE);
            axe.setDamageValue(axe.getMaxDamage() - 5);
            axe.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.UNBREAKING), 2);
            player.getInventory().setItem(0, axe);
            var menu = new CraftingTerminalMenu(123, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int playerSlot = findPlayerMenuSlot(menu, Items.IRON_AXE);
            int axeSlot = CraftingTerminalMenu.MACHINE_SLOT_START + MachineEnergyTable.AXE_SLOT;

            menu.quickMoveStack(player, playerSlot);
            if (core.getAxeEnergy() != 15 || core.hasInfiniteAxeEnergy()) {
                helper.fail("Five remaining durability with Unbreaking II must become exactly 15 Axe Energy");
                return;
            }
            if (!menu.getSlot(playerSlot).getItem().isEmpty()
                    || !menu.getSlot(axeSlot).getItem().isEmpty()
                    || !core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT).isEmpty()) {
                helper.fail("An accepted axe must be consumed instead of becoming installed equipment");
                return;
            }

            ItemStack mendingOnly = new ItemStack(Items.IRON_AXE);
            mendingOnly.setDamageValue(mendingOnly.getMaxDamage() - 4);
            mendingOnly.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.MENDING), 1);
            menu.setCarried(mendingOnly);
            menu.clicked(axeSlot, 0, ClickType.PICKUP, player);
            if (core.getAxeEnergy() != 19 || !menu.getCarried().isEmpty()
                    || !menu.getSlot(axeSlot).getItem().isEmpty()) {
                helper.fail("Mending and unrelated enchantments must not multiply Axe Energy: "
                        + core.getAxeEnergy());
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void finite_axe_energy_accumulates_and_checked_overflow_preserves_input(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack first = new ItemStack(Items.WOODEN_AXE);
            first.setDamageValue(first.getMaxDamage() - 3);
            ItemStack second = new ItemStack(Items.STONE_AXE);
            second.setDamageValue(second.getMaxDamage() - 7);
            if (!core.addAxeEnergy(first) || !core.addAxeEnergy(second)
                    || core.getAxeEnergy() != 10 || !first.isEmpty() || !second.isEmpty()) {
                helper.fail("Multiple finite axes must accumulate atomically to ten Axe Energy");
                return;
            }

            var persisted = new CompoundTag();
            persisted.putLong("axeEnergy", Long.MAX_VALUE - 1);
            core.loadAdditional(persisted, helper.getLevel().registryAccess());
            ItemStack rejected = new ItemStack(Items.IRON_AXE);
            rejected.setDamageValue(rejected.getMaxDamage() - 2);
            if (core.addAxeEnergy(rejected)) {
                helper.fail("Checked Axe Energy overflow must reject the complete input");
                return;
            }
            if (core.getAxeEnergy() != Long.MAX_VALUE - 1 || rejected.getCount() != 1) {
                helper.fail("Overflow rejection must preserve both stored energy and input axe");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void overflowed_legacy_axe_remains_retrievable_from_consumable_slot(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack legacyAxe = new ItemStack(Items.IRON_AXE);
            legacyAxe.setDamageValue(legacyAxe.getMaxDamage() - 2);
            var machineItems = new ListTag();
            var axeEntry = new CompoundTag();
            axeEntry.putByte("Slot", (byte) MachineEnergyTable.AXE_SLOT);
            machineItems.add(legacyAxe.save(helper.getLevel().registryAccess(), axeEntry));
            var machines = new CompoundTag();
            machines.put("Items", machineItems);
            var persisted = new CompoundTag();
            persisted.putLong("axeEnergy", Long.MAX_VALUE - 1);
            persisted.put("machines", machines);
            core.loadAdditional(persisted, helper.getLevel().registryAccess());

            var menu = new CraftingTerminalMenu(124, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int axeSlot = CraftingTerminalMenu.MACHINE_SLOT_START + MachineEnergyTable.AXE_SLOT;
            ItemStack visible = menu.getSlot(axeSlot).getItem();
            if (visible.getCount() != 1 || !ItemStack.isSameItemSameComponents(visible, legacyAxe)) {
                helper.fail("Failed legacy migration must expose the complete axe for recovery: " + visible);
                return;
            }

            menu.quickMoveStack(player, axeSlot);
            if (!core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT).isEmpty()
                    || player.getInventory().countItem(Items.IRON_AXE) != 1) {
                helper.fail("Recovered legacy axe must move from Core storage to the player inventory");
                return;
            }
            if (core.getAxeEnergy() != Long.MAX_VALUE - 1) {
                helper.fail("Recovering a failed legacy migration must not change stored Axe Energy");
                return;
            }

            menu.removed(player);
            player.getInventory().clearContent();
            core.loadAdditional(persisted, helper.getLevel().registryAccess());
            var pickupMenu = new CraftingTerminalMenu(125, player.getInventory(), core);
            pickupMenu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            pickupMenu.clicked(axeSlot, 0, ClickType.PICKUP, player);
            ItemStack carried = pickupMenu.getCarried();
            if (carried.getCount() != 1 || !ItemStack.isSameItemSameComponents(carried, legacyAxe)
                    || !core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT).isEmpty()) {
                helper.fail("Normal pickup must recover the complete failed legacy axe: " + carried);
                return;
            }
            if (core.getAxeEnergy() != Long.MAX_VALUE - 1) {
                helper.fail("Normal legacy recovery must preserve stored Axe Energy");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void unbreakable_axe_sets_infinite_and_rejects_further_axes(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            ItemStack unbreakable = new ItemStack(Items.DIAMOND_AXE);
            unbreakable.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
            if (!core.addAxeEnergy(unbreakable) || !unbreakable.isEmpty()
                    || !core.hasInfiniteAxeEnergy()) {
                helper.fail("Unbreakable axe must be consumed into explicit infinite Axe Energy");
                return;
            }

            ItemStack rejected = new ItemStack(Items.IRON_AXE);
            if (core.addAxeEnergy(rejected) || rejected.getCount() != 1) {
                helper.fail("Further axes must be rejected without consumption after Axe Energy is infinite");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void machine_slots_enforce_page_exact_mapping_and_forged_click_guard(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            var menu = new CraftingTerminalMenu(115, player.getInventory(), core);
            int start = CraftingTerminalMenu.FUEL_INPUT_SLOT + 1;
            ItemStack[] expected = {
                    new ItemStack(Items.FURNACE),
                    new ItemStack(Items.BLAST_FURNACE),
                    new ItemStack(Items.SMOKER),
                    new ItemStack(Items.CAMPFIRE),
                    new ItemStack(Items.BREWING_STAND),
                    new ItemStack(Items.CRAFTING_TABLE),
                    new ItemStack(Items.STONECUTTER),
                    new ItemStack(Items.SMITHING_TABLE),
                    new ItemStack(Items.IRON_AXE)
            };

            if (CraftingTerminalMenu.MACHINE_SLOT_COUNT != expected.length) {
                helper.fail("Installed station registry must expose all nine machine/tool slots");
                return;
            }

            for (int i = 0; i < expected.length; i++) {
                if (menu.getSlot(start + i).isActive() || menu.getSlot(start + i).mayPlace(expected[i])) {
                    helper.fail("Machine slot " + i + " must be hidden and reject placement on Items page");
                    return;
                }
            }
            menu.setCarried(new ItemStack(Items.FURNACE));
            menu.clicked(start, 0, ClickType.PICKUP, player);
            if (!menu.getCarried().is(Items.FURNACE) || !menu.getSlot(start).getItem().isEmpty()) {
                helper.fail("Forged Items-page machine-slot click must be a no-op");
                return;
            }
            menu.setCarried(ItemStack.EMPTY);

            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            for (int i = 0; i < expected.length; i++) {
                var slot = menu.getSlot(start + i);
                if (!slot.isActive() || !slot.mayPlace(expected[i])) {
                    helper.fail("Fuel machine slot " + i + " must accept its exact machine");
                    return;
                }
                ItemStack wrong = expected[(i + 1) % expected.length];
                if (slot.mayPlace(wrong) || slot.mayPlace(new ItemStack(Items.STONE))) {
                    helper.fail("Fuel machine slot " + i + " accepted a wrong machine or ordinary item");
                    return;
                }
            }
            if (!menu.getSlot(start + 8).mayPlace(new ItemStack(Items.DIAMOND_AXE))
                    || menu.getSlot(start + 8).mayPlace(new ItemStack(Items.DIAMOND_PICKAXE))) {
                helper.fail("Tool slot must accept mod-compatible axes but reject unrelated tools");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void installed_machine_survives_page_exit_close_and_remote_reopen(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            player.getInventory().setItem(0, new ItemStack(Items.BREWING_STAND, 2));
            var localMenu = new CraftingTerminalMenu(116, player.getInventory(), core);
            localMenu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            int playerSlot = findPlayerMenuSlot(localMenu, Items.BREWING_STAND);
            int brewingMachineSlot = CraftingTerminalMenu.FUEL_INPUT_SLOT + 5;
            localMenu.quickMoveStack(player, playerSlot);
            localMenu.clickMenuButton(player, CraftingTerminalMenu.STORAGE_PAGE_BUTTON);
            if (!localMenu.getSlot(brewingMachineSlot).getItem().is(Items.BREWING_STAND)
                    || localMenu.getSlot(brewingMachineSlot).getItem().getCount() != 2) {
                helper.fail("Leaving Fuel page must keep installed Brewing Stands in the Core");
                return;
            }
            localMenu.removed(player);

            var remotePlayer = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var remoteMenu = new CraftingTerminalMenu(117, remotePlayer.getInventory(), core, core.getBlockPos(), true);
            remoteMenu.clickMenuButton(remotePlayer, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            ItemStack reopened = remoteMenu.getSlot(brewingMachineSlot).getItem();
            if (!reopened.is(Items.BREWING_STAND) || reopened.getCount() != 2) {
                helper.fail("Remote reopen must see the same Core-owned installed machines, got " + reopened);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void items_page_stores_machine_as_ordinary_network_item(GameTestHelper helper) {
        withCore(helper, (core, player) -> {
            player.getInventory().setItem(0, new ItemStack(Items.SMOKER, 2));
            var menu = new CraftingTerminalMenu(118, player.getInventory(), core);
            int playerSlot = findPlayerMenuSlot(menu, Items.SMOKER);
            menu.quickMoveStack(player, playerSlot);

            if (core.getItemCount(ItemKey.of(new ItemStack(Items.SMOKER))) != 2) {
                helper.fail("Items page must store Smoker blocks as ordinary network items");
                return;
            }
            core.tick();
            if (core.getEnergy(EnergyType.SMOKING_ENERGY) != 0) {
                helper.fail("Machines stored in the ordinary network must not generate machine energy");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "terminalflowtests.platform", batch = "fuel_page")
    public static void conflicted_core_pauses_generation_rejects_install_and_allows_retrieval(GameTestHelper helper) {
        var level = helper.getLevel();
        var firstPos = helper.absolutePos(new BlockPos(1, 3, 1));
        var secondPos = firstPos.east();
        level.setBlock(firstPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(secondPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(firstPos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("First Core not found");
                return;
            }
            core.rebuildNetwork(level);
            core.getMachineContainer().setItem(0, new ItemStack(Items.FURNACE, 2));
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(119, player.getInventory(), core);
            menu.clickMenuButton(player, CraftingTerminalMenu.FUEL_PAGE_BUTTON);
            var machineSlot = menu.getSlot(CraftingTerminalMenu.MACHINE_SLOT_START);
            if (machineSlot.mayPlace(new ItemStack(Items.FURNACE))) {
                helper.fail("Conflicted Core must reject new machine installation");
                return;
            }
            long before = core.getEnergy(EnergyType.SMELTING_ENERGY);
            core.tick();
            if (core.getEnergy(EnergyType.SMELTING_ENERGY) != before) {
                helper.fail("Conflicted Core must pause installed-machine generation");
                return;
            }

            menu.quickMoveStack(player, CraftingTerminalMenu.MACHINE_SLOT_START);
            if (!machineSlot.getItem().isEmpty() || player.getInventory().countItem(Items.FURNACE) != 2) {
                helper.fail("Conflict must not trap already-installed machines");
                return;
            }
            helper.succeed();
        });
    }

    private static void withCore(GameTestHelper helper, FuelPageBody body) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            core.rebuildNetwork(level);
            body.run(core, helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL));
        });
    }

    private static int findPlayerMenuSlot(StorageTerminalMenu menu, net.minecraft.world.item.Item item) {
        int end = StorageTerminalMenu.DISPLAY_SLOTS + StorageTerminalMenu.PLAYER_INVENTORY_SLOTS;
        for (int i = StorageTerminalMenu.DISPLAY_SLOTS; i < end; i++) {
            if (menu.getSlot(i).getItem().is(item)) return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<net.minecraft.world.inventory.DataSlot> dataSlots(
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        try {
            var field = net.minecraft.world.inventory.AbstractContainerMenu.class.getDeclaredField("dataSlots");
            field.setAccessible(true);
            return (java.util.List<net.minecraft.world.inventory.DataSlot>) field.get(menu);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface FuelPageBody {
        void run(StorageCoreBlockEntity core, net.minecraft.world.entity.player.Player player);
    }
}
