package com.swearprom.magicstorage.magic_storage.gametest;

import com.swearprom.magicstorage.magic_storage.BusActor;
import com.swearprom.magicstorage.magic_storage.BusConfiguration;
import com.swearprom.magicstorage.magic_storage.BusFilterMode;
import com.swearprom.magicstorage.magic_storage.BusFilterPolicy;
import com.swearprom.magicstorage.magic_storage.BusFilterRule;
import com.swearprom.magicstorage.magic_storage.BusKind;
import com.swearprom.magicstorage.magic_storage.BusMode;
import com.swearprom.magicstorage.magic_storage.BusOperationDirection;
import com.swearprom.magicstorage.magic_storage.BusOperationId;
import com.swearprom.magicstorage.magic_storage.ExportBusBlock;
import com.swearprom.magicstorage.magic_storage.ExportBusBlockEntity;
import com.swearprom.magicstorage.magic_storage.ImportBusBlock;
import com.swearprom.magicstorage.magic_storage.ImportBusBlockEntity;
import com.swearprom.magicstorage.magic_storage.ItemKey;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.StorageCoreBlockEntity;
import com.swearprom.magicstorage.magic_storage.StorageResourceCapabilities;
import com.swearprom.magicstorage.magic_storage.StorageResourceHandler;
import com.swearprom.magicstorage.magic_storage.StorageResourceKey;
import com.swearprom.magicstorage.magic_storage.StorageResourceKindApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
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

    @GameTest(template = "behavioraltests.platform")
    public static void allow_and_deny_empty_filters_have_opposite_semantics(GameTestHelper helper) {
        ItemKey stone = ItemKey.of(new ItemStack(Items.STONE));
        BusFilterPolicy allow = BusFilterPolicy.compile(
                filterConfig(BusFilterMode.ALLOW, List.of()), helper.getLevel().registryAccess());
        BusFilterPolicy deny = BusFilterPolicy.compile(
                filterConfig(BusFilterMode.DENY, List.of()), helper.getLevel().registryAccess());
        if (allow.allows(stone) || !deny.allows(stone)) {
            helper.fail("Empty Allow must match nothing and empty Deny must match everything");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void unsupported_filter_policy_never_allows_candidates(GameTestHelper helper) {
        CompoundTag raw = new CompoundTag();
        raw.putInt("schema", 99);
        CompoundTag root = new CompoundTag();
        root.put(BusConfiguration.TAG_BUS_CONFIG, raw);
        BusConfiguration config = BusConfiguration.load(
                root, BusKind.IMPORT, helper.getLevel().registryAccess());
        BusFilterPolicy policy = BusFilterPolicy.compile(config, helper.getLevel().registryAccess());
        ItemKey stone = ItemKey.of(new ItemStack(Items.STONE));
        if (policy.allows(stone) || !policy.orderedCandidates(List.of(stone)).isEmpty()) {
            helper.fail("Unsupported bus filter policy must fail closed");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void every_filter_rule_type_uses_its_exact_identity_contract(GameTestHelper helper) {
        ItemStack namedDiamond = new ItemStack(Items.DIAMOND);
        namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        ItemKey namedKey = ItemKey.of(namedDiamond);
        ItemKey plainDiamond = ItemKey.of(new ItemStack(Items.DIAMOND));
        ItemKey oakPlanks = ItemKey.of(new ItemStack(Items.OAK_PLANKS));
        ItemKey dirt = ItemKey.of(new ItemStack(Items.DIRT));
        var registries = helper.getLevel().registryAccess();

        BusFilterPolicy exact = BusFilterPolicy.compile(filterConfig(
                BusFilterMode.ALLOW, List.of(BusFilterRule.exact(namedDiamond))), registries);
        BusFilterPolicy item = BusFilterPolicy.compile(filterConfig(
                BusFilterMode.ALLOW,
                List.of(BusFilterRule.item(ResourceLocation.withDefaultNamespace("diamond")))), registries);
        BusFilterPolicy tag = BusFilterPolicy.compile(filterConfig(
                BusFilterMode.ALLOW,
                List.of(BusFilterRule.tag(ResourceLocation.withDefaultNamespace("planks")))), registries);
        BusFilterPolicy mod = BusFilterPolicy.compile(filterConfig(
                BusFilterMode.ALLOW, List.of(BusFilterRule.mod("minecraft"))), registries);

        if (!exact.allows(namedKey) || exact.allows(plainDiamond)
                || !item.allows(namedKey) || !item.allows(plainDiamond) || item.allows(dirt)
                || !tag.allows(oakPlanks) || tag.allows(dirt)
                || !mod.allows(dirt)) {
            helper.fail("Exact/item/tag/mod filter semantics diverged");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void duplicate_and_unavailable_rules_stay_visible_but_never_match(GameTestHelper helper) {
        CompoundTag configTag = currentConfigTag(BusFilterMode.ALLOW);
        ListTag rules = new ListTag();
        rules.add(itemRule("minecraft:stone"));
        rules.add(itemRule("minecraft:stone"));
        rules.add(itemRule("missing_addon:missing_item"));
        configTag.put("filterRules", rules);
        CompoundTag root = new CompoundTag();
        root.put(BusConfiguration.TAG_BUS_CONFIG, configTag);

        BusConfiguration config = BusConfiguration.load(
                root, BusKind.EXPORT, helper.getLevel().registryAccess());
        BusFilterPolicy policy = BusFilterPolicy.compile(config, helper.getLevel().registryAccess());
        if (!config.supported()
                || policy.ruleSlots().size() != 3
                || policy.ruleSlots().get(0).state() != BusFilterPolicy.RuleState.ACTIVE
                || policy.ruleSlots().get(1).state() != BusFilterPolicy.RuleState.DUPLICATE
                || policy.ruleSlots().get(2).state() != BusFilterPolicy.RuleState.UNAVAILABLE
                || !policy.allows(ItemKey.of(new ItemStack(Items.STONE)))
                || policy.allows(ItemKey.of(new ItemStack(Items.DIRT)))) {
            helper.fail("Duplicate/unavailable filter rules were hidden or became matchable");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void allow_candidates_follow_rule_order_then_canonical_item_identity(GameTestHelper helper) {
        ItemStack stoneB = new ItemStack(Items.STONE);
        stoneB.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
        ItemStack stoneA = new ItemStack(Items.STONE);
        stoneA.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        ItemKey dirt = ItemKey.of(new ItemStack(Items.DIRT));
        ItemKey keyA = ItemKey.of(stoneA);
        ItemKey keyB = ItemKey.of(stoneB);
        BusFilterPolicy policy = BusFilterPolicy.compile(filterConfig(
                BusFilterMode.ALLOW,
                List.of(
                        BusFilterRule.item(ResourceLocation.withDefaultNamespace("dirt")),
                        BusFilterRule.item(ResourceLocation.withDefaultNamespace("stone"))
                )), helper.getLevel().registryAccess());

        List<ItemKey> ordered = policy.orderedCandidates(List.of(keyB, keyA, dirt));
        List<ItemKey> reversed = policy.orderedCandidates(List.of(dirt, keyA, keyB));
        if (!ordered.equals(List.of(dirt, keyA, keyB)) || !reversed.equals(ordered)) {
            helper.fail("Allow candidates ignored rule order or canonical component order: " + ordered);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void deny_candidates_use_global_deterministic_order(GameTestHelper helper) {
        ItemStack stoneB = new ItemStack(Items.STONE);
        stoneB.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
        ItemStack stoneA = new ItemStack(Items.STONE);
        stoneA.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        ItemKey diamond = ItemKey.of(new ItemStack(Items.DIAMOND));
        ItemKey dirt = ItemKey.of(new ItemStack(Items.DIRT));
        ItemKey keyA = ItemKey.of(stoneA);
        ItemKey keyB = ItemKey.of(stoneB);
        BusFilterPolicy policy = BusFilterPolicy.compile(filterConfig(
                BusFilterMode.DENY,
                List.of(BusFilterRule.item(ResourceLocation.withDefaultNamespace("dirt")))),
                helper.getLevel().registryAccess());

        List<ItemKey> ordered = policy.orderedCandidates(List.of(keyB, dirt, keyA, diamond));
        List<ItemKey> reversed = policy.orderedCandidates(List.of(diamond, keyA, dirt, keyB));
        if (!ordered.equals(List.of(diamond, keyA, keyB)) || !reversed.equals(ordered)) {
            helper.fail("Deny candidates were not globally deterministic: " + ordered);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "behavioraltests.platform")
    public static void directional_import_keeps_source_slot_order_while_filtering(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos busPos = corePos.east();
        BlockPos sourcePos = busPos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos, MagicStorage.IMPORT_BUS.get().defaultBlockState()
                .setValue(ImportBusBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(sourcePos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)
                    || !(level.getBlockEntity(busPos) instanceof ImportBusBlockEntity bus)
                    || !(level.getBlockEntity(sourcePos) instanceof BarrelBlockEntity source)) {
                helper.fail("Directional Import fixture is incomplete");
                return;
            }
            core.rebuildNetwork(level);
            BusConfiguration config = BusConfiguration.current(
                    BusMode.DIRECTIONAL,
                    BusConfiguration.ALL_SIDES_MASK,
                    true,
                    true,
                    BusFilterMode.ALLOW,
                    List.of(BusFilterRule.item(ResourceLocation.withDefaultNamespace("stone"))),
                    Optional.empty(),
                    1);
            if (!applyConfiguration(level, busPos, MagicStorage.IMPORT_BUS_BE.get(),
                    MagicStorage.IMPORT_BUS_ITEM.get(), config)) {
                helper.fail("Could not apply Import filter configuration");
                return;
            }
            source.setItem(0, new ItemStack(Items.DIRT, 4));
            source.setItem(1, new ItemStack(Items.STONE, 4));
            source.setChanged();
            bus.tick();
            if (core.getItemCount(ItemKey.of(new ItemStack(Items.DIRT))) != 0
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 4
                    || source.getItem(0).getCount() != 4
                    || !source.getItem(1).isEmpty()) {
                helper.fail("Directional Import did not skip denied slot 0 and transfer allowed slot 1");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void passive_import_filter_matches_simulation_and_preserves_caller_stack(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos busPos = corePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos, MagicStorage.IMPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Passive Import Core is missing");
                return;
            }
            core.rebuildNetwork(level);
            BusConfiguration config = BusConfiguration.current(
                    BusMode.DIRECTIONAL,
                    BusConfiguration.ALL_SIDES_MASK,
                    true,
                    true,
                    BusFilterMode.ALLOW,
                    List.of(BusFilterRule.item(ResourceLocation.withDefaultNamespace("stone"))),
                    Optional.empty(),
                    1);
            if (!applyConfiguration(level, busPos, MagicStorage.IMPORT_BUS_BE.get(),
                    MagicStorage.IMPORT_BUS_ITEM.get(), config)) {
                helper.fail("Could not apply passive Import filter configuration");
                return;
            }
            IItemHandler handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, busPos, Direction.UP);
            if (handler == null) {
                helper.fail("Passive Import capability is missing");
                return;
            }
            ItemStack denied = new ItemStack(Items.DIRT, 5);
            ItemStack deniedRemainder = handler.insertItem(0, denied, false);
            ItemStack allowed = new ItemStack(Items.STONE, 5);
            ItemStack simulatedRemainder = handler.insertItem(0, allowed, true);
            if (denied.getCount() != 5 || allowed.getCount() != 5
                    || deniedRemainder.getCount() != 5 || !simulatedRemainder.isEmpty()
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 0) {
                helper.fail("Passive Import simulation/filter mutated input or Core state");
                return;
            }
            ItemStack committedRemainder = handler.insertItem(0, allowed, false);
            if (!committedRemainder.isEmpty() || allowed.getCount() != 5
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.DIRT))) != 0
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 5) {
                helper.fail("Passive Import execution diverged from filtered simulation");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void directional_export_uses_filter_rule_order_not_core_iteration(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos busPos = corePos.east();
        BlockPos targetPos = busPos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos, MagicStorage.EXPORT_BUS.get().defaultBlockState()
                .setValue(ExportBusBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(targetPos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)
                    || !(level.getBlockEntity(busPos) instanceof ExportBusBlockEntity bus)
                    || !(level.getBlockEntity(targetPos) instanceof BarrelBlockEntity target)) {
                helper.fail("Directional Export fixture is incomplete");
                return;
            }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 2));
            core.insertItem(new ItemStack(Items.DIRT, 2));
            BusConfiguration config = BusConfiguration.current(
                    BusMode.DIRECTIONAL,
                    BusConfiguration.ALL_SIDES_MASK,
                    false,
                    true,
                    BusFilterMode.ALLOW,
                    List.of(
                            BusFilterRule.item(ResourceLocation.withDefaultNamespace("dirt")),
                            BusFilterRule.item(ResourceLocation.withDefaultNamespace("stone"))),
                    Optional.empty(),
                    1);
            if (!applyConfiguration(level, busPos, MagicStorage.EXPORT_BUS_BE.get(),
                    MagicStorage.EXPORT_BUS_ITEM.get(), config)) {
                helper.fail("Could not apply ordered Export filter configuration");
                return;
            }
            bus.tick();
            if (!target.getItem(0).is(Items.DIRT) || target.getItem(0).getCount() != 2
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.DIRT))) != 0
                    || core.getItemCount(ItemKey.of(new ItemStack(Items.STONE))) != 2) {
                helper.fail("Directional Export ignored filter rule order");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void disabled_automation_blocks_active_and_passive_import(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos busPos = corePos.east();
        BlockPos sourcePos = busPos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos, MagicStorage.IMPORT_BUS.get().defaultBlockState()
                .setValue(ImportBusBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(sourcePos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)
                    || !(level.getBlockEntity(busPos) instanceof ImportBusBlockEntity bus)
                    || !(level.getBlockEntity(sourcePos) instanceof BarrelBlockEntity source)) {
                helper.fail("Disabled automation fixture is incomplete");
                return;
            }
            core.rebuildNetwork(level);
            BusConfiguration config = BusConfiguration.current(
                    BusMode.DIRECTIONAL,
                    BusConfiguration.ALL_SIDES_MASK,
                    true,
                    false,
                    BusFilterMode.DENY,
                    List.of(),
                    Optional.empty(),
                    1);
            if (!applyConfiguration(level, busPos, MagicStorage.IMPORT_BUS_BE.get(),
                    MagicStorage.IMPORT_BUS_ITEM.get(), config)) {
                helper.fail("Could not disable Import automation");
                return;
            }
            source.setItem(0, new ItemStack(Items.STONE, 3));
            source.setChanged();
            bus.tick();
            IItemHandler handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, busPos, Direction.UP);
            ItemStack passive = new ItemStack(Items.DIRT, 3);
            ItemStack passiveRemainder = handler == null
                    ? passive.copy()
                    : handler.insertItem(0, passive, false);
            if (source.getItem(0).getCount() != 3 || passiveRemainder.getCount() != 3
                    || core.getTypeCount() != 0) {
                helper.fail("Disabled automation still mutated active or passive Import state");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void passive_import_accepts_typed_resources_through_generic_and_native_capabilities(
            GameTestHelper helper
    ) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos busPos = corePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos, MagicStorage.IMPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Typed Import Core is missing");
                return;
            }
            core.rebuildNetwork(level);
            StorageResourceHandler generic = level.getCapability(
                    StorageResourceCapabilities.BLOCK, busPos, Direction.UP);
            IFluidHandler fluids = level.getCapability(
                    Capabilities.FluidHandler.BLOCK, busPos, Direction.UP);
            var energy = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, busPos, Direction.UP);
            if (generic == null || fluids == null || energy == null) {
                helper.fail("Import Bus did not expose all typed insertion capabilities");
                return;
            }
            StorageResourceKey water = StorageResourceKey.of(
                    StorageResourceKindApi.FLUID_KIND,
                    ResourceLocation.withDefaultNamespace("water"),
                    new CompoundTag());
            if (generic.insert(water, 1_000, true) != 1_000
                    || core.getResourceAmount(water) != 0
                    || generic.insert(water, 1_000, false) != 1_000
                    || core.getResourceAmount(water) != 1_000) {
                helper.fail("Generic Import capability violated simulation or exact insertion");
                return;
            }
            FluidStack namedWater = new FluidStack(
                    net.minecraft.world.level.material.Fluids.WATER, 250);
            namedWater.set(DataComponents.CUSTOM_NAME, Component.literal("Bus Variant"));
            if (fluids.fill(namedWater, IFluidHandler.FluidAction.EXECUTE) != 250
                    || energy.receiveEnergy(600, false) != 600
                    || core.getTypeCount() != 3
                    || !generic.getStoredResources().isEmpty()) {
                helper.fail("Native Import capabilities did not route into the universal ledger");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void passive_export_extracts_typed_resources_without_accepting_insertion(
            GameTestHelper helper
    ) {
        var level = helper.getLevel();
        BlockPos corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos busPos = corePos.east();
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos, MagicStorage.EXPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Typed Export Core is missing");
                return;
            }
            core.rebuildNetwork(level);
            BusConfiguration config = BusConfiguration.current(
                    BusMode.DIRECTIONAL,
                    BusConfiguration.ALL_SIDES_MASK,
                    true,
                    true,
                    BusFilterMode.DENY,
                    List.of(),
                    Optional.empty(),
                    1);
            if (!applyConfiguration(level, busPos, MagicStorage.EXPORT_BUS_BE.get(),
                    MagicStorage.EXPORT_BUS_ITEM.get(), config)) {
                helper.fail("Could not configure typed Export capability");
                return;
            }
            StorageResourceKey water = StorageResourceKey.of(
                    StorageResourceKindApi.FLUID_KIND,
                    ResourceLocation.withDefaultNamespace("water"),
                    new CompoundTag());
            StorageResourceKey energyKey = StorageResourceKey.of(
                    StorageResourceKindApi.ENERGY_KIND,
                    ResourceLocation.fromNamespaceAndPath("neoforge", "energy"),
                    new CompoundTag());
            if (core.insertResource(water, 2_000, com.swearprom.magicstorage.magic_storage.Action.EXECUTE)
                    != 2_000
                    || core.insertResource(energyKey, 800,
                    com.swearprom.magicstorage.magic_storage.Action.EXECUTE) != 800) {
                helper.fail("Could not seed typed Export resources");
                return;
            }
            StorageResourceHandler generic = level.getCapability(
                    StorageResourceCapabilities.BLOCK, busPos, Direction.UP);
            IFluidHandler fluids = level.getCapability(
                    Capabilities.FluidHandler.BLOCK, busPos, Direction.UP);
            var energy = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, busPos, Direction.UP);
            if (generic == null || fluids == null || energy == null) {
                helper.fail("Export Bus did not expose all typed extraction capabilities");
                return;
            }
            if (generic.extract(water, 600, true) != 600
                    || core.getResourceAmount(water) != 2_000
                    || generic.extract(water, 600, false) != 600
                    || generic.insert(water, 1, false) != 0
                    || fluids.drain(400, IFluidHandler.FluidAction.EXECUTE).getAmount() != 400
                    || energy.extractEnergy(300, false) != 300
                    || core.getResourceAmount(water) != 1_000
                    || core.getResourceAmount(energyKey) != 500) {
                helper.fail("Typed Export capability violated extraction direction or exact amounts");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "behavioraltests.platform")
    public static void export_drop_preserves_non_exact_filter_configuration(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos busPos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(busPos, MagicStorage.EXPORT_BUS.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(busPos) instanceof ExportBusBlockEntity bus)) {
            helper.fail("Export Bus is missing for drop configuration test");
            return;
        }
        BusConfiguration config = BusConfiguration.current(
                BusMode.DIRECTIONAL,
                BusConfiguration.ALL_SIDES_MASK,
                false,
                true,
                BusFilterMode.ALLOW,
                List.of(BusFilterRule.item(ResourceLocation.withDefaultNamespace("stone"))),
                Optional.of(UUID.randomUUID()),
                4);
        if (!applyConfiguration(level, busPos, MagicStorage.EXPORT_BUS_BE.get(),
                MagicStorage.EXPORT_BUS_ITEM.get(), config)) {
            helper.fail("Could not apply non-exact Export configuration");
            return;
        }
        ItemStack busDrop = Block.getDrops(level.getBlockState(busPos), level, busPos, bus).stream()
                .filter(stack -> stack.is(MagicStorage.EXPORT_BUS_ITEM.get()))
                .findFirst()
                .orElse(ItemStack.EMPTY);
        if (busDrop.isEmpty() || !busDrop.has(DataComponents.BLOCK_ENTITY_DATA)) {
            helper.fail("Export drop lost non-exact filter configuration");
            return;
        }
        BusConfiguration dropped = BusConfiguration.load(
                busDrop.get(DataComponents.BLOCK_ENTITY_DATA).copyTag(),
                BusKind.EXPORT,
                level.registryAccess());
        if (!dropped.supported() || dropped.owner().isPresent()
                || dropped.filterRules().size() != 1
                || dropped.filterRules().getFirst().type() != BusFilterRule.Type.ITEM
                || !dropped.filterRules().getFirst().id()
                .equals(Optional.of(ResourceLocation.withDefaultNamespace("stone")))) {
            helper.fail("Export drop did not preserve owner-stripped non-exact filter state");
            return;
        }
        helper.succeed();
    }

    private static boolean applyConfiguration(
            net.minecraft.world.level.Level level,
            BlockPos pos,
            BlockEntityType<?> blockEntityType,
            Item blockItem,
            BusConfiguration configuration
    ) {
        CompoundTag tag = new CompoundTag();
        configuration.save(tag, level.registryAccess());
        ItemStack carrier = new ItemStack(blockItem);
        BlockItem.setBlockEntityData(carrier, blockEntityType, tag);
        return BlockItem.updateCustomBlockEntityTag(level, null, pos, carrier);
    }

    private static BusConfiguration filterConfig(BusFilterMode mode, List<BusFilterRule> rules) {
        return BusConfiguration.current(
                BusMode.DIRECTIONAL,
                BusConfiguration.ALL_SIDES_MASK,
                false,
                true,
                mode,
                rules,
                Optional.empty(),
                0
        );
    }

    private static CompoundTag currentConfigTag(BusFilterMode mode) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", 1);
        tag.putString("mode", "directional");
        tag.putInt("sideMask", BusConfiguration.ALL_SIDES_MASK);
        tag.putBoolean("unsidedAccess", false);
        tag.putBoolean("automationEnabled", true);
        tag.putString("filterMode", mode.name().toLowerCase(java.util.Locale.ROOT));
        tag.putLong("configRevision", 0);
        return tag;
    }

    private static CompoundTag itemRule(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "item");
        tag.putString("id", id);
        return tag;
    }
}
