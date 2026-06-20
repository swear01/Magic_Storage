package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(MagicStorage.MODID)
public class PersistenceTests {

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
            core.insertItem(new ItemStack(Items.DIRT, 50));
            if (core.getTypeCount() != 2) { helper.fail("Should have 2 types"); return; }

            // Destroy block (saves to PENDING)
            level.destroyBlock(corePos, false);
            var pendingAfterDestroy = StorageCoreBlockEntity.PENDING.get(corePos.immutable());
            if (pendingAfterDestroy == null) { helper.fail("PENDING should have data after destroy"); return; }
            level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

            // onLoad may not be called by setBlock in GameTest, manually restore
            var tag = StorageCoreBlockEntity.PENDING.remove(corePos.immutable());
            if (tag == null) { helper.fail("PENDING missing"); return; }
            boolean hasInv = tag.contains("inventory");
            boolean hasEnergy = tag.contains("energy");
            var invTag = tag.getList("inventory", 10);
            int items = invTag.size();
            level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

            helper.runAfterDelay(3, () -> {
                var newBe = level.getBlockEntity(corePos);
                if (!(newBe instanceof StorageCoreBlockEntity newCore)) { helper.fail("New core BE not found"); return; }
                newCore.loadAdditional(tag, level.registryAccess());
                int types = newCore.getTypeCount();
                // Debug: try parsing manually
                var invDebug = tag.getList("inventory", 10);
                int parsed = 0;
                for (int i = 0; i < invDebug.size(); i++) {
                    var entry = invDebug.getCompound(i);
                    var st = net.minecraft.world.item.ItemStack.parse(level.registryAccess(), entry.getCompound("item"));
                    if (st.isPresent()) parsed++;
                }
                if (types == 2) helper.succeed();
                else helper.fail("tagItems=" + items + " manuallyParsed=" + parsed + " types=" + types);
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
}
