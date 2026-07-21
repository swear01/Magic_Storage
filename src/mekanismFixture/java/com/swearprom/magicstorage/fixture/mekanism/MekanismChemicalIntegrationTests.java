package com.swearprom.magicstorage.fixture.mekanism;

import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.StorageCoreBlockEntity;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
}
