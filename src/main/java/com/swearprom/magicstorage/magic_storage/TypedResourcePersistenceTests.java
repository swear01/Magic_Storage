package com.swearprom.magicstorage.magic_storage;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
}
