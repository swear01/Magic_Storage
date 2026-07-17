package com.swearprom.magicstorage.magic_storage.gametest;

import com.swearprom.magicstorage.magic_storage.BusActor;
import com.swearprom.magicstorage.magic_storage.BusConfiguration;
import com.swearprom.magicstorage.magic_storage.BusFilterMode;
import com.swearprom.magicstorage.magic_storage.BusFilterRule;
import com.swearprom.magicstorage.magic_storage.BusKind;
import com.swearprom.magicstorage.magic_storage.BusMode;
import com.swearprom.magicstorage.magic_storage.BusOperationDirection;
import com.swearprom.magicstorage.magic_storage.BusOperationId;
import com.swearprom.magicstorage.magic_storage.ExportBusBlockEntity;
import com.swearprom.magicstorage.magic_storage.ImportBusBlockEntity;
import com.swearprom.magicstorage.magic_storage.ItemKey;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder(MagicStorage.MODID)
@PrefixGameTestTemplate(false)
public final class BusAutomationTests {

    private BusAutomationTests() {
    }

    @GameTest(template = "behavioraltests.platform")
    public static void legacy_import_config_migrates_exact_defaults(GameTestHelper helper) {
        BusConfiguration config = BusConfiguration.load(
                new CompoundTag(), BusKind.IMPORT, helper.getLevel().registryAccess());
        if (!config.supported()
                || config.schema() != 1
                || config.mode() != BusMode.DIRECTIONAL
                || config.sideMask() != BusConfiguration.ALL_SIDES_MASK
                || !config.unsidedAccess()
                || !config.automationEnabled()
                || config.filterMode() != BusFilterMode.DENY
                || !config.filterRules().isEmpty()
                || config.owner().isPresent()
                || config.configRevision() != 0) {
            helper.fail("Legacy Import Bus defaults did not migrate exactly");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void legacy_export_filter_migrates_exact_components(GameTestHelper helper) {
        ItemStack namedDiamond = new ItemStack(Items.DIAMOND);
        namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("exact-filter"));
        CompoundTag root = new CompoundTag();
        root.put("filter", namedDiamond.save(helper.getLevel().registryAccess()));

        BusConfiguration config = BusConfiguration.load(
                root, BusKind.EXPORT, helper.getLevel().registryAccess());
        if (!config.supported()
                || config.mode() != BusMode.DIRECTIONAL
                || config.sideMask() != BusConfiguration.ALL_SIDES_MASK
                || config.unsidedAccess()
                || !config.automationEnabled()
                || config.filterMode() != BusFilterMode.ALLOW
                || config.filterRules().size() != 1
                || config.filterRules().getFirst().type() != BusFilterRule.Type.EXACT_STACK
                || config.filterRules().getFirst().exactKey().isEmpty()
                || !config.filterRules().getFirst().exactKey().orElseThrow().equals(ItemKey.of(namedDiamond))) {
            helper.fail("Legacy Export Bus filter did not migrate as an exact component-sensitive rule");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void current_bus_config_round_trip_preserves_every_field(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ItemStack exact = new ItemStack(Items.NETHERITE_PICKAXE);
        exact.set(DataComponents.CUSTOM_NAME, Component.literal("round-trip"));
        BusConfiguration source = BusConfiguration.current(
                BusMode.DIRECTIONLESS,
                0b100101,
                false,
                false,
                BusFilterMode.ALLOW,
                List.of(
                        BusFilterRule.exact(exact),
                        BusFilterRule.item(ResourceLocation.withDefaultNamespace("diamond")),
                        BusFilterRule.tag(ResourceLocation.withDefaultNamespace("planks")),
                        BusFilterRule.mod("minecraft")
                ),
                Optional.of(owner),
                27
        );
        CompoundTag root = new CompoundTag();
        source.save(root, helper.getLevel().registryAccess());
        BusConfiguration loaded = BusConfiguration.load(
                root, BusKind.IMPORT, helper.getLevel().registryAccess());
        if (!source.equals(loaded)) {
            helper.fail("Current bus configuration did not round-trip exactly");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void unknown_bus_schema_is_preserved_and_disabled(GameTestHelper helper) {
        CompoundTag raw = new CompoundTag();
        raw.putInt("schema", 99);
        raw.putString("futureMode", "quantum");
        CompoundTag root = new CompoundTag();
        root.put(BusConfiguration.TAG_BUS_CONFIG, raw.copy());

        BusConfiguration config = BusConfiguration.load(
                root, BusKind.IMPORT, helper.getLevel().registryAccess());
        CompoundTag written = new CompoundTag();
        config.save(written, helper.getLevel().registryAccess());
        if (config.supported()
                || config.automationEnabled()
                || !written.getCompound(BusConfiguration.TAG_BUS_CONFIG).equals(raw)) {
            helper.fail("Unknown bus schema was not preserved raw and disabled");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void malformed_current_bus_config_is_preserved_and_disabled(GameTestHelper helper) {
        CompoundTag raw = new CompoundTag();
        raw.putInt("schema", 1);
        raw.putString("mode", "sideways");
        raw.putInt("sideMask", BusConfiguration.ALL_SIDES_MASK);
        raw.putBoolean("unsidedAccess", true);
        raw.putBoolean("automationEnabled", true);
        raw.putString("filterMode", "deny");
        raw.putLong("configRevision", 0);
        CompoundTag root = new CompoundTag();
        root.put(BusConfiguration.TAG_BUS_CONFIG, raw.copy());

        BusConfiguration config = BusConfiguration.load(
                root, BusKind.IMPORT, helper.getLevel().registryAccess());
        CompoundTag written = new CompoundTag();
        config.save(written, helper.getLevel().registryAccess());
        if (config.supported()
                || config.automationEnabled()
                || !written.getCompound(BusConfiguration.TAG_BUS_CONFIG).equals(raw)) {
            helper.fail("Malformed current bus config did not fail closed with raw preservation");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void player_placement_captures_bus_owner_without_changing_defaults(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos importPos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos exportPos = helper.absolutePos(new BlockPos(3, 3, 1));
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        level.setBlock(importPos, MagicStorage.IMPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(exportPos, MagicStorage.EXPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);
        MagicStorage.IMPORT_BUS.get().setPlacedBy(
                level, importPos, level.getBlockState(importPos), player, new ItemStack(MagicStorage.IMPORT_BUS_ITEM.get()));
        MagicStorage.EXPORT_BUS.get().setPlacedBy(
                level, exportPos, level.getBlockState(exportPos), player, new ItemStack(MagicStorage.EXPORT_BUS_ITEM.get()));

        if (!(level.getBlockEntity(importPos) instanceof ImportBusBlockEntity importBus)
                || !(level.getBlockEntity(exportPos) instanceof ExportBusBlockEntity exportBus)
                || !importBus.getBusConfiguration().owner().equals(Optional.of(player.getUUID()))
                || !exportBus.getBusConfiguration().owner().equals(Optional.of(player.getUUID()))
                || importBus.getBusConfiguration().filterMode() != BusFilterMode.DENY
                || exportBus.getBusConfiguration().filterMode() != BusFilterMode.ALLOW) {
            helper.fail("Bus placement did not capture owner while preserving kind defaults");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void structured_bus_actor_preserves_complete_operation_identity(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        BlockPos busPos = helper.absolutePos(new BlockPos(1, 3, 1));
        BusOperationId operationId = new BusOperationId(1234, 17);
        BusActor actor = BusActor.owned(
                owner,
                helper.getLevel().dimension(),
                busPos,
                network,
                BusOperationDirection.EXPORT,
                operationId
        );
        BusActor legacy = BusActor.legacyUnclaimed(
                helper.getLevel().dimension(),
                busPos,
                network,
                BusOperationDirection.IMPORT,
                new BusOperationId(1234, 18)
        );
        if (!actor.owner().equals(Optional.of(owner))
                || !actor.dimension().equals(helper.getLevel().dimension())
                || !actor.busPos().equals(busPos)
                || !actor.networkId().equals(network)
                || actor.direction() != BusOperationDirection.EXPORT
                || !actor.operationId().equals(operationId)
                || !legacy.legacyUnclaimed()
                || actor.legacyUnclaimed()
                || actor.name().equals(legacy.name())) {
            helper.fail("Structured bus actor did not preserve complete operation identity");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void config_revision_overflow_is_rejected_without_wrap(GameTestHelper helper) {
        BusConfiguration config = BusConfiguration.current(
                BusMode.DIRECTIONAL,
                BusConfiguration.ALL_SIDES_MASK,
                true,
                true,
                BusFilterMode.DENY,
                List.of(),
                Optional.empty(),
                Long.MAX_VALUE
        );
        try {
            config.nextRevision();
            helper.fail("Bus config revision wrapped past Long.MAX_VALUE");
        } catch (IllegalStateException expected) {
            helper.succeed();
        }
    }
}
