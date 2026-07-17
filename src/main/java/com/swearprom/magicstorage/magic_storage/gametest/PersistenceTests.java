package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

@GameTestHolder(MagicStorage.MODID)
public class PersistenceTests {

    @GameTest(template = "platform")
    public static void core_persistence_contract_is_repository_owned_and_segmented(GameTestHelper helper) {
        if (!CoreStorageRepository.DATA_NAME.equals("magic_storage_core_storages")) {
            helper.fail("Core storage repository must use the approved world data name");
            return;
        }
        if (CoreStorageRecord.MAX_SEGMENT_TYPES != 63) {
            helper.fail("Core storage records must use 63-type persistence segments");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void core_storage_record_round_trip_preserves_all_state(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        UUID storageId = UUID.randomUUID();
        CoreStorageRecord source = CoreStorageRecord.fresh(storageId);
        UUID networkId = source.networkId();
        for (EnergyType type : EnergyType.values()) {
            source.energy().put(type, 101L + type.ordinal());
        }
        ItemStack namedDiamond = new ItemStack(Items.DIAMOND);
        namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("Repository Diamond"));
        source.putItem(ItemKey.of(namedDiamond), Long.MAX_VALUE - 7);
        source.machines().setItem(MachineEnergyTable.FURNACE_SLOT, new ItemStack(Items.FURNACE, 3));
        source.descriptorAmounts().put(MachineEnergyTable.AXE_ID, 37L);
        source.infiniteDescriptors().add(ResourceLocation.fromNamespaceAndPath(
                MagicStorage.MODID, "round_trip_infinite"));

        CompoundTag unresolvedInventory = unresolvedInventoryEntry(
                registries, "removed_addon:storage_item", 73);
        CompoundTag unresolvedMachine = unresolvedMachineEntry(
                registries, "removed_addon:machine", "removed_addon:machine_item", 5);
        CompoundTag unresolvedDescriptor = new CompoundTag();
        unresolvedDescriptor.putString("descriptorId", "removed_addon:consumable");
        unresolvedDescriptor.putLong("amount", 19);
        unresolvedDescriptor.putBoolean("infinite", false);
        source.unresolvedInventoryEntries().add(unresolvedInventory.copy());
        source.unresolvedMachineEntries().add(unresolvedMachine.copy());
        source.unresolvedDescriptorEntries().add(unresolvedDescriptor.copy());

        CompoundTag encoded = source.save(registries);
        CoreStorageRecord.LoadResult decoded = CoreStorageRecord.load(encoded, registries);
        if (!decoded.success()) {
            helper.fail("Core storage record did not decode: " + decoded.error());
            return;
        }
        CoreStorageRecord restored = decoded.record();
        if (!restored.storageId().equals(storageId) || !restored.networkId().equals(networkId)) {
            helper.fail("Storage or network UUID changed during repository round trip");
            return;
        }
        for (EnergyType type : EnergyType.values()) {
            if (restored.energy().getOrDefault(type, 0L) != 101L + type.ordinal()) {
                helper.fail("Energy did not round trip for " + type);
                return;
            }
        }
        if (restored.getItemCount(ItemKey.of(namedDiamond)) != Long.MAX_VALUE - 7
                || restored.machines().getItem(MachineEnergyTable.FURNACE_SLOT).getCount() != 3
                || restored.descriptorAmounts().getOrDefault(MachineEnergyTable.AXE_ID, 0L) != 37
                || restored.unresolvedInventoryEntries().size() != 1
                || restored.unresolvedMachineEntries().size() != 1
                || restored.unresolvedDescriptorEntries().size() != 2) {
            helper.fail("Core storage record lost durable state during round trip");
            return;
        }
        if (!restored.unresolvedInventoryEntries().getFirst().equals(unresolvedInventory)
                || !restored.unresolvedMachineEntries().getFirst().equals(unresolvedMachine)) {
            helper.fail("Unavailable addon NBT changed during repository round trip");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void core_storage_record_segments_never_exceed_63_types(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        int[] cases = {0, 1, 63, 64, 785, 786};
        int[] expectedSegments = {0, 1, 1, 2, 13, 13};
        for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
            CoreStorageRecord record = CoreStorageRecord.fresh(UUID.randomUUID());
            for (int index = 0; index < cases[caseIndex]; index++) {
                ItemStack variant = new ItemStack(Items.PAPER);
                variant.set(DataComponents.CUSTOM_NAME, Component.literal("segment-" + index));
                record.putItem(ItemKey.of(variant), index + 1L);
            }
            CompoundTag encoded = record.save(registries);
            ListTag segments = encoded.getList("inventorySegments", Tag.TAG_COMPOUND);
            if (segments.size() != expectedSegments[caseIndex]) {
                helper.fail(cases[caseIndex] + " types used " + segments.size()
                        + " segments instead of " + expectedSegments[caseIndex]);
                return;
            }
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                int entries = segments.getCompound(segmentIndex)
                        .getList("entries", Tag.TAG_COMPOUND).size();
                if (entries > CoreStorageRecord.MAX_SEGMENT_TYPES) {
                    helper.fail("Segment exceeded 63 types: " + entries);
                    return;
                }
            }
            CoreStorageRecord.LoadResult decoded = CoreStorageRecord.load(encoded, registries);
            if (!decoded.success() || decoded.record().typeCount() != cases[caseIndex]) {
                helper.fail("Segmented record did not restore " + cases[caseIndex] + " types");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void core_storage_repository_enforces_exact_runtime_attachment(GameTestHelper helper) {
        CoreStorageRepository repository = new CoreStorageRepository();
        CoreStorageRepository.CoreLocation firstLocation = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 3, 1)));
        CoreStorageRepository.CoreLocation secondLocation = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4, 3, 1)));
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        CoreStorageRecord record = repository.createFresh(firstLocation, firstOwner);

        CoreStorageRepository.AttachResult duplicate = repository.attachExisting(
                record.storageId(), secondLocation, secondOwner);
        if (duplicate.success()
                || duplicate.reason() != CoreStorageRepository.AttachFailure.DUPLICATE_ATTACHMENT) {
            helper.fail("A second loaded Core attached to the same storage UUID");
            return;
        }
        repository.release(record.storageId(), firstLocation, secondOwner);
        if (repository.attachExisting(record.storageId(), secondLocation, secondOwner).success()) {
            helper.fail("A non-owner token released another Core attachment");
            return;
        }
        repository.release(record.storageId(), firstLocation, firstOwner);
        CoreStorageRepository.AttachResult transferred = repository.attachExisting(
                record.storageId(), secondLocation, secondOwner);
        if (!transferred.success() || transferred.record() != record) {
            helper.fail("Released repository record did not attach at the new location");
            return;
        }
        int recordsBeforeMissingLookup = repository.recordCountForTesting();
        CoreStorageRepository.AttachResult missing = repository.attachExisting(
                UUID.randomUUID(), firstLocation, UUID.randomUUID());
        if (missing.success() || missing.reason() != CoreStorageRepository.AttachFailure.MISSING_RECORD
                || repository.recordCountForTesting() != recordsBeforeMissingLookup) {
            helper.fail("Missing storage lookup manufactured a blank record");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void core_storage_recovery_reuses_one_record_without_payload_snapshot(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        CoreStorageRepository repository = new CoreStorageRepository();
        CoreStorageRepository.CoreLocation origin = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 3, 1)));
        CoreStorageRepository.CoreLocation target = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4, 3, 1)));
        UUID originalOwnerToken = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        CoreStorageRecord stored = repository.createFresh(origin, originalOwnerToken);
        UUID networkId = stored.networkId();
        stored.putItem(ItemKey.of(new ItemStack(Items.DIAMOND)), 81);

        CoreStorageRepository.RecoverySummary summary = repository.prepareRecovery(
                stored.storageId(), origin, originalOwnerToken, playerId, 1234L).orElseThrow();
        if (!repository.reissueLatest(playerId).orElseThrow().id().equals(summary.id())) {
            helper.fail("Recovery reissue created a different capability UUID");
            return;
        }
        CompoundTag repositoryTag = repository.save(new CompoundTag(), registries);
        ListTag recoveries = repositoryTag.getList("recoveries", Tag.TAG_COMPOUND);
        if (recoveries.size() != 1
                || !recoveries.getCompound(0).hasUUID("storageId")
                || recoveries.getCompound(0).contains("contents")) {
            helper.fail("Recovery entry copied Core payload instead of referencing storage UUID");
            return;
        }

        repository.release(stored.storageId(), origin, originalOwnerToken);
        UUID targetOwnerToken = UUID.randomUUID();
        CoreStorageRecord temporaryFresh = repository.createFresh(target, targetOwnerToken);
        CoreStorageRepository.ClaimResult claimed = repository.claimIntoFresh(
                summary.id(), temporaryFresh.storageId(), target, targetOwnerToken);
        if (!claimed.success() || claimed.record() != stored
                || !claimed.record().networkId().equals(networkId)
                || claimed.record().getItemCount(ItemKey.of(new ItemStack(Items.DIAMOND))) != 81
                || repository.hasRecovery(summary.id())
                || repository.hasRecord(temporaryFresh.storageId())) {
            helper.fail("Recovery claim copied, reset, or failed to consume the repository mapping");
            return;
        }
        CoreStorageRepository.ClaimResult duplicateClaim = repository.claimIntoFresh(
                summary.id(), stored.storageId(), target, targetOwnerToken);
        if (duplicateClaim.success()
                || duplicateClaim.reason() != CoreStorageRepository.AttachFailure.RECOVERY_MISSING) {
            helper.fail("A copied recovery token claimed the same storage twice");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void packed_record_only_allows_exact_origin_interruption_recovery(GameTestHelper helper) {
        CoreStorageRepository repository = new CoreStorageRepository();
        CoreStorageRepository.CoreLocation origin = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 3, 1)));
        CoreStorageRepository.CoreLocation copy = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4, 3, 1)));
        UUID originalToken = UUID.randomUUID();
        CoreStorageRecord record = repository.createFresh(origin, originalToken);
        UUID recoveryId = repository.prepareRecovery(
                record.storageId(), origin, originalToken, UUID.randomUUID(), 99L).orElseThrow().id();
        repository.release(record.storageId(), origin, originalToken);

        CoreStorageRepository.AttachResult copiedAttach = repository.attachExisting(
                record.storageId(), copy, UUID.randomUUID());
        if (copiedAttach.success()
                || copiedAttach.reason() != CoreStorageRepository.AttachFailure.PACKED) {
            helper.fail("A stale copied Core attached a packed storage record");
            return;
        }
        CoreStorageRepository.AttachResult originAttach = repository.attachExisting(
                record.storageId(), origin, UUID.randomUUID());
        if (!originAttach.success() || repository.hasRecovery(recoveryId)) {
            helper.fail("Exact origin did not cancel an interrupted pack");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void corrupt_repository_records_are_preserved_and_fail_closed(GameTestHelper helper) {
        UUID corruptId = UUID.randomUUID();
        CompoundTag corrupt = new CompoundTag();
        corrupt.putUUID("storageId", corruptId);
        corrupt.putString("sentinel", "retain-me");
        CompoundTag orphan = new CompoundTag();
        orphan.putString("sentinel", "orphan-retain-me");
        ListTag storages = new ListTag();
        storages.add(corrupt.copy());
        storages.add(orphan.copy());
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", 1);
        root.put("storages", storages);
        root.put("recoveries", new ListTag());

        CoreStorageRepository repository = CoreStorageRepository.load(
                root, helper.getLevel().registryAccess());
        CoreStorageRepository.AttachResult attached = repository.attachExisting(
                corruptId,
                new CoreStorageRepository.CoreLocation(
                        helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 3, 1))),
                UUID.randomUUID());
        if (attached.success()
                || attached.reason() != CoreStorageRepository.AttachFailure.CORRUPT_RECORD) {
            helper.fail("Corrupt repository record did not fail closed");
            return;
        }
        CompoundTag resaved = repository.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag retained = resaved.getList("storages", Tag.TAG_COMPOUND);
        if (retained.size() != 2 || !retained.contains(corrupt) || !retained.contains(orphan)) {
            helper.fail("Corrupt or orphan repository NBT was not retained exactly: " + retained);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void repository_deletes_only_empty_attached_records(GameTestHelper helper) {
        CoreStorageRepository repository = new CoreStorageRepository();
        CoreStorageRepository.CoreLocation location = new CoreStorageRepository.CoreLocation(
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 3, 1)));
        UUID ownerToken = UUID.randomUUID();
        CoreStorageRecord empty = repository.createFresh(location, ownerToken);
        if (!repository.removeIfEmpty(empty.storageId(), location, ownerToken)
                || repository.hasRecord(empty.storageId())) {
            helper.fail("Empty Core record was not deleted on removal");
            return;
        }

        CoreStorageRecord populated = repository.createFresh(location, ownerToken);
        populated.putItem(ItemKey.of(new ItemStack(Items.STONE)), 1);
        if (repository.removeIfEmpty(populated.storageId(), location, ownerToken)
                || !repository.hasRecord(populated.storageId())) {
            helper.fail("Non-empty Core record was deleted on removal");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void storage_core_can_always_be_harvested_without_losing_its_drop(GameTestHelper helper) {
        if (MagicStorage.STORAGE_CORE.get().defaultBlockState().requiresCorrectToolForDrops()) {
            helper.fail("Storage Core must not delete contents when broken without the preferred tool");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void creative_break_drops_one_time_recoverable_packed_core(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var restoredPos = helper.absolutePos(new BlockPos(4, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core BE not found");
                return;
            }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.insertItem(new ItemStack(Items.DIRT, 17));

            var creativePlayer = helper.makeMockPlayer(GameType.CREATIVE);
            var state = level.getBlockState(corePos);
            state.getBlock().playerWillDestroy(level, corePos, state, creativePlayer);

            ItemStack packedCore = level.getEntitiesOfClass(
                            ItemEntity.class, new AABB(corePos).inflate(2.0)).stream()
                    .map(ItemEntity::getItem)
                    .filter(stack -> stack.is(MagicStorage.STORAGE_CORE_ITEM.get()))
                    .findFirst()
                    .map(ItemStack::copy)
                    .orElse(ItemStack.EMPTY);
            if (packedCore.isEmpty()) {
                helper.fail("Creative break did not create a packed Core");
                return;
            }
            var recoveryId = StorageCoreBlockItem.getRecoveryId(packedCore);
            if (recoveryId.isEmpty()) {
                helper.fail("Packed Core is missing its recovery token");
                return;
            }
            if (StorageCoreBlockItem.getStoredTypeCount(packedCore) != 2) {
                helper.fail("Packed Core summary should report two stored types");
                return;
            }

            level.setBlock(restoredPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
            MagicStorage.STORAGE_CORE.get().setPlacedBy(
                    level, restoredPos, level.getBlockState(restoredPos), creativePlayer, packedCore);
            if (!(level.getBlockEntity(restoredPos) instanceof StorageCoreBlockEntity restored)) {
                helper.fail("Restored Core BE not found");
                return;
            }
            if (restored.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 64
                    || restored.getItemCount(ItemKey.of(new ItemStack(Items.DIRT))) != 17) {
                helper.fail("Creative packed Core did not restore its exact contents");
                return;
            }
            if (CoreRecoverySavedData.get(level).claim(recoveryId.get()).isPresent()) {
                helper.fail("Recovery token must be one-time after successful placement");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void failed_packed_core_placement_keeps_recovery_snapshot(GameTestHelper helper) {
        var level = helper.getLevel();
        var owner = UUID.randomUUID();
        var recovery = CoreRecoverySavedData.get(level);
        UUID recoveryId = recovery.create(new CompoundTag(), owner, 0, 0, level.getGameTime());
        ItemStack packedCore = StorageCoreBlockItem.createRecoveryStack(
                recovery.summary(recoveryId).orElseThrow());
        BlockPos missingCorePos = helper.absolutePos(new BlockPos(4, 3, 1));

        try {
            MagicStorage.STORAGE_CORE.get().setPlacedBy(
                    level,
                    missingCorePos,
                    MagicStorage.STORAGE_CORE.get().defaultBlockState(),
                    helper.makeMockPlayer(GameType.SURVIVAL),
                    packedCore);
            helper.fail("Placement without a Core block entity must fail closed");
            return;
        } catch (IllegalStateException expected) {
            if (!recovery.has(recoveryId)) {
                helper.fail("Failed placement consumed the only recovery snapshot");
                return;
            }
        }
        recovery.claim(recoveryId);
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void latest_recovery_prefers_last_core_created_in_the_same_tick(GameTestHelper helper) {
        var level = helper.getLevel();
        var owner = UUID.randomUUID();
        var recovery = CoreRecoverySavedData.get(level);
        long createdAt = level.getGameTime();
        UUID first = recovery.create(new CompoundTag(), owner, 1, 11, createdAt);
        UUID second = recovery.create(new CompoundTag(), owner, 2, 22, createdAt);
        var latest = recovery.reissueLatest(owner);
        if (latest.isEmpty() || !latest.get().id().equals(second)) {
            helper.fail("Same-tick recovery reissue did not select the last created Core");
            return;
        }
        recovery.claim(first);
        recovery.claim(second);
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void core_identity_is_unique_and_survives_nbt_round_trip(GameTestHelper helper) {
        var level = helper.getLevel();
        var firstPos = helper.absolutePos(new BlockPos(1, 3, 1));
        var secondPos = helper.absolutePos(new BlockPos(4, 3, 1));
        level.setBlock(firstPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(secondPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(firstPos) instanceof StorageCoreBlockEntity first)
                    || !(level.getBlockEntity(secondPos) instanceof StorageCoreBlockEntity second)) {
                helper.fail("Core block entities not found");
                return;
            }
            if (first.getNetworkId().equals(second.getNetworkId())) {
                helper.fail("Fresh storage cores must have distinct persistent identities");
                return;
            }

            var tag = new net.minecraft.nbt.CompoundTag();
            first.saveAdditional(tag, level.registryAccess());
            var restored = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
            restored.loadAdditional(tag, level.registryAccess());
            if (!restored.getNetworkId().equals(first.getNetworkId())) {
                helper.fail("Storage core identity did not survive NBT round-trip");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void broken_core_drop_carries_inventory_data_and_reloads(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.insertItem(new ItemStack(Items.DIRT, 50));
            core.getMachineContainer().setItem(0, new ItemStack(Items.FURNACE, 4));

            var drops = Block.getDrops(level.getBlockState(corePos), level, corePos, core);
            ItemStack droppedCore = ItemStack.EMPTY;
            for (ItemStack drop : drops) {
                if (drop.is(MagicStorage.STORAGE_CORE_ITEM.get())) {
                    droppedCore = drop;
                    break;
                }
            }
            if (droppedCore.isEmpty()) { helper.fail("Core drop missing"); return; }
            if (!droppedCore.has(DataComponents.BLOCK_ENTITY_DATA)) {
                helper.fail("Core drop should carry block entity data");
                return;
            }

            var restoredPos = helper.absolutePos(new BlockPos(4, 3, 1));
            level.setBlock(restoredPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(restoredPos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
            if (!BlockItem.updateCustomBlockEntityTag(level, null, restoredPos, droppedCore)) {
                helper.fail("Core drop block entity data did not load into placed core");
                return;
            }

            var restoredBe = level.getBlockEntity(restoredPos);
            if (!(restoredBe instanceof StorageCoreBlockEntity restoredCore)) { helper.fail("Restored core BE not found"); return; }
            if (restoredCore.getTypeCount() != 2) {
                helper.fail("Restored core should have 2 types, got " + restoredCore.getTypeCount());
                return;
            }
            if (restoredCore.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 128) {
                helper.fail("Restored core lost stone count");
                return;
            }
            if (restoredCore.getItemCount(ItemKey.of(new ItemStack(Items.DIRT))) != 50) {
                helper.fail("Restored core lost dirt count");
                return;
            }
            ItemStack restoredMachines = restoredCore.getMachineContainer().getItem(0);
            if (!restoredMachines.is(Items.FURNACE) || restoredMachines.getCount() != 4) {
                helper.fail("Restored core lost its installed Furnace stack: " + restoredMachines);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void blank_core_after_break_does_not_clone_previous_inventory(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.getMachineContainer().setItem(0, new ItemStack(Items.FURNACE, 2));
            if (core.getTypeCount() != 1) { helper.fail("Should have 1 type"); return; }

            level.destroyBlock(corePos, false);
            level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

            helper.runAfterDelay(1, () -> {
                var blankBe = level.getBlockEntity(corePos);
                if (!(blankBe instanceof StorageCoreBlockEntity blankCore)) { helper.fail("Blank core BE not found"); return; }
                blankCore.onLoad();
                if (blankCore.getTypeCount() != 0) {
                    helper.fail("Blank core cloned previous inventory after break");
                    return;
                }
                if (!blankCore.getMachineContainer().isEmpty()) {
                    helper.fail("Blank core cloned previously installed machines after break");
                    return;
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "platform")
    public static void items_survive_core_break_and_rebuild(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.insertItem(new ItemStack(Items.STONE, 64));
            core.insertItem(new ItemStack(Items.DIRT, 50));
            if (core.getTypeCount() != 2) { helper.fail("Should have 2 types"); return; }

            level.destroyBlock(corePos, true);

            helper.runAfterDelay(3, () -> {
                ItemStack droppedCore = ItemStack.EMPTY;
                for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(corePos).inflate(2.0))) {
                    ItemStack stack = entity.getItem();
                    if (stack.is(MagicStorage.STORAGE_CORE_ITEM.get())) {
                        droppedCore = stack.copy();
                        break;
                    }
                }
                if (droppedCore.isEmpty()) { helper.fail("Dropped core item not found"); return; }
                if (!droppedCore.has(DataComponents.BLOCK_ENTITY_DATA)) {
                    helper.fail("Dropped core item should carry block entity data");
                    return;
                }

                level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
                if (!BlockItem.updateCustomBlockEntityTag(level, null, corePos, droppedCore)) {
                    helper.fail("Dropped core data did not load into replacement core");
                    return;
                }
                var newBe = level.getBlockEntity(corePos);
                if (!(newBe instanceof StorageCoreBlockEntity newCore)) { helper.fail("New core BE not found"); return; }
                if (newCore.getTypeCount() != 2) { helper.fail("Restored core should have 2 types"); return; }
                if (newCore.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 128) {
                    helper.fail("Restored core lost stone count");
                    return;
                }
                if (newCore.getItemCount(ItemKey.of(new ItemStack(Items.DIRT))) != 50) {
                    helper.fail("Restored core lost dirt count");
                    return;
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "platform")
    public static void export_bus_filter_nbt_preserves_components(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.runAfterDelay(1, () -> {
            var registries = level.registryAccess();
            var busPos = helper.absolutePos(new BlockPos(1, 3, 1));
            var busState = MagicStorage.EXPORT_BUS.get().defaultBlockState();

            var sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(
                registries.registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SHARPNESS),
                1
            );

            var bus = new ExportBusBlockEntity(busPos, busState);
            bus.setFilter(sword.copy());

            ItemKey filterBefore = bus.getFilter();
            if (filterBefore == null) { helper.fail("Filter not set"); return; }

            var tag = new CompoundTag();
            bus.saveAdditional(tag, registries);

            var bus2 = new ExportBusBlockEntity(busPos, busState);
            bus2.loadAdditional(tag, registries);

            ItemKey filterAfter = bus2.getFilter();
            if (filterAfter == null) { helper.fail("Filter lost after NBT load"); return; }
            if (!filterAfter.toStack(1).isEnchanted()) {
                helper.fail("Filter components (enchantment) were lost after NBT round-trip");
                return;
            }
            if (!filterAfter.equals(filterBefore)) {
                helper.fail("Filter ItemKey changed after NBT round-trip");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void advanced_search_at_mod(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            // @minecraft should match stone, @magic_storage should not
            var byMinecraft = core.getDisplayStacks("@minecraft");
            if (byMinecraft.size() < 1) helper.fail("@minecraft should match vanilla items");
            var byMod = core.getDisplayStacks("@magic_storage");
            if (!byMod.isEmpty()) helper.fail("@magic_storage should not match vanilla items");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void advanced_search_empty_filter(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));
            core.insertItem(new ItemStack(Items.DIRT, 1));
            // null and blank filter should show all
            if (core.getDisplayStacks(null).size() != 2) helper.fail("null filter should show all");
            if (core.getDisplayStacks("").size() != 2) helper.fail("empty filter should show all");
            if (core.getDisplayStacks("   ").size() != 2) helper.fail("blank filter should show all");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void fuzzy_match_across_variants(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var enchanted = new ItemStack(Items.DIAMOND_SWORD);
            enchanted.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SHARPNESS), 1);
            core.insertItem(enchanted);
            core.insertItem(new ItemStack(Items.DIAMOND_SWORD));
            if (core.getTypeCount() != 2) { helper.fail("expected 2 variants, got " + core.getTypeCount()); return; }
            long fuzzy = core.countMatching(s -> s.is(Items.DIAMOND_SWORD));
            if (fuzzy != 2) { helper.fail("fuzzy count should be 2, got " + fuzzy); return; }
            long exact = core.countMatching(s -> ItemStack.isSameItemSameComponents(s, new ItemStack(Items.DIAMOND_SWORD)));
            if (exact != 1) { helper.fail("exact count should be 1, got " + exact); return; }
            long got = core.extractMatching(s -> s.is(Items.DIAMOND_SWORD), 1, false);
            if (got != 1) { helper.fail("should extract 1, got " + got); return; }
            if (core.countMatching(s -> s.is(Items.DIAMOND_SWORD)) != 1) { helper.fail("1 should remain"); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void item_count_at_long_max_rejects_insert_without_wrapping(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.east(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            var entry = new CompoundTag();
            entry.put("item", new ItemStack(Items.STONE, 1).save(level.registryAccess()));
            entry.putLong("count", Long.MAX_VALUE);
            var inventory = new ListTag();
            inventory.add(entry);
            var tag = new CompoundTag();
            tag.put("inventory", inventory);
            core.loadAdditional(tag, level.registryAccess());

            var input = new ItemStack(Items.STONE, 1);
            long inserted = core.insertItem(input, Action.EXECUTE, Actor.EMPTY);
            long stored = core.getItemCount(ItemKey.of(new ItemStack(Items.STONE)));
            if (inserted != 0) { helper.fail("Long.MAX_VALUE variant must reject another item, accepted " + inserted); return; }
            if (input.getCount() != 1) { helper.fail("Rejected item must remain in input stack"); return; }
            if (stored != Long.MAX_VALUE) { helper.fail("Stored count wrapped or changed: " + stored); return; }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void fuzzy_count_saturates_when_matching_variants_exceed_long_max(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
            ItemStack enchanted = new ItemStack(Items.DIAMOND_SWORD);
            enchanted.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SHARPNESS), 1);

            var inventory = new ListTag();
            var fullEntry = new CompoundTag();
            fullEntry.put("item", plain.save(level.registryAccess()));
            fullEntry.putLong("count", Long.MAX_VALUE);
            inventory.add(fullEntry);
            var extraEntry = new CompoundTag();
            extraEntry.put("item", enchanted.save(level.registryAccess()));
            extraEntry.putLong("count", 1);
            inventory.add(extraEntry);
            var tag = new CompoundTag();
            tag.put("inventory", inventory);
            core.loadAdditional(tag, level.registryAccess());

            long matching = core.countMatching(stack -> stack.is(Items.DIAMOND_SWORD));
            if (matching != Long.MAX_VALUE) {
                helper.fail("Fuzzy variant count must saturate at Long.MAX_VALUE, got " + matching);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void duplicate_nbt_entries_merge_without_loss_or_overflow(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            ItemStack stone = new ItemStack(Items.STONE);
            var inventory = new ListTag();
            for (long count : new long[]{5, 7}) {
                var entry = new CompoundTag();
                entry.put("item", stone.save(level.registryAccess()));
                entry.putLong("count", count);
                inventory.add(entry);
            }
            var tag = new CompoundTag();
            tag.put("inventory", inventory);
            core.loadAdditional(tag, level.registryAccess());
            if (core.getItemCount(ItemKey.of(stone)) != 12) {
                helper.fail("Duplicate item entries should merge to 12 instead of overwriting");
                return;
            }

            inventory = new ListTag();
            for (long count : new long[]{Long.MAX_VALUE - 4, 10}) {
                var entry = new CompoundTag();
                entry.put("item", stone.save(level.registryAccess()));
                entry.putLong("count", count);
                inventory.add(entry);
            }
            tag = new CompoundTag();
            tag.put("inventory", inventory);
            core.loadAdditional(tag, level.registryAccess());
            if (core.getItemCount(ItemKey.of(stone)) != Long.MAX_VALUE) {
                helper.fail("Duplicate item entries must saturate instead of wrapping");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void installed_machines_preserve_slots_counts_and_components_across_nbt(GameTestHelper helper) {
        var level = helper.getLevel();
        var firstPos = helper.absolutePos(new BlockPos(1, 3, 1));
        var secondPos = helper.absolutePos(new BlockPos(4, 3, 1));
        level.setBlock(firstPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(secondPos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(firstPos) instanceof StorageCoreBlockEntity first)
                    || !(level.getBlockEntity(secondPos) instanceof StorageCoreBlockEntity second)) {
                helper.fail("Core block entities not found");
                return;
            }

            ItemStack namedFurnaces = new ItemStack(Items.FURNACE, 7);
            namedFurnaces.set(DataComponents.CUSTOM_NAME, Component.literal("Arc Furnaces"));
            var machineItems = new ListTag();
            CompoundTag furnaceEntry = new CompoundTag();
            furnaceEntry.putByte("Slot", (byte) 0);
            furnaceEntry = (CompoundTag) namedFurnaces.save(level.registryAccess(), furnaceEntry);
            machineItems.add(furnaceEntry);
            CompoundTag brewingEntry = new CompoundTag();
            brewingEntry.putByte("Slot", (byte) 4);
            brewingEntry = (CompoundTag) new ItemStack(Items.BREWING_STAND, 2)
                    .save(level.registryAccess(), brewingEntry);
            machineItems.add(brewingEntry);
            var machines = new CompoundTag();
            machines.put("Items", machineItems);
            var initial = new CompoundTag();
            initial.put("machines", machines);
            first.loadAdditional(initial, level.registryAccess());

            var saved = new CompoundTag();
            first.saveAdditional(saved, level.registryAccess());
            second.loadAdditional(saved, level.registryAccess());
            var resaved = new CompoundTag();
            second.saveAdditional(resaved, level.registryAccess());
            ListTag roundTrip = resaved.getList("machineDescriptors", Tag.TAG_COMPOUND);
            if (roundTrip.size() != 2) {
                helper.fail("Machine NBT must preserve both occupied slots, got " + roundTrip.size());
                return;
            }

            ItemStack furnace = ItemStack.EMPTY;
            ItemStack brewing = ItemStack.EMPTY;
            for (int i = 0; i < roundTrip.size(); i++) {
                CompoundTag entry = roundTrip.getCompound(i);
                String descriptorId = entry.getString("descriptorId");
                ItemStack stack = ItemStack.parse(
                        level.registryAccess(), entry.getCompound("item")).orElse(ItemStack.EMPTY);
                if (descriptorId.equals(MachineEnergyTable.FURNACE_ID.toString())) furnace = stack;
                if (descriptorId.equals(MachineEnergyTable.BREWING_STAND_ID.toString())) brewing = stack;
            }
            if (!ItemStack.isSameItemSameComponents(furnace, namedFurnaces) || furnace.getCount() != 7) {
                helper.fail("Furnace machine stack lost count or components: " + furnace);
                return;
            }
            if (!brewing.is(Items.BREWING_STAND) || brewing.getCount() != 2) {
                helper.fail("Brewing Stand machine stack did not survive its exact slot: " + brewing);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void finite_axe_energy_survives_nbt_round_trip(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var source = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        axe.setDamageValue(axe.getMaxDamage() - 37);
        axe.enchant(registries.registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.UNBREAKING), 1);
        if (!source.addAxeEnergy(axe) || !axe.isEmpty()) {
            helper.fail("Finite axe must be accepted and consumed before persistence");
            return;
        }
        if (source.getAxeEnergy() != 74 || source.hasInfiniteAxeEnergy()) {
            helper.fail("Expected exactly 74 finite Axe Energy before save");
            return;
        }

        var saved = new CompoundTag();
        source.saveAdditional(saved, registries);
        var restored = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        restored.loadAdditional(saved, registries);
        if (restored.getAxeEnergy() != 74) {
            helper.fail("Finite Axe Energy did not survive NBT round-trip: " + restored.getAxeEnergy());
            return;
        }
        if (restored.hasInfiniteAxeEnergy()) {
            helper.fail("Finite Axe Energy round-trip must not enable the infinite flag");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void machine_installations_persist_by_descriptor_id_in_a_fixed_slot_bank(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var source = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        if (source.getMachineContainer().getContainerSize() != MachineDescriptorApi.MAX_DESCRIPTORS) {
            helper.fail("Machine slot bank must stay fixed at the public descriptor cap");
            return;
        }
        source.getMachineContainer().setItem(
                MachineEnergyTable.FURNACE_SLOT, new ItemStack(Items.FURNACE, 3));

        var saved = new CompoundTag();
        source.saveAdditional(saved, registries);
        ListTag descriptors = saved.getList("machineDescriptors", Tag.TAG_COMPOUND);
        if (descriptors.size() != 1
                || !MachineEnergyTable.FURNACE_ID.toString().equals(
                        descriptors.getCompound(0).getString("descriptorId"))) {
            helper.fail("Machine persistence must use the stable descriptor id: " + descriptors);
            return;
        }

        var restored = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        restored.loadAdditional(saved, registries);
        ItemStack installed = restored.getMachineContainer().getItem(MachineEnergyTable.FURNACE_SLOT);
        if (!installed.is(Items.FURNACE) || installed.getCount() != 3) {
            helper.fail("Descriptor-keyed machine did not restore to its current slot: " + installed);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void missing_machine_descriptor_recovers_its_item_into_core_storage(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var entry = new CompoundTag();
        entry.putString("descriptorId", "removed_addon:missing_station");
        entry.put("item", new ItemStack(Items.DIAMOND, 7).save(registries));
        var descriptors = new ListTag();
        descriptors.add(entry);
        var persisted = new CompoundTag();
        persisted.put("machineDescriptors", descriptors);

        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(persisted, registries);
        if (core.getItemCount(ItemKey.of(new ItemStack(Items.DIAMOND))) != 7) {
            helper.fail("Removed descriptor item was not recovered into Core storage");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void unavailable_addon_item_nbt_is_retained_until_addon_returns(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        CompoundTag rawItem = (CompoundTag) new ItemStack(Items.DIAMOND, 7).save(registries);
        rawItem.putString("id", "removed_addon:orphaned_machine_item");
        var entry = new CompoundTag();
        entry.putString("descriptorId", "removed_addon:orphaned_machine");
        entry.put("item", rawItem.copy());
        var descriptors = new ListTag();
        descriptors.add(entry);
        var persisted = new CompoundTag();
        persisted.put("machineDescriptors", descriptors);

        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(persisted, registries);
        var resaved = new CompoundTag();
        core.saveAdditional(resaved, registries);
        ListTag retained = resaved.getList("machineDescriptors", Tag.TAG_COMPOUND);
        if (retained.size() != 1) {
            helper.fail("Unavailable addon item NBT was discarded instead of retained: " + retained);
            return;
        }
        CompoundTag retainedEntry = retained.getCompound(0);
        CompoundTag retainedItem = retainedEntry.getCompound("item");
        if (!"removed_addon:orphaned_machine".equals(retainedEntry.getString("descriptorId"))
                || !"removed_addon:orphaned_machine_item".equals(retainedItem.getString("id"))
                || retainedItem.getInt("count") != 7) {
            helper.fail("Unavailable addon machine entry changed during lossless retention: " + retainedEntry);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void unavailable_addon_inventory_item_nbt_is_retained_until_addon_returns(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        CompoundTag rawItem = (CompoundTag) new ItemStack(Items.DIAMOND).save(registries);
        rawItem.putString("id", "removed_addon:orphaned_storage_item");
        var entry = new CompoundTag();
        entry.put("item", rawItem.copy());
        entry.putLong("count", 73);
        var inventory = new ListTag();
        inventory.add(entry);
        var persisted = new CompoundTag();
        persisted.put("inventory", inventory);

        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(persisted, registries);
        var resaved = new CompoundTag();
        core.saveAdditional(resaved, registries);
        ListTag retained = resaved.getList("inventory", Tag.TAG_COMPOUND);
        if (!core.hasRecoverableContents() || core.getTotalStoredItemCount() != 73
                || retained.size() != 1) {
            helper.fail("Unavailable addon storage item was discarded: " + retained);
            return;
        }
        CompoundTag retainedEntry = retained.getCompound(0);
        if (!"removed_addon:orphaned_storage_item".equals(
                retainedEntry.getCompound("item").getString("id"))
                || retainedEntry.getLong("count") != 73) {
            helper.fail("Unavailable addon storage entry changed during retention: " + retainedEntry);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void descriptor_consumable_state_is_keyed_and_persistent(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var source = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        axe.setDamageValue(axe.getMaxDamage() - 13);
        if (!source.addDescriptorConsumable(MachineEnergyTable.AXE_ID, axe)
                || source.getDescriptorAmount(MachineEnergyTable.AXE_ID) != 13) {
            helper.fail("Descriptor consumable callback was not applied server-side");
            return;
        }

        var saved = new CompoundTag();
        source.saveAdditional(saved, registries);
        var restored = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        restored.loadAdditional(saved, registries);
        if (restored.getDescriptorAmount(MachineEnergyTable.AXE_ID) != 13
                || restored.hasInfiniteDescriptor(MachineEnergyTable.AXE_ID)
                || !restored.consumeDescriptor(MachineEnergyTable.AXE_ID, 5)
                || restored.getDescriptorAmount(MachineEnergyTable.AXE_ID) != 8) {
            helper.fail("Descriptor consumable state did not persist or consume by stable id");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void infinite_axe_energy_flag_survives_nbt_round_trip(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var source = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        ItemStack axe = new ItemStack(Items.NETHERITE_AXE);
        axe.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        if (!source.addAxeEnergy(axe) || !axe.isEmpty()) {
            helper.fail("Unbreakable axe must be accepted and consumed before persistence");
            return;
        }
        if (!source.hasInfiniteAxeEnergy() || source.getAxeEnergy() != 0) {
            helper.fail("Infinite Axe Energy must use an explicit flag instead of a finite sentinel");
            return;
        }

        var saved = new CompoundTag();
        source.saveAdditional(saved, registries);
        var restored = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        restored.loadAdditional(saved, registries);
        if (!restored.hasInfiniteAxeEnergy()) {
            helper.fail("Infinite Axe Energy flag did not survive NBT round-trip");
            return;
        }
        if (restored.getAxeEnergy() != 0) {
            helper.fail("Infinite Axe Energy round-trip must not use Long.MAX_VALUE or another finite sentinel");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void legacy_slot_eight_finite_axe_migrates_and_clears_after_success(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        ItemStack legacyAxe = new ItemStack(Items.IRON_AXE);
        legacyAxe.setDamageValue(legacyAxe.getMaxDamage() - 9);
        legacyAxe.enchant(registries.registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.UNBREAKING), 2);
        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(legacyMachineTag(registries, legacyAxe), registries);

        if (core.getAxeEnergy() != 27 || core.hasInfiniteAxeEnergy()) {
            helper.fail("Legacy finite axe must migrate to exactly 27 finite Axe Energy");
            return;
        }
        if (!core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT).isEmpty()) {
            helper.fail("Legacy slot 8 axe must clear after successful finite migration");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void legacy_slot_eight_overflow_preserves_axe_and_existing_energy(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        ItemStack legacyAxe = new ItemStack(Items.IRON_AXE);
        legacyAxe.setDamageValue(legacyAxe.getMaxDamage() - 2);
        CompoundTag persisted = legacyMachineTag(registries, legacyAxe);
        persisted.putLong("axeEnergy", Long.MAX_VALUE - 1);
        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(persisted, registries);

        if (core.getAxeEnergy() != Long.MAX_VALUE - 1 || core.hasInfiniteAxeEnergy()) {
            helper.fail("Overflow migration must preserve the existing finite Axe Energy");
            return;
        }
        ItemStack retained = core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT);
        if (retained.getCount() != 1 || !ItemStack.isSameItemSameComponents(retained, legacyAxe)) {
            helper.fail("Overflow migration must leave the complete legacy slot 8 axe untouched: " + retained);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void legacy_slot_eight_unbreakable_axe_migrates_to_infinite(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        ItemStack legacyAxe = new ItemStack(Items.DIAMOND_AXE);
        legacyAxe.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(legacyMachineTag(registries, legacyAxe), registries);

        if (!core.hasInfiniteAxeEnergy() || core.getAxeEnergy() != 0) {
            helper.fail("Legacy Unbreakable axe must migrate to explicit infinite Axe Energy");
            return;
        }
        if (!core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT).isEmpty()) {
            helper.fail("Legacy slot 8 axe must clear after successful infinite migration");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void stacked_legacy_instant_station_keeps_one_and_recovers_extras(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        ItemStack legacyStations = new ItemStack(Items.CRAFTING_TABLE, 3);
        var machineItems = new ListTag();
        var stationEntry = new CompoundTag();
        stationEntry.putByte("Slot", (byte) MachineEnergyTable.CRAFTING_TABLE_SLOT);
        machineItems.add(legacyStations.save(registries, stationEntry));
        var machines = new CompoundTag();
        machines.put("Items", machineItems);
        var persisted = new CompoundTag();
        persisted.put("machines", machines);
        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(persisted, registries);

        ItemStack installed = core.getMachineContainer().getItem(MachineEnergyTable.CRAFTING_TABLE_SLOT);
        if (!installed.is(Items.CRAFTING_TABLE) || installed.getCount() != 1) {
            helper.fail("Legacy instant station must normalize to exactly one installed item: " + installed);
            return;
        }
        if (core.getItemCount(ItemKey.of(new ItemStack(Items.CRAFTING_TABLE))) != 2) {
            helper.fail("Legacy instant-station extras must remain recoverable in Core storage");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void legacy_bottle_fuel_migrates_to_plain_glass_bottles_once(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var inventory = new ListTag();
        var bottleEntry = new CompoundTag();
        bottleEntry.put("item", new ItemStack(Items.GLASS_BOTTLE).save(registries));
        bottleEntry.putLong("count", 5);
        inventory.add(bottleEntry);
        var energy = new CompoundTag();
        energy.putLong("bottle_fuel", 37);
        energy.putLong(EnergyType.FURNACE_FUEL.getId(), 91);
        var legacy = new CompoundTag();
        legacy.put("energy", energy);
        legacy.put("inventory", inventory);

        var first = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        first.loadAdditional(legacy, registries);
        ItemKey bottles = ItemKey.of(new ItemStack(Items.GLASS_BOTTLE));
        if (first.getItemCount(bottles) != 42) {
            helper.fail("Legacy Bottle Energy must migrate one-for-one to plain Glass Bottles");
            return;
        }
        if (first.getEnergy(EnergyType.FURNACE_FUEL) != 91) {
            helper.fail("Bottle migration must preserve unrelated energy pools");
            return;
        }

        var saved = new CompoundTag();
        first.saveAdditional(saved, registries);
        if (saved.getCompound("energy").contains("bottle_fuel")) {
            helper.fail("Fully migrated Bottle Energy must not remain in saved NBT");
            return;
        }
        var second = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        second.loadAdditional(saved, registries);
        var resaved = new CompoundTag();
        second.saveAdditional(resaved, registries);
        if (second.getItemCount(bottles) != 42
                || resaved.getCompound("energy").contains("bottle_fuel")) {
            helper.fail("Bottle migration must be idempotent across save and reload");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void legacy_bottle_fuel_overflow_remains_recoverable_until_space_frees(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var inventory = new ListTag();
        var bottleEntry = new CompoundTag();
        bottleEntry.put("item", new ItemStack(Items.GLASS_BOTTLE).save(registries));
        bottleEntry.putLong("count", Long.MAX_VALUE);
        inventory.add(bottleEntry);
        var energy = new CompoundTag();
        energy.putLong("bottle_fuel", 7);
        var legacy = new CompoundTag();
        legacy.put("energy", energy);
        legacy.put("inventory", inventory);

        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(legacy, registries);
        ItemKey bottles = ItemKey.of(new ItemStack(Items.GLASS_BOTTLE));
        var blockedSave = new CompoundTag();
        core.saveAdditional(blockedSave, registries);
        if (core.getItemCount(bottles) != Long.MAX_VALUE
                || blockedSave.getCompound("energy").getLong("bottle_fuel") != 7) {
            helper.fail("Overflowing Bottle migration must preserve all seven legacy units in escrow");
            return;
        }

        ItemStack extracted = core.extractItem(bottles, 7, Action.EXECUTE, Actor.EMPTY);
        var recoveredSave = new CompoundTag();
        core.saveAdditional(recoveredSave, registries);
        if (!extracted.is(Items.GLASS_BOTTLE) || extracted.getCount() != 7
                || core.getItemCount(bottles) != Long.MAX_VALUE
                || recoveredSave.getCompound("energy").contains("bottle_fuel")) {
            helper.fail("Free bottle capacity must recover the complete escrow without clamping or duplication");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void loading_missing_live_energy_clears_previous_values(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var energy = new CompoundTag();
        energy.putLong(EnergyType.FURNACE_FUEL.getId(), 91);
        var populated = new CompoundTag();
        populated.put("energy", energy);
        var core = new StorageCoreBlockEntity(BlockPos.ZERO, MagicStorage.STORAGE_CORE.get().defaultBlockState());
        core.loadAdditional(populated, registries);
        core.loadAdditional(new CompoundTag(), registries);
        if (core.getEnergy(EnergyType.FURNACE_FUEL) != 0) {
            helper.fail("Loading a tag without live energy must not retain stale values");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void energy_at_long_max_does_not_wrap_or_consume_fuel(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            var energy = new CompoundTag();
            energy.putLong(EnergyType.SMELTING_ENERGY.getId(), Long.MAX_VALUE);
            energy.putLong(EnergyType.FURNACE_FUEL.getId(), Long.MAX_VALUE);
            var tag = new CompoundTag();
            tag.put("energy", energy);
            var machineItems = new ListTag();
            CompoundTag furnace = new CompoundTag();
            furnace.putByte("Slot", (byte) 0);
            furnace = (CompoundTag) new ItemStack(Items.FURNACE).save(level.registryAccess(), furnace);
            machineItems.add(furnace);
            var machines = new CompoundTag();
            machines.put("Items", machineItems);
            tag.put("machines", machines);
            core.loadAdditional(tag, level.registryAccess());

            core.tick();
            if (core.getEnergy(EnergyType.SMELTING_ENERGY) != Long.MAX_VALUE) {
                helper.fail("Machine energy wrapped at Long.MAX_VALUE: " + core.getEnergy(EnergyType.SMELTING_ENERGY));
                return;
            }
            var coal = new ItemStack(Items.COAL, 1);
            if (core.addFuel(coal, EnergyType.FURNACE_FUEL)) {
                helper.fail("Full fuel pool must reject additional fuel");
                return;
            }
            if (coal.getCount() != 1) { helper.fail("Rejected fuel must not be consumed"); return; }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != Long.MAX_VALUE) {
                helper.fail("Fuel pool wrapped at Long.MAX_VALUE: " + core.getEnergy(EnergyType.FURNACE_FUEL));
                return;
            }
            helper.succeed();
        });
    }

    private static CompoundTag legacyMachineTag(
            net.minecraft.core.HolderLookup.Provider registries,
            ItemStack legacyAxe
    ) {
        var machineItems = new ListTag();
        var axeEntry = new CompoundTag();
        axeEntry.putByte("Slot", (byte) MachineEnergyTable.AXE_SLOT);
        machineItems.add(legacyAxe.save(registries, axeEntry));
        var machines = new CompoundTag();
        machines.put("Items", machineItems);
        var tag = new CompoundTag();
        tag.put("machines", machines);
        return tag;
    }

    private static CompoundTag unresolvedInventoryEntry(
            net.minecraft.core.HolderLookup.Provider registries,
            String itemId,
            long count
    ) {
        CompoundTag rawItem = (CompoundTag) new ItemStack(Items.PAPER).save(registries);
        rawItem.putString("id", itemId);
        CompoundTag entry = new CompoundTag();
        entry.put("item", rawItem);
        entry.putLong("count", count);
        return entry;
    }

    private static CompoundTag unresolvedMachineEntry(
            net.minecraft.core.HolderLookup.Provider registries,
            String descriptorId,
            String itemId,
            int count
    ) {
        CompoundTag rawItem = (CompoundTag) new ItemStack(Items.FURNACE, count).save(registries);
        rawItem.putString("id", itemId);
        CompoundTag entry = new CompoundTag();
        entry.putString("descriptorId", descriptorId);
        entry.put("item", rawItem);
        return entry;
    }
}
