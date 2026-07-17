package com.swearprom.magicstorage.magic_storage;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(MagicStorage.MODID)
@PrefixGameTestTemplate(false)
public final class CreativeStorageUnitTests {

    @GameTest(template = "registrationtests.empty")
    public static void unlimited_capacity_is_explicit_and_not_a_finite_sentinel(GameTestHelper helper) {
        StorageTypeCapacity capacity = StorageTypeCapacity.unlimitedCapacity();

        if (!capacity.unlimited() || capacity.finiteTypeSlots() != 0) {
            helper.fail("Unlimited capacity must use an explicit state with no finite sentinel");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "registrationtests.empty")
    public static void capacity_composition_handles_zero_finite_one_multiple_and_mixed_units(GameTestHelper helper) {
        StorageTypeCapacity zero = StorageTypeCapacity.zero();
        StorageTypeCapacity finite = zero
                .plus(StorageTypeCapacity.finite(10))
                .plus(StorageTypeCapacity.finite(25));
        StorageTypeCapacity oneCreative = finite.plus(StorageTypeCapacity.unlimitedCapacity());
        StorageTypeCapacity multipleCreative = oneCreative.plus(StorageTypeCapacity.unlimitedCapacity());

        if (zero.finiteTypeSlots() != 0 || zero.unlimited()) {
            helper.fail("Zero capacity must be finite zero");
            return;
        }
        if (finite.finiteTypeSlots() != 35 || finite.unlimited()) {
            helper.fail("Finite capacities must add exactly");
            return;
        }
        if (!oneCreative.unlimited() || oneCreative.finiteTypeSlots() != 35
                || !multipleCreative.equals(oneCreative)) {
            helper.fail("One or multiple Creative Storage Units must produce the same unlimited result");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void one_connected_creative_unit_makes_the_loaded_network_unlimited(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos creativeUnitPos = corePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            StorageTypeCapacity capacity = core.getTypeCapacity();
            if (!capacity.unlimited() || capacity.finiteTypeSlots() != 0
                    || !core.getConnectedBlocks().contains(creativeUnitPos)) {
                helper.fail("A connected Creative Storage Unit must provide explicit unlimited capacity");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void unlimited_network_accepts_more_component_types_than_all_finite_tiers(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(),
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            int finiteTierTotal = 10 + 25 + 50 + 100 + 200 + 400;
            for (int damage = 0; damage <= finiteTierTotal; damage++) {
                ItemStack variant = new ItemStack(Items.NETHERITE_PICKAXE);
                variant.setDamageValue(damage);
                if (core.insertItem(variant) != 1) {
                    helper.fail("Unlimited capacity rejected component variant " + damage);
                    return;
                }
            }
            if (core.getTypeCount() != finiteTierTotal + 1) {
                helper.fail("Expected " + (finiteTierTotal + 1)
                        + " distinct component types, got " + core.getTypeCount());
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void removing_and_reconnecting_creative_capacity_preserves_over_capacity_storage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos finiteUnitPos = corePos.east();
        BlockPos creativeUnitPos = corePos.west();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(finiteUnitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            for (int damage = 0; damage < 11; damage++) {
                ItemStack variant = new ItemStack(Items.DIAMOND_PICKAXE);
                variant.setDamageValue(damage);
                if (core.insertItem(variant) != 1) {
                    helper.fail("Could not seed over-capacity component variant " + damage);
                    return;
                }
            }

            level.removeBlock(creativeUnitPos, false);
            core.rebuildNetwork(level);
            if (core.getTypeCapacity().unlimited() || core.getTotalTypeSlots() != 10
                    || core.getTypeCount() != 11) {
                helper.fail("Removing creative capacity must keep 11 stored types over finite capacity 10");
                return;
            }
            ItemStack existing = new ItemStack(Items.DIAMOND_PICKAXE);
            existing.setDamageValue(10);
            if (core.extractItem(ItemKey.of(existing), 1).getCount() != 1) {
                helper.fail("Existing over-capacity items must remain extractable");
                return;
            }
            if (core.insertItem(new ItemStack(Items.DIRT)) != 0) {
                helper.fail("A new type must be rejected while the finite network remains full");
                return;
            }

            level.setBlock(creativeUnitPos,
                    MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(level);
            if (!core.getTypeCapacity().unlimited()
                    || core.insertItem(new ItemStack(Items.DIRT)) != 1) {
                helper.fail("Reconnecting creative capacity must immediately accept new types");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void creative_capacity_accepts_a_new_crafting_output_type(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(),
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            core.getMachineContainer().setItem(
                    MachineEnergyTable.CRAFTING_TABLE_SLOT, new ItemStack(Items.CRAFTING_TABLE));
            if (core.insertItem(new ItemStack(Items.OAK_PLANKS, 2)) != 2) {
                helper.fail("Could not seed crafting ingredients into creative-capacity storage");
                return;
            }
            var stickRecipe = level.getRecipeManager().byKey(
                    ResourceLocation.withDefaultNamespace("stick")
            ).orElse(null);
            if (stickRecipe == null) {
                helper.fail("Vanilla stick recipe not found");
                return;
            }
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(208, player.getInventory(), core);
            menu.getCurrentRecipes().add(stickRecipe);
            menu.clickMenuButton(player, CraftingTerminalMenu.OUTPUT_DESTINATION_BUTTON);
            menu.craftItem(1, player);

            if (core.getItemCount(ItemKey.of(new ItemStack(Items.OAK_PLANKS))) != 0
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.STICK))) != 4) {
                helper.fail("Unlimited capacity must accept a new crafting output type in storage");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void creative_capacity_incremental_add_matches_full_rebuild(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos finiteUnitPos = corePos.east();
        BlockPos firstCreativePos = finiteUnitPos.east();
        BlockPos secondCreativePos = firstCreativePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(finiteUnitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            level.setBlock(firstCreativePos,
                    MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);
            if (!core.tryIncrementalAdd(level, firstCreativePos)) {
                helper.fail("Incremental topology must accept a connected Creative Storage Unit");
                return;
            }
            StorageTypeCapacity oneCreativeIncremental = core.getTypeCapacity();
            core.rebuildNetwork(level);
            if (!oneCreativeIncremental.equals(core.getTypeCapacity())) {
                helper.fail("One-Creative incremental capacity must match full rebuild");
                return;
            }

            level.setBlock(secondCreativePos,
                    MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);
            if (!core.tryIncrementalAdd(level, secondCreativePos)) {
                helper.fail("Incremental topology must accept a second Creative Storage Unit");
                return;
            }
            StorageTypeCapacity multipleCreativeIncremental = core.getTypeCapacity();
            core.rebuildNetwork(level);
            if (!multipleCreativeIncremental.equals(core.getTypeCapacity())
                    || !multipleCreativeIncremental.equals(oneCreativeIncremental)
                    || !multipleCreativeIncremental.unlimited()
                    || multipleCreativeIncremental.finiteTypeSlots() != 10) {
                helper.fail("Mixed and multiple-Creative topology must have rebuild parity");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void creative_capacity_does_not_bypass_multi_core_conflict(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos firstCorePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos creativeUnitPos = firstCorePos.east();
        BlockPos secondCorePos = creativeUnitPos.east();
        level.setBlock(firstCorePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(firstCorePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("First Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            if (core.insertItem(new ItemStack(Items.STONE)) != 1) {
                helper.fail("Could not seed the valid one-Core network");
                return;
            }

            level.setBlock(secondCorePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(level);
            if (!core.isConflicted() || !core.getTypeCapacity().unlimited()
                    || core.insertItem(new ItemStack(Items.DIRT)) != 0
                    || !core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), 1).isEmpty()) {
                helper.fail("Creative capacity must not bypass multi-Core conflict behavior");
                return;
            }

            level.removeBlock(secondCorePos, false);
            core.rebuildNetwork(level);
            if (core.isConflicted()
                    || core.extractItem(ItemKey.of(new ItemStack(Items.STONE)), 1).getCount() != 1) {
                helper.fail("Resolving the Core conflict must restore access without deleting items");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void unlimited_capacity_survives_save_load_and_both_menu_sync_paths(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(),
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            if (core.insertItem(new ItemStack(Items.STONE, 7)) != 7) {
                helper.fail("Could not seed storage before save/load");
                return;
            }
            var saved = new net.minecraft.nbt.CompoundTag();
            core.saveAdditional(saved, level.registryAccess());
            level.removeBlockEntity(corePos);
            var reloaded = new StorageCoreBlockEntity(
                    corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState());
            reloaded.loadAdditional(saved, level.registryAccess());
            level.setBlockEntity(reloaded);
        });

        helper.runAfterDelay(4, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Reloaded Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            if (!core.getTypeCapacity().unlimited()
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 7) {
                helper.fail("Save/load must preserve items and topology-derived unlimited capacity");
                return;
            }

            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var storageServer = new StorageTerminalMenu(209, player.getInventory(), core);
            var storageBuffer = menuBuffer(level, corePos, false);
            var storageClient = new StorageTerminalMenu(
                    MagicStorage.STORAGE_TERMINAL_MENU.get(), 210, player.getInventory(), storageBuffer);
            if (!syncMenuData(storageServer, storageClient, 12)) {
                helper.fail("Storage menu data-slot parity must include explicit unlimited capacity");
                return;
            }

            var craftingServer = new CraftingTerminalMenu(211, player.getInventory(), core);
            var craftingBuffer = menuBuffer(level, corePos, true);
            var craftingClient = new CraftingTerminalMenu(212, player.getInventory(), craftingBuffer);
            if (!syncMenuData(craftingServer, craftingClient, 101)) {
                helper.fail("Crafting menu data-slot parity must include explicit unlimited capacity");
                return;
            }
            if (!storageServer.hasUnlimitedTypeCapacity()
                    || !storageClient.hasUnlimitedTypeCapacity()
                    || !craftingServer.hasUnlimitedTypeCapacity()
                    || !craftingClient.hasUnlimitedTypeCapacity()
                    || storageClient.getMaxTypes() != 0
                    || craftingClient.getMaxTypes() != 0) {
                helper.fail("Both terminal clients must receive explicit unlimited capacity after save/load");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void open_storage_menu_tracks_creative_disconnect_and_reconnect(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos creativeUnitPos = corePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new StorageTerminalMenu(213, player.getInventory(), core);
            if (!menu.hasUnlimitedTypeCapacity()) {
                helper.fail("Open Storage menu did not start with creative capacity");
                return;
            }

            level.removeBlock(creativeUnitPos, false);
            core.rebuildNetwork(level);
            menu.broadcastChanges();
            if (menu.hasUnlimitedTypeCapacity()) {
                helper.fail("Open Storage menu kept stale unlimited capacity after disconnect");
                return;
            }

            level.setBlock(creativeUnitPos,
                    MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(level);
            menu.broadcastChanges();
            if (!menu.hasUnlimitedTypeCapacity()) {
                helper.fail("Open Storage menu did not restore unlimited capacity after reconnect");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "registrationtests.empty")
    public static void creative_storage_unit_block_and_item_are_registered_without_a_recipe(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                MagicStorage.MODID, "creative_storage_unit");
        var blocks = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        var items = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM);
        if (blocks.get(id) != MagicStorage.CREATIVE_STORAGE_UNIT.get()
                || items.get(id) != MagicStorage.CREATIVE_STORAGE_UNIT_ITEM.get()
                || MagicStorage.CREATIVE_STORAGE_UNIT.get().asItem()
                        != MagicStorage.CREATIVE_STORAGE_UNIT_ITEM.get()) {
            helper.fail("Creative Storage Unit needs matching block/item command registration");
            return;
        }
        boolean hasRecipe = level.getRecipeManager().getRecipes().stream()
                .map(net.minecraft.world.item.crafting.RecipeHolder::value)
                .map(recipe -> recipe.getResultItem(level.registryAccess()))
                .anyMatch(stack -> stack.is(MagicStorage.CREATIVE_STORAGE_UNIT_ITEM.get()));
        if (hasRecipe) {
            helper.fail("Creative Storage Unit must not have a survival recipe");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void creative_storage_unit_has_no_inventory_or_item_generation(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos unitPos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(unitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(20, () -> {
            if (MagicStorage.CREATIVE_STORAGE_UNIT.get()
                    instanceof net.minecraft.world.level.block.EntityBlock
                    || level.getBlockEntity(unitPos) != null
                    || level.getBlockState(unitPos).isRandomlyTicking()) {
                helper.fail("Creative Storage Unit must not own inventory or ticking state");
                return;
            }
            if (!level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(unitPos)).isEmpty()) {
                helper.fail("Creative Storage Unit must never generate items");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void disconnecting_and_reconnecting_creative_unit_preserves_core_contents(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos finiteUnitPos = corePos.west();
        BlockPos bridgePos = corePos.east();
        BlockPos creativeUnitPos = bridgePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(finiteUnitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(bridgePos, MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            for (int damage = 0; damage < 11; damage++) {
                ItemStack variant = new ItemStack(Items.WOODEN_PICKAXE);
                variant.setDamageValue(damage);
                if (core.insertItem(variant) != 1) {
                    helper.fail("Could not seed disconnect over-capacity type " + damage);
                    return;
                }
            }

            level.removeBlock(bridgePos, false);
            core.rebuildNetwork(level);
            if (level.getBlockState(creativeUnitPos).isAir()
                    || core.getTypeCapacity().unlimited()
                    || core.getTotalTypeSlots() != 10
                    || core.getTypeCount() != 11
                    || core.insertItem(new ItemStack(Items.DIRT)) != 0) {
                helper.fail("Disconnected creative capacity must preserve finite over-capacity behavior");
                return;
            }

            level.setBlock(bridgePos,
                    MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(level);
            if (!core.getTypeCapacity().unlimited()
                    || core.insertItem(new ItemStack(Items.DIRT)) != 1) {
                helper.fail("Reconnecting the unchanged Creative Storage Unit must restore capacity");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void wrenching_creative_capacity_preserves_core_contents(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos finiteUnitPos = corePos.east();
        BlockPos creativeUnitPos = corePos.west();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(finiteUnitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            for (int damage = 0; damage < 11; damage++) {
                ItemStack variant = new ItemStack(Items.IRON_PICKAXE);
                variant.setDamageValue(damage);
                if (core.insertItem(variant) != 1) {
                    helper.fail("Could not seed wrench over-capacity type " + damage);
                    return;
                }
            }

            var player = FakePlayerFactory.get(
                    level, new GameProfile(UUID.randomUUID(), "creative-unit-wrench-test"));
            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicStorage.WRENCH.get()));
            player.setShiftKeyDown(true);
            var hit = new BlockHitResult(
                    Vec3.atCenterOf(creativeUnitPos), Direction.UP, creativeUnitPos, false);
            if (!WrenchActions.tryUse(level, player, InteractionHand.MAIN_HAND, hit).consumesAction()
                    || !level.getBlockState(creativeUnitPos).isAir()) {
                helper.fail("Sneak-wrench did not dismantle the Creative Storage Unit");
                return;
            }
            core.rebuildNetwork(level);
            boolean receivedUnit = false;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (player.getInventory().getItem(slot).is(MagicStorage.CREATIVE_STORAGE_UNIT_ITEM.get())) {
                    receivedUnit = true;
                    break;
                }
            }
            if (!receivedUnit || core.getTypeCapacity().unlimited()
                    || core.getTotalTypeSlots() != 10 || core.getTypeCount() != 11) {
                helper.fail("Wrench dismantle must return the unit and preserve over-capacity Core contents");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform", batch = "creative_explosion")
    public static void exploding_creative_capacity_preserves_core_contents(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos finiteUnitPos = corePos.east();
        BlockPos creativeUnitPos = corePos.west();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(finiteUnitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            for (int damage = 0; damage < 11; damage++) {
                ItemStack variant = new ItemStack(Items.GOLDEN_PICKAXE);
                variant.setDamageValue(damage);
                if (core.insertItem(variant) != 1) {
                    helper.fail("Could not seed explosion over-capacity type " + damage);
                    return;
                }
            }

            var calculator = new net.minecraft.world.level.ExplosionDamageCalculator() {
                @Override
                public java.util.Optional<Float> getBlockExplosionResistance(
                        net.minecraft.world.level.Explosion explosion,
                        net.minecraft.world.level.BlockGetter reader,
                        BlockPos pos,
                        net.minecraft.world.level.block.state.BlockState state,
                        net.minecraft.world.level.material.FluidState fluid
                ) {
                    if (pos.equals(creativeUnitPos)) return java.util.Optional.of(0.0F);
                    if (state.isAir() && fluid.isEmpty()) return java.util.Optional.empty();
                    return java.util.Optional.of(10000.0F);
                }

                @Override
                public boolean shouldBlockExplode(
                        net.minecraft.world.level.Explosion explosion,
                        net.minecraft.world.level.BlockGetter reader,
                        BlockPos pos,
                        net.minecraft.world.level.block.state.BlockState state,
                        float power
                ) {
                    return pos.equals(creativeUnitPos);
                }
            };
            level.explode(
                    null,
                    null,
                    calculator,
                    Vec3.atCenterOf(creativeUnitPos),
                    2.0F,
                    false,
                    net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
            core.rebuildNetwork(level);
            if (!level.getBlockState(creativeUnitPos).isAir()
                    || core.getTypeCapacity().unlimited()
                    || core.getTotalTypeSlots() != 10
                    || core.getTypeCount() != 11) {
                helper.fail("Explosion must remove creative capacity without deleting Core contents");
                return;
            }
            level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(creativeUnitPos).inflate(8.0D)
            ).forEach(net.minecraft.world.entity.Entity::discard);
            helper.succeed();
        });
    }

    @GameTest(
            template = "behavioraltests.platform",
            batch = "creative_chunk_unload",
            timeoutTicks = 400)
    public static void unloading_and_reloading_creative_capacity_preserves_core_contents(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 3, 1));
        ChunkPos coreChunk = new ChunkPos(anchor);
        BlockPos corePos = null;
        BlockPos finiteUnitPos = null;
        BlockPos creativeUnitPos = null;
        Direction networkDirection = null;
        for (Direction direction : new Direction[]{
                Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
            BlockPos candidateCore = switch (direction) {
                case EAST -> new BlockPos(
                        coreChunk.getMaxBlockX() - 1, anchor.getY(), coreChunk.getMiddleBlockZ());
                case WEST -> new BlockPos(
                        coreChunk.getMinBlockX() + 1, anchor.getY(), coreChunk.getMiddleBlockZ());
                case SOUTH -> new BlockPos(
                        coreChunk.getMiddleBlockX(), anchor.getY(), coreChunk.getMaxBlockZ() - 1);
                default -> new BlockPos(
                        coreChunk.getMiddleBlockX(), anchor.getY(), coreChunk.getMinBlockZ() + 1);
            };
            BlockPos candidateCreative = candidateCore.relative(direction, 34);
            if (!level.getForcedChunks().contains(new ChunkPos(candidateCreative).toLong())) {
                corePos = candidateCore;
                finiteUnitPos = candidateCore.relative(direction);
                creativeUnitPos = candidateCreative;
                networkDirection = direction;
                break;
            }
        }
        if (corePos == null) {
            helper.fail("No adjacent unforced chunk available for unload coverage");
            return;
        }

        BlockPos finalCorePos = corePos;
        BlockPos finalCreativeUnitPos = creativeUnitPos;
        Direction finalNetworkDirection = networkDirection;
        ChunkPos creativeChunk = new ChunkPos(creativeUnitPos);
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(finiteUnitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        java.util.Set<ChunkPos> remoteChunks = new java.util.HashSet<>();
        for (int distance = 2; distance < 34; distance++) {
            BlockPos bridgePos = corePos.relative(networkDirection, distance);
            ChunkPos bridgeChunk = new ChunkPos(bridgePos);
            remoteChunks.add(bridgeChunk);
            level.getChunkAt(bridgePos);
            level.setBlock(bridgePos,
                    MagicStorage.STORAGE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        remoteChunks.add(creativeChunk);
        level.getChunkAt(creativeUnitPos);
        level.setBlock(creativeUnitPos,
                MagicStorage.CREATIVE_STORAGE_UNIT.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(finalCorePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Storage Core block entity missing");
                return;
            }
            core.rebuildNetwork(level);
            for (int damage = 0; damage < 11; damage++) {
                ItemStack variant = new ItemStack(Items.STONE_PICKAXE);
                variant.setDamageValue(damage);
                if (core.insertItem(variant) != 1) {
                    helper.fail("Could not seed unload over-capacity type " + damage);
                    return;
                }
            }
            for (ChunkPos remoteChunk : remoteChunks) {
                level.getChunkSource().removeRegionTicket(
                        net.minecraft.server.level.TicketType.UNKNOWN,
                        remoteChunk,
                        0,
                        remoteChunk);
            }
            helper.succeedWhen(() -> {
                helper.assertFalse(level.hasChunkAt(finalCreativeUnitPos),
                        "Waiting for Creative Storage Unit chunk to unload: "
                                + level.getChunkSource().getChunkDebugData(creativeChunk));
                core.rebuildNetwork(level);
                helper.assertTrue(!core.getTypeCapacity().unlimited()
                                && core.getTotalTypeSlots() == 10
                                && core.getTypeCount() == 11,
                        "Unloaded creative capacity must leave Core contents over finite capacity");

                level.getChunkAt(finalCreativeUnitPos);
                core.rebuildNetwork(level);
                helper.assertTrue(core.getTypeCapacity().unlimited()
                                && core.getTypeCount() == 11
                                && core.getConnectedBlocks().contains(
                                        finalCorePos.relative(finalNetworkDirection, 34)),
                        "Reloading the Creative Storage Unit chunk must restore unlimited capacity");
                for (int distance = 34; distance >= 0; distance--) {
                    level.removeBlock(finalCorePos.relative(finalNetworkDirection, distance), false);
                }
                level.getChunkSource().removeRegionTicket(
                        net.minecraft.server.level.TicketType.UNKNOWN,
                        creativeChunk,
                        0,
                        creativeChunk);
            });
        });
    }

    private static net.minecraft.network.RegistryFriendlyByteBuf menuBuffer(
            net.minecraft.world.level.Level level,
            BlockPos corePos,
            boolean includeMachineSnapshot
    ) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        buffer.writeBlockPos(corePos);
        buffer.writeBlockPos(corePos);
        buffer.writeBoolean(false);
        if (includeMachineSnapshot) {
            MachineEnergyTable.writeSnapshot(buffer, MachineEnergyTable.entries());
        }
        return buffer;
    }

    private static boolean syncMenuData(
            net.minecraft.world.inventory.AbstractContainerMenu server,
            net.minecraft.world.inventory.AbstractContainerMenu client,
            int expectedSlots
    ) {
        java.util.List<net.minecraft.world.inventory.DataSlot> serverData = dataSlots(server);
        java.util.List<net.minecraft.world.inventory.DataSlot> clientData = dataSlots(client);
        if (serverData.size() != expectedSlots || clientData.size() != expectedSlots) return false;
        for (int i = 0; i < serverData.size(); i++) {
            clientData.get(i).set(serverData.get(i).get());
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<net.minecraft.world.inventory.DataSlot> dataSlots(
            net.minecraft.world.inventory.AbstractContainerMenu menu
    ) {
        try {
            var field = net.minecraft.world.inventory.AbstractContainerMenu.class.getDeclaredField("dataSlots");
            field.setAccessible(true);
            return (java.util.List<net.minecraft.world.inventory.DataSlot>) field.get(menu);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
