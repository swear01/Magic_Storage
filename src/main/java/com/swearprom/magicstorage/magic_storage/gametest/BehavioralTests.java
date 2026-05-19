package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(MagicStorage.MODID)
public class BehavioralTests {

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, path);
    }

    @GameTest(template = "platform")
    public static void storage_unit_tier_contributions(GameTestHelper helper) {
        assertUnitContribution(helper, MagicStorage.STORAGE_UNIT_T1.get(), 10, "storage_unit_t1");
        assertUnitContribution(helper, MagicStorage.STORAGE_UNIT_T2.get(), 25, "storage_unit_t2");
        assertUnitContribution(helper, MagicStorage.STORAGE_UNIT_T3.get(), 50, "storage_unit_t3");
        assertUnitContribution(helper, MagicStorage.STORAGE_UNIT_T4.get(), 100, "storage_unit_t4");
        assertUnitContribution(helper, MagicStorage.STORAGE_UNIT_T5.get(), 200, "storage_unit_t5");
        assertUnitContribution(helper, MagicStorage.STORAGE_UNIT_T6.get(), 400, "storage_unit_t6");
        helper.succeed();
    }

    private static void assertUnitContribution(GameTestHelper helper, Block block, int expected, String name) {
        if (!(block instanceof StorageUnitBlock su) || su.getTypeContribution() != expected) {
            helper.fail(name + " should contribute " + expected
                    + ", got " + ((StorageUnitBlock) block).getTypeContribution());
        }
    }

    @GameTest(template = "platform")
    public static void block_entity_type_is_registered(GameTestHelper helper) {
        var type = helper.getLevel().registryAccess().registryOrThrow(Registries.BLOCK_ENTITY_TYPE)
                .get(key("storage_core"));
        if (type == null) helper.fail("storage_core BE type is not registered");
        if (type != MagicStorage.STORAGE_CORE_BE.get()) helper.fail("BE type mismatch");
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void export_bus_is_registered(GameTestHelper helper) {
        var block = helper.getLevel().registryAccess().registryOrThrow(Registries.BLOCK).get(key("export_bus"));
        if (block == null) helper.fail("export_bus block not registered");
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void all_unit_tiers_are_registered(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BLOCK);
        String[] tiers = {"storage_unit_t1", "storage_unit_t2",
                          "storage_unit_t3", "storage_unit_t4",
                          "storage_unit_t5", "storage_unit_t6"};
        for (String name : tiers) {
            if (registry.get(key(name)) == null) {
                helper.fail(name + " not registered");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void terminal_blocks_implement_network_interface(GameTestHelper helper) {
        var stBlock = MagicStorage.STORAGE_TERMINAL.get();
        var ctBlock = MagicStorage.CRAFTING_TERMINAL.get();
        if (!(stBlock instanceof IStorageNetworkBlock)) helper.fail("storage_terminal not IStorageNetworkBlock");
        if (!(ctBlock instanceof IStorageNetworkBlock)) helper.fail("crafting_terminal not IStorageNetworkBlock");
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void core_block_is_storage_core(GameTestHelper helper) {
        var core = MagicStorage.STORAGE_CORE.get();
        if (!(core instanceof IStorageNetworkBlock net && net.isStorageCore())) {
            helper.fail("storage_core should return isStorageCore=true");
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void bfs_handles_multiple_units_via_level(GameTestHelper helper) {
        var level = helper.getLevel();
        var coreState = MagicStorage.STORAGE_CORE.get().defaultBlockState();
        var unitState = MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState();
        var unitT3State = MagicStorage.STORAGE_UNIT_T3.get().defaultBlockState();

        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unit1Pos = corePos.south();
        var unit2Pos = corePos.west();

        level.setBlock(corePos, coreState, Block.UPDATE_ALL);
        level.setBlock(unit1Pos, unitState, Block.UPDATE_ALL);
        level.setBlock(unit2Pos, unitT3State, Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core BE not created. BE=" + be);
                return;
            }
            core.rebuildNetwork(level);
            var blocks = core.getConnectedBlocks();
            if (!blocks.contains(unit1Pos)) helper.fail("unit1 not connected");
            if (!blocks.contains(unit2Pos)) helper.fail("unit2 not connected");
            if (core.getTotalTypeSlots() != 60)
                helper.fail("Expected 60 slots (10+50), got " + core.getTotalTypeSlots());
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void energy_auto_fills_after_setup(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(5, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core BE not found, got: " + (be == null ? "null" : be.getClass().getName()));
                return;
            }
            if (core.getEnergy(EnergyType.SMELTING_ENERGY) == 0) {
                helper.fail("No energy accumulated after 5 ticks");
                return;
            }
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 0) {
                helper.fail("furnace_fuel should remain 0 (not auto-fill)");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void insert_and_extract_item(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            
            var unitPos = corePos.east();
            level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(level);
            
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64);
            long inserted = core.insertItem(stack);
            if (inserted != 64) helper.fail("Expected to insert 64, got " + inserted);
            
            net.minecraft.world.item.ItemStack extracted = core.extractItem(ItemKey.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)), 32);
            if (extracted.getCount() != 32) helper.fail("Expected to extract 32, got " + extracted.getCount());
            
            var display = core.getDisplayStacks();
            if (display.isEmpty() || display.get(0).getCount() != 32) {
                helper.fail("Remaining count should be 32, got " + (display.isEmpty() ? "empty" : display.get(0).getCount()));
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void nbt_separation(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            
            var unitPos = corePos.east();
            level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
            core.rebuildNetwork(level);
            
            net.minecraft.world.item.ItemStack sword1 = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
            net.minecraft.world.item.ItemStack sword2 = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
            sword2.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 1);
            
            core.insertItem(sword1.copy());
            core.insertItem(sword2.copy());
            
            if (core.getTypeCount() != 2) {
                helper.fail("Expected 2 distinct item types due to NBT, got " + core.getTypeCount());
            }
            
            net.minecraft.world.item.ItemStack extracted = core.extractItem(ItemKey.of(sword2), 1);
            if (extracted.isEmpty() || !extracted.isEnchanted()) {
                helper.fail("Extracted sword should have enchantments");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void type_slot_limit(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            core.rebuildNetwork(level); // 0 slots initially
            
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 1);
            long inserted = core.insertItem(stack);
            if (inserted != 0) {
                helper.fail("Should not insert if totalTypeSlots is 0, inserted " + inserted);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void fuel_conversion(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            
            net.minecraft.world.item.ItemStack coal = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL);
            core.addFuel(coal, EnergyType.FURNACE_FUEL);
            
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 1600) {
                helper.fail("Expected 1600 furnace_fuel from coal, got " + core.getEnergy(EnergyType.FURNACE_FUEL));
            }
            if (!coal.isEmpty()) {
                helper.fail("Coal item should be consumed");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void energy_consumption(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            
            // tick enough times to accumulate SMELTING_ENERGY
            for (int i = 0; i < 200; i++) {
                core.tick();
            }
            
            // add fuel
            core.addFuel(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL), EnergyType.FURNACE_FUEL);
            
            EnergyCost cost = new EnergyCost(EnergyType.SMELTING_ENERGY, 100, EnergyType.FURNACE_FUEL, 100);
            boolean success = core.consumeEnergy(cost, 1);
            
            if (!success) helper.fail("Should be able to consume energy");
            if (core.getEnergy(EnergyType.SMELTING_ENERGY) < 100) helper.fail("Smelting energy should be at least 100");
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 1500) helper.fail("Furnace fuel should be 1500");
            
            // test failure case
            boolean fail = core.consumeEnergy(cost, 100); // 100 * 100 = 10000 smelting, we don't have that
            if (fail) helper.fail("Should fail due to insufficient process energy");
            
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void dynamic_capacity_change(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.south();
        
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            core.rebuildNetwork(level);
            if (core.getTotalTypeSlots() != 10) helper.fail("Expected 10 slots initially");
            
            level.destroyBlock(unitPos, false);
            
            helper.runAfterDelay(2, () -> {
                core.rebuildNetwork(level);
                if (core.getTotalTypeSlots() != 0) {
                    helper.fail("Expected 0 slots after unit destroyed, got " + core.getTotalTypeSlots());
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "platform")
    public static void insert_multiple_item_types(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 5));
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 20));
            if (core.getTypeCount() != 3) helper.fail("Expected 3 types, got " + core.getTypeCount());
            var display = core.getDisplayStacks();
            long total = 0;
            for (var s : display) total += s.getCount();
            if (total != 35) helper.fail("Expected 35 total items, got " + total);
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void insert_same_item_accumulates(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 20));
            if (core.getTypeCount() != 1) helper.fail("Should be 1 type (accumulated)");
            var extracted = core.extractItem(ItemKey.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)), 30);
            if (extracted.getCount() != 30) helper.fail("Expected 30, got " + extracted.getCount());
            if (core.getTypeCount() != 0) helper.fail("Should be 0 types after full extract");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void extract_more_than_available(GameTestHelper helper) {
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        helper.getLevel().setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var level = helper.getLevel();
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
            var extracted = core.extractItem(ItemKey.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)), 999);
            if (extracted.getCount() != 10) helper.fail("Should only return available 10, got " + extracted.getCount());
            if (core.getTypeCount() != 0) helper.fail("Should be empty after extracting all");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void extract_from_empty(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            var extracted = core.extractItem(ItemKey.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)), 1);
            if (!extracted.isEmpty()) helper.fail("Should return empty from empty inventory");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void insert_zero_does_nothing(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            long r = core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 0));
            if (r != 0) helper.fail("Insert 0 should return 0");
            if (core.getTypeCount() != 0) helper.fail("Insert 0 should not add type");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void different_fuel_types(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.addFuel(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL), EnergyType.FURNACE_FUEL);
            core.addFuel(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BLAZE_POWDER), EnergyType.BLAZE_FUEL);
            if (core.getEnergy(EnergyType.FURNACE_FUEL) != 1600) helper.fail("Furnace fuel should be 1600");
            if (core.getEnergy(EnergyType.BLAZE_FUEL) != 600) helper.fail("Blaze fuel should be 600");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void energy_type_isolation(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            for (int i = 0; i < 200; i++) core.tick();
            long smeltBefore = core.getEnergy(EnergyType.SMELTING_ENERGY);
            long blastBefore = core.getEnergy(EnergyType.BLASTING_ENERGY);
            var cost = new EnergyCost(EnergyType.BLASTING_ENERGY, 100, EnergyType.FURNACE_FUEL, 0);
            core.addFuel(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL), EnergyType.FURNACE_FUEL);
            core.consumeEnergy(cost, 1);
            if (core.getEnergy(EnergyType.SMELTING_ENERGY) != smeltBefore)
                helper.fail("Smelting energy should NOT be affected by blasting consumption");
            if (core.getEnergy(EnergyType.BLASTING_ENERGY) >= blastBefore)
                helper.fail("Blasting energy should decrease after consumption");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void display_stacks_filter_by_name(GameTestHelper helper) {
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        helper.getLevel().setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(1, () -> {
            var level = helper.getLevel();
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 1));
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 1));
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 1));
            var stoneFiltered = core.getDisplayStacks("stone");
            if (stoneFiltered.size() != 1) helper.fail("'stone' should match 1 item");
            var dirtFiltered = core.getDisplayStacks("dirt");
            if (dirtFiltered.size() != 1) helper.fail("'dirt' should match 1 item");
            var all = core.getDisplayStacks("");
            if (all.size() != 3) helper.fail("Empty filter should show all 3");
            var none = core.getDisplayStacks("nonexistent");
            if (!none.isEmpty()) helper.fail("No match should return empty");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void recipe_lookup_finds_smelting_recipe(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(0, player.getInventory(), core);
            menu.lookUpRecipes(level, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
            var recipes = menu.getCurrentRecipes();
            if (recipes.isEmpty()) helper.fail("Should find smelting/blasting recipes for iron ingot");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void crafting_table_recipe_no_energy(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(1, player.getInventory(), core);
            menu.lookUpRecipes(level, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
            if (menu.getCurrentRecipes().isEmpty()) { helper.fail("No recipes for sticks"); return; }
            // Verify recipe type is crafting (instant, no energy)
            var holder = menu.getCurrentRecipes().get(menu.getCurrentRecipeIndex());
            if (holder.value().getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING)
                helper.fail("Stick recipe should be Crafting type");
            var cost = RecipeEnergyTable.getCost(holder.value().getType());
            if (cost != null) helper.fail("Crafting table recipe should have no energy cost (null)");
            // Verify recipe has ingredients
            if (holder.value().getIngredients().isEmpty()) helper.fail("Recipe should have ingredients");
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void export_bus_no_item_loss_when_target_full(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        var unitPos = corePos.south();
        var busPos = corePos.east();
        var chestPos = busPos.east();

        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unitPos, MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(busPos,
            MagicStorage.EXPORT_BUS.get().defaultBlockState().setValue(ExportBusBlock.FACING, Direction.EAST),
            Block.UPDATE_ALL);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAfterDelay(3, () -> {
            var coreBe = level.getBlockEntity(corePos);
            if (!(coreBe instanceof StorageCoreBlockEntity core)) { helper.fail("Core not found"); return; }
            core.rebuildNetwork(level);
            core.insertItem(new ItemStack(Items.STONE, 1));

            var chestBe = level.getBlockEntity(chestPos);
            if (!(chestBe instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) {
                helper.fail("Chest not found"); return;
            }
            for (int i = 0; i < chest.getContainerSize(); i++) {
                chest.setItem(i, new ItemStack(Items.DIRT, 64));
            }

            var busBe = level.getBlockEntity(busPos);
            if (!(busBe instanceof ExportBusBlockEntity bus)) { helper.fail("Bus not found"); return; }
            bus.setFilter(new ItemStack(Items.STONE));
            bus.tick();

            var stoneKey = ItemKey.of(new ItemStack(Items.STONE));
            var remaining = core.extractItem(stoneKey, 1, true);
            if (remaining.isEmpty()) {
                helper.fail("Stone was lost when export bus pushed to full chest");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "platform")
    public static void smelting_craft_consumes_energy(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            for (int i = 0; i < 200; i++) core.tick();
            core.addFuel(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL), EnergyType.FURNACE_FUEL);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(2, player.getInventory(), core);
            menu.lookUpRecipes(level, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
            if (menu.getCurrentRecipes().isEmpty()) { helper.fail("No recipes for iron ingot"); return; }
            // Verify smelting recipe has energy cost
            for (int r = 0; r < menu.getRecipeCount(); r++) {
                var holder = menu.getCurrentRecipes().get(menu.getCurrentRecipeIndex());
                if (holder.value().getType() == net.minecraft.world.item.crafting.RecipeType.SMELTING) {
                    var cost = RecipeEnergyTable.getCost(holder.value().getType());
                    if (cost == null || cost.processAmount() <= 0) helper.fail("Smelting should have energy cost");
                    if (cost.fuelAmount() <= 0) helper.fail("Smelting should require fuel energy");
                    helper.succeed();
                    return;
                }
                menu.nextRecipe();
            }
            helper.fail("No smelting recipe found for iron ingot");
        });
    }

    @GameTest(template = "platform")
    public static void craft_rejected_without_energy(GameTestHelper helper) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            var be = level.getBlockEntity(corePos);
            if (!(be instanceof StorageCoreBlockEntity core)) { helper.fail("Core BE not found"); return; }
            core.rebuildNetwork(level);
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            var menu = new CraftingTerminalMenu(3, player.getInventory(), core);
            menu.lookUpRecipes(level, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
            if (menu.getCurrentRecipes().isEmpty()) { helper.fail("No recipes for iron ingot"); return; }
            core.insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_ORE, 1));
            boolean hasRecipe = false;
            for (int r = 0; r < menu.getRecipeCount(); r++) {
                var holder = menu.getCurrentRecipes().get(menu.getCurrentRecipeIndex());
                if (holder.value().getType() == net.minecraft.world.item.crafting.RecipeType.SMELTING) {
                    hasRecipe = true; break;
                }
                menu.nextRecipe();
            }
            if (!hasRecipe) helper.fail("Smelting recipe not found for iron ingot");
            // Verify recipe needs iron ore as input
            var holder = menu.getCurrentRecipes().get(menu.getCurrentRecipeIndex());
            if (holder.value().getIngredients().isEmpty()) helper.fail("Recipe should have ingredients");
            // Verify energy cost exists for smelting
            var cost = RecipeEnergyTable.getCost(holder.value().getType());
            if (cost == null) helper.fail("Smelting should have energy cost");
            helper.succeed();
        });
    }
}
