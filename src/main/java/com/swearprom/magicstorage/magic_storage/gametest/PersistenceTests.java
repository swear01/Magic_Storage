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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(MagicStorage.MODID)
public class PersistenceTests {

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
            ListTag roundTrip = resaved.getCompound("machines").getList("Items", Tag.TAG_COMPOUND);
            if (roundTrip.size() != 2) {
                helper.fail("Machine NBT must preserve both occupied slots, got " + roundTrip.size());
                return;
            }

            ItemStack furnace = ItemStack.EMPTY;
            ItemStack brewing = ItemStack.EMPTY;
            for (int i = 0; i < roundTrip.size(); i++) {
                CompoundTag entry = roundTrip.getCompound(i);
                int slot = entry.getByte("Slot") & 255;
                ItemStack stack = ItemStack.parse(level.registryAccess(), entry).orElse(ItemStack.EMPTY);
                if (slot == 0) furnace = stack;
                if (slot == 4) brewing = stack;
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
}
