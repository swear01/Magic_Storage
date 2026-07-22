package com.swearprom.magicstorage.magic_storage;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

@GameTestHolder(MagicStorage.MODID)
@PrefixGameTestTemplate(false)
public final class TypedResourcePersistenceTests {
    private TypedResourcePersistenceTests() {
    }

    @GameTest(template = "behavioraltests.platform")
    public static void core_record_persists_resources_and_fails_closed_on_corruption(
            GameTestHelper helper
    ) {
        CoreStorageRecord original = CoreStorageRecord.fresh(UUID.randomUUID());
        StorageResourceKey fluid = StorageResourceBridge.fluidKey(
                new FluidStack(Fluids.WATER, 1), helper.getLevel().registryAccess());
        original.resourceLedger().insert(
                fluid, 4_000, StorageTypeCapacity.unlimitedCapacity(), Action.EXECUTE);
        original.resourceLedger().insert(
                StorageResourceBridge.ENERGY_KEY,
                9_000,
                StorageTypeCapacity.unlimitedCapacity(),
                Action.EXECUTE);

        CompoundTag saved = original.save(helper.getLevel().registryAccess());
        CoreStorageRecord.LoadResult loaded = CoreStorageRecord.load(
                saved, helper.getLevel().registryAccess());
        if (!loaded.success()
                || loaded.record().resourceLedger().amount(fluid) != 4_000
                || loaded.record().resourceLedger().amount(StorageResourceBridge.ENERGY_KEY) != 9_000) {
            helper.fail("Core record did not preserve fluid and power ledger entries");
            return;
        }

        CompoundTag previousSchema = saved.copy();
        previousSchema.remove(CoreStorageRecord.TAG_RESOURCE_LEDGER);
        CoreStorageRecord.LoadResult previousLoaded = CoreStorageRecord.load(
                previousSchema, helper.getLevel().registryAccess());
        if (!previousLoaded.success() || !previousLoaded.record().resourceLedger().isEmpty()) {
            helper.fail("Previous item-only Core record did not load with an empty typed ledger");
            return;
        }

        CompoundTag corrupt = saved.copy();
        CompoundTag ledger = corrupt.getCompound(CoreStorageRecord.TAG_RESOURCE_LEDGER);
        ledger.getList("entries", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", -1);
        CoreStorageRecord.LoadResult corruptLoaded = CoreStorageRecord.load(
                corrupt, helper.getLevel().registryAccess());
        if (corruptLoaded.success() || !corruptLoaded.raw().equals(corrupt)) {
            helper.fail("Corrupt typed ledger did not fail closed with raw record preservation");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void items_share_the_live_typed_ledger_and_mixed_transactions_are_atomic(
            GameTestHelper helper
    ) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            core.rebuildNetwork(level);
            ItemKey logs = ItemKey.of(new ItemStack(Items.OAK_LOG));
            ItemKey charcoal = ItemKey.of(new ItemStack(Items.CHARCOAL));
            StorageResourceKey logResource = StorageResourceBridge.itemKey(
                    logs, level.registryAccess());
            StorageResourceKey charcoalResource = StorageResourceBridge.itemKey(
                    charcoal, level.registryAccess());
            StorageResourceKey water = StorageResourceBridge.fluidKey(
                    new FluidStack(Fluids.WATER, 1), level.registryAccess());

            if (core.insertItemCount(logs, 2, Action.EXECUTE, Actor.EMPTY) != 2
                    || core.insertResource(water, 1_000, Action.EXECUTE) != 1_000
                    || core.getResourceAmount(logResource) != 2) {
                helper.fail("Existing item API did not write into the typed ledger");
                return;
            }

            Map<StorageResourceKey, Long> impossible = Map.of(
                    logResource, -2L,
                    water, -1_001L,
                    charcoalResource, 1L);
            if (core.applyResourceTransaction(impossible, Action.EXECUTE, Actor.EMPTY)
                    || core.getItemCount(logs) != 2
                    || core.getResourceAmount(water) != 1_000
                    || core.getItemCount(charcoal) != 0) {
                helper.fail("Failed mixed transaction partially mutated the Core");
                return;
            }

            Map<StorageResourceKey, Long> exact = Map.of(
                    logResource, -2L,
                    water, -1_000L,
                    charcoalResource, 1L);
            if (!core.applyResourceTransaction(exact, Action.EXECUTE, Actor.EMPTY)
                    || core.getItemCount(logs) != 0
                    || core.getResourceAmount(water) != 0
                    || core.getItemCount(charcoal) != 1
                    || core.getTypeCount() != 1) {
                helper.fail("Exact mixed transaction did not commit every resource together");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void exact_item_components_round_trip_through_the_universal_ledger(
            GameTestHelper helper
    ) {
        ItemStack namedDiamond = new ItemStack(Items.DIAMOND);
        namedDiamond.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Ledger Variant"));
        ItemKey itemKey = ItemKey.of(namedDiamond);
        StorageResourceKey resourceKey = StorageResourceBridge.itemKey(
                itemKey, helper.getLevel().registryAccess());
        CoreStorageRecord original = CoreStorageRecord.fresh(UUID.randomUUID());
        original.putItem(itemKey, 73, helper.getLevel().registryAccess());
        if (original.resourceLedger().amount(resourceKey) != 73) {
            helper.fail("Record item insertion did not use the universal ledger");
            return;
        }

        CompoundTag saved = original.save(helper.getLevel().registryAccess());
        CoreStorageRecord.LoadResult loaded = CoreStorageRecord.load(
                saved, helper.getLevel().registryAccess());
        if (!loaded.success()
                || loaded.record().resourceLedger().amount(resourceKey) != 73
                || loaded.record().getItemCount(itemKey, helper.getLevel().registryAccess()) != 73) {
            helper.fail("Exact item components did not round-trip through the universal ledger");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void terminal_lists_live_typed_resources_without_treating_them_as_items(
            GameTestHelper helper
    ) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            core.rebuildNetwork(level);
            StorageResourceKey water = StorageResourceBridge.fluidKey(
                    new FluidStack(Fluids.WATER, 1), level.registryAccess());
            StorageResourceKey oxygen = StorageResourceKey.of(
                    StorageResourceKindApi.CHEMICAL_KIND,
                    ResourceLocation.fromNamespaceAndPath("mekanism", "oxygen"),
                    new CompoundTag());
            if (core.insertItem(new ItemStack(Items.STONE)) != 1
                    || core.insertResource(water, 1_000, Action.EXECUTE) != 1_000
                    || core.insertResource(StorageResourceBridge.ENERGY_KEY, 2_000, Action.EXECUTE) != 2_000
                    || core.insertResource(oxygen, 300, Action.EXECUTE) != 300) {
                helper.fail("Could not seed terminal typed resources");
                return;
            }
            StorageResourceKey missingProvider = StorageResourceKey.of(
                    ResourceLocation.fromNamespaceAndPath("missing_provider", "mana"),
                    ResourceLocation.fromNamespaceAndPath("missing_provider", "blue"),
                    new CompoundTag());
            if (core.storageRecordForTesting().resourceLedger().insert(
                    missingProvider,
                    500,
                    StorageTypeCapacity.unlimitedCapacity(),
                    Action.EXECUTE) != 500) {
                helper.fail("Could not seed an unavailable provider resource");
                return;
            }
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(902, player.getInventory(), core);
            if (menu.getTotalItemTypes() != 1 || !menu.getSlot(0).getItem().is(Items.STONE)) {
                helper.fail("Default Item view did not isolate stored items");
                return;
            }
            menu.clickMenuButton(player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON);
            ItemStack displayedWater = menu.getSlot(0).getItem();
            if (menu.getResourceView() != TerminalResourceView.FLUID
                    || menu.getTotalItemTypes() != 1
                    || !TerminalResourceDisplay.key(displayedWater).orElseThrow().equals(water)) {
                helper.fail("Fluid view did not isolate the stored fluid");
                return;
            }
            menu.clickMenuButton(player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON);
            if (menu.getResourceView() != TerminalResourceView.ENERGY
                    || menu.getTotalItemTypes() != 1
                    || !TerminalResourceDisplay.key(menu.getSlot(0).getItem()).orElseThrow()
                    .equals(StorageResourceBridge.ENERGY_KEY)) {
                helper.fail("Energy view did not isolate stored power");
                return;
            }
            menu.clickMenuButton(player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON);
            if (menu.getResourceView() != TerminalResourceView.GAS
                    || menu.getTotalItemTypes() != 1
                    || !TerminalResourceDisplay.key(menu.getSlot(0).getItem()).orElseThrow().equals(oxygen)) {
                helper.fail("Gas view did not isolate stored chemicals");
                return;
            }
            menu.clickMenuButton(player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON);
            if (menu.getResourceView() != TerminalResourceView.OTHER
                    || menu.getTotalItemTypes() != 0) {
                helper.fail("Other view must omit unresolved resource providers");
                return;
            }

            menu.clickMenuButton(player, StorageTerminalMenu.RESET_RESOURCE_VIEW_BUTTON);
            menu.clickMenuButton(player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON);
            if (core.insertResource(water, 250, Action.EXECUTE) != 250) {
                helper.fail("Could not update displayed fluid amount");
                return;
            }
            menu.broadcastChanges();
            ItemStack updatedWater = menu.getSlot(0).getItem();
            if (TerminalDisplayStack.amount(updatedWater) != 1_250) {
                helper.fail("Open terminal did not refresh a typed resource mutation");
                return;
            }
            menu.clicked(0, 0, ClickType.PICKUP, player);
            if (!menu.getCarried().isEmpty() || core.getResourceAmount(water) != 1_250) {
                helper.fail("Typed display entry was incorrectly extracted as its icon item");
                return;
            }

            var craftingMenu = new CraftingTerminalMenu(903, player.getInventory(), core);
            craftingMenu.clickMenuButton(player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON);
            if (craftingMenu.getResourceView() != TerminalResourceView.FLUID
                    || craftingMenu.getTotalItemTypes() != 1) {
                helper.fail("Crafting Terminal Storage page did not share the fluid view");
                return;
            }
            craftingMenu.clicked(0, 0, ClickType.PICKUP, player);
            if (!craftingMenu.getSelectedStack().isEmpty()
                    || !craftingMenu.getCarried().isEmpty()) {
                helper.fail("Crafting Terminal treated a typed representative as an item recipe");
                return;
            }
            craftingMenu.clickMenuButton(player, CraftingTerminalMenu.CRAFTABLE_PAGE_BUTTON);
            if (craftingMenu.clickMenuButton(
                    player, StorageTerminalMenu.NEXT_RESOURCE_VIEW_BUTTON)) {
                helper.fail("Craftable page accepted a forged resource-view transition");
                return;
            }
            helper.succeed();
        });
    }
}
