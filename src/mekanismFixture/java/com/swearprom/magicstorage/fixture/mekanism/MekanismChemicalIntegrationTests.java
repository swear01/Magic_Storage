package com.swearprom.magicstorage.fixture.mekanism;

import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.BusConfiguration;
import com.swearprom.magicstorage.magic_storage.BusFilterMode;
import com.swearprom.magicstorage.magic_storage.BusMode;
import com.swearprom.magicstorage.magic_storage.StorageCoreBlockEntity;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

@GameTestHolder(MekanismFixtureMod.MODID)
@PrefixGameTestTemplate(false)
public final class MekanismChemicalIntegrationTests {
    private static final BlockCapability<IChemicalHandler, net.minecraft.core.Direction>
            CHEMICAL_CAPABILITY = BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"),
                    IChemicalHandler.class);

    private MekanismChemicalIntegrationTests() {
    }

    @GameTest(template = "craftingtests.platform")
    public static void core_chemical_capability_preserves_long_amounts_and_simulation(
            GameTestHelper helper
    ) {
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
            IChemicalHandler handler = level.getCapability(CHEMICAL_CAPABILITY, corePos, null);
            if (handler == null) {
                helper.fail("Storage Core did not expose the Mekanism chemical capability");
                return;
            }
            var oxygenHolder = MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceKey.create(
                    MekanismAPI.CHEMICAL_REGISTRY_NAME,
                    ResourceLocation.fromNamespaceAndPath("mekanism", "oxygen"))).orElseThrow();
            ChemicalStack oxygen = new ChemicalStack(oxygenHolder, 5_000_000_000L);

            ChemicalStack simulatedRemainder = handler.insertChemical(oxygen, Action.SIMULATE);
            if (!simulatedRemainder.isEmpty()
                    || core.getTypeCount() != 0
                    || oxygen.getAmount() != 5_000_000_000L) {
                helper.fail("Chemical insertion simulation mutated Core or caller stack");
                return;
            }
            ChemicalStack remainder = handler.insertChemical(oxygen, Action.EXECUTE);
            if (!remainder.isEmpty() || core.getTypeCount() != 1
                    || oxygen.getAmount() != 5_000_000_000L) {
                helper.fail("Chemical insertion did not store the exact long amount");
                return;
            }
            ResourceLocation stableChemicalKind = ResourceLocation.fromNamespaceAndPath(
                    "mekanism", "chemical");
            if (core.getResourceKeys().stream()
                    .noneMatch(key -> key.kindId().equals(stableChemicalKind))) {
                helper.fail("Chemical storage changed its persisted resource-kind identity");
                return;
            }
            if (handler.getChemicalTanks() != 1
                    || handler.getChemicalInTank(0).getAmount() != 5_000_000_000L
                    || !handler.getChemicalInTank(0).is(oxygenHolder)) {
                helper.fail("Chemical tank view lost identity or long amount");
                return;
            }
            ChemicalStack simulatedExtract = handler.extractChemical(
                    oxygen.copyWithAmount(2_000_000_000L), Action.SIMULATE);
            if (simulatedExtract.getAmount() != 2_000_000_000L
                    || handler.getChemicalInTank(0).getAmount() != 5_000_000_000L) {
                helper.fail("Chemical extraction simulation mutated Core");
                return;
            }
            ChemicalStack extracted = handler.extractChemical(
                    oxygen.copyWithAmount(2_000_000_000L), Action.EXECUTE);
            if (extracted.getAmount() != 2_000_000_000L
                    || handler.getChemicalInTank(0).getAmount() != 3_000_000_000L) {
                helper.fail("Chemical extraction execute returned the wrong amount");
                return;
            }
            var hydrogenHolder = MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceKey.create(
                    MekanismAPI.CHEMICAL_REGISTRY_NAME,
                    ResourceLocation.fromNamespaceAndPath("mekanism", "hydrogen"))).orElseThrow();
            ChemicalStack hydrogen = new ChemicalStack(hydrogenHolder, 4_000L);
            ChemicalStack hydrogenRemainder = handler.insertChemical(0, hydrogen, Action.EXECUTE);
            if (!hydrogenRemainder.isEmpty()
                    || hydrogen.getAmount() != 4_000L
                    || core.getTypeCount() != 2
                    || handler.getChemicalTanks() != 2) {
                helper.fail("Explicit-tank insertion did not accept a second chemical type");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void buses_expose_directional_chemical_capabilities(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var importPos = corePos.east();
        var exportPos = corePos.west();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(importPos, MagicStorage.IMPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(exportPos, MagicStorage.EXPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Chemical Bus Core not found");
                return;
            }
            core.rebuildNetwork(level);
            BusConfiguration exportConfig = BusConfiguration.current(
                    BusMode.DIRECTIONAL,
                    BusConfiguration.ALL_SIDES_MASK,
                    true,
                    true,
                    BusFilterMode.DENY,
                    List.of(),
                    Optional.empty(),
                    1);
            CompoundTag configTag = new CompoundTag();
            exportConfig.save(configTag, level.registryAccess());
            ItemStack carrier = new ItemStack(MagicStorage.EXPORT_BUS_ITEM.get());
            BlockItem.setBlockEntityData(carrier, MagicStorage.EXPORT_BUS_BE.get(), configTag);
            if (!BlockItem.updateCustomBlockEntityTag(level, null, exportPos, carrier)) {
                helper.fail("Could not configure chemical Export Bus");
                return;
            }
            IChemicalHandler importer = level.getCapability(CHEMICAL_CAPABILITY, importPos, null);
            IChemicalHandler exporter = level.getCapability(CHEMICAL_CAPABILITY, exportPos, null);
            if (importer == null || exporter == null) {
                helper.fail("Chemical capabilities were missing from buses");
                return;
            }
            var oxygenHolder = MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceKey.create(
                    MekanismAPI.CHEMICAL_REGISTRY_NAME,
                    ResourceLocation.fromNamespaceAndPath("mekanism", "oxygen"))).orElseThrow();
            ChemicalStack oxygen = new ChemicalStack(oxygenHolder, 1_000);
            if (!importer.insertChemical(oxygen, Action.SIMULATE).isEmpty()
                    || core.getTypeCount() != 0
                    || !importer.insertChemical(oxygen, Action.EXECUTE).isEmpty()
                    || !importer.extractChemical(oxygen.copyWithAmount(1), Action.EXECUTE).isEmpty()
                    || exporter.insertChemical(oxygen, Action.EXECUTE).getAmount() != 1_000
                    || exporter.extractChemical(
                    oxygen.copyWithAmount(400), Action.SIMULATE).getAmount() != 400
                    || exporter.extractChemical(
                    oxygen.copyWithAmount(400), Action.EXECUTE).getAmount() != 400
                    || exporter.getChemicalInTank(0).getAmount() != 600) {
                helper.fail("Chemical Bus direction, simulation, or exact amount diverged");
                return;
            }
            helper.succeed();
        });
    }
}
