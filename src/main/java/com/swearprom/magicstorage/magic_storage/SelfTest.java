package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.HashSet;
import java.util.Set;

class SelfTest {
    private static int passed = 0;
    private static int failed = 0;

    static void runAll() {
        testItemKey();
        testFuelTable();
        testRecipeEnergyTable();
        testEnergyType();
        testEnergyCost();
        testFuelValue();
        testEnergyTypeUniqueness();

        MagicStorage.LOGGER.info("SelfTest: {} passed, {} failed, {} total",
                passed, failed, passed + failed);
        if (failed > 0) {
            MagicStorage.LOGGER.error("SelfTest: {} TESTS FAILED!", failed);
        }
    }

    private static void testItemKey() {
        var stack1 = new ItemStack(Items.DIAMOND_SWORD);
        var stack2 = new ItemStack(Items.DIAMOND_SWORD);
        var stack3 = new ItemStack(Items.IRON_SWORD);

        var key1 = ItemKey.of(stack1);
        var key2 = ItemKey.of(stack2);
        var key3 = ItemKey.of(stack3);
        var key1Again = ItemKey.of(stack1);

        assertTrue("same item = same key", key1.equals(key2));
        assertTrue("same item = same hashCode", key1.hashCode() == key2.hashCode());
        assertTrue("different item = different key", !key1.equals(key3));
        assertTrue("key is stable (same stack = same key)", key1.equals(key1Again));
        assertTrue("key = itself", key1.equals(key1));
        assertTrue("key != null", !key1.equals(null));
        assertTrue("primaryKey returns item", key1.item() == Items.DIAMOND_SWORD);
    }

    private static void testEnergyCost() {
        var cost = new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200);
        assertTrue("energyCost processType", cost.processType() == EnergyType.SMELTING_ENERGY);
        assertTrue("energyCost processAmount", cost.processAmount() == 200);
        assertTrue("energyCost fuelType", cost.fuelType() == EnergyType.FURNACE_FUEL);
        assertTrue("energyCost fuelAmount", cost.fuelAmount() == 200);

        var cost2 = new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200);
        assertTrue("energyCost equals", cost.equals(cost2));
        assertTrue("energyCost hashCode", cost.hashCode() == cost2.hashCode());
    }

    private static void testFuelValue() {
        var fv = new FuelValue(EnergyType.FURNACE_FUEL, 1600);
        assertTrue("fuelValue pool", fv.pool() == EnergyType.FURNACE_FUEL);
        assertTrue("fuelValue amount", fv.valuePerItem() == 1600);
    }

    private static void testEnergyTypeUniqueness() {
        Set<String> ids = new HashSet<>();
        for (EnergyType type : EnergyType.values()) {
            assertTrue("unique id: " + type.getId(), ids.add(type.getId()));
        }
    }

    private static void testFuelTable() {
        assertTrue("coal is fuel", FuelTable.isFuel(new ItemStack(Items.COAL)));
        assertTrue("coal gives furnace_fuel", FuelTable.getFuelValues(new ItemStack(Items.COAL))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.FURNACE_FUEL && fv.valuePerItem() == 1600));
        assertTrue("glass_bottle gives bottle_fuel", FuelTable.isFuel(new ItemStack(Items.GLASS_BOTTLE)));
        assertTrue("blaze_rod has two pools", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_ROD)).size() == 2);
        assertTrue("stone is not fuel", !FuelTable.isFuel(new ItemStack(Items.STONE)));
        assertTrue("charcoal is fuel", FuelTable.isFuel(new ItemStack(Items.CHARCOAL)));
        assertTrue("coal block is fuel", FuelTable.isFuel(new ItemStack(Items.COAL_BLOCK)));
        assertTrue("coal block 16000 furnace_fuel", FuelTable.getFuelValues(new ItemStack(Items.COAL_BLOCK))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.FURNACE_FUEL && fv.valuePerItem() == 16000));
        assertTrue("lava bucket 20000 furnace_fuel", FuelTable.getFuelValues(new ItemStack(Items.LAVA_BUCKET))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.FURNACE_FUEL && fv.valuePerItem() == 20000));
        assertTrue("blaze powder 600 blaze_fuel", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_POWDER))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.BLAZE_FUEL && fv.valuePerItem() == 600));
    }

    private static void testRecipeEnergyTable() {
        var smeltCost = RecipeEnergyTable.getCost(RecipeType.SMELTING);
        assertTrue("smelting needs smelting_energy", smeltCost.processType() == EnergyType.SMELTING_ENERGY);
        assertTrue("smelting process cost 200", smeltCost.processAmount() == 200);
        assertTrue("smelting needs furnace_fuel", smeltCost.fuelType() == EnergyType.FURNACE_FUEL);
        assertTrue("smelting fuel cost 200", smeltCost.fuelAmount() == 200);

        var blastCost = RecipeEnergyTable.getCost(RecipeType.BLASTING);
        assertTrue("blasting process cost 100", blastCost.processAmount() == 100);
        assertTrue("blasting uses blast energy", blastCost.processType() == EnergyType.BLASTING_ENERGY);

        var smokeCost = RecipeEnergyTable.getCost(RecipeType.SMOKING);
        assertTrue("smoking uses smoking_energy", smokeCost.processType() == EnergyType.SMOKING_ENERGY);

        var campCost = RecipeEnergyTable.getCost(RecipeType.CAMPFIRE_COOKING);
        assertTrue("campfire process cost 600", campCost.processAmount() == 600);

        assertTrue("crafting cost is null (instant)", RecipeEnergyTable.getCost(RecipeType.CRAFTING) == null);
        assertTrue("stonecutting cost is null (instant)", RecipeEnergyTable.getCost(RecipeType.STONECUTTING) == null);
        assertTrue("smithing cost is null (instant)", RecipeEnergyTable.getCost(RecipeType.SMITHING) == null);
    }

    private static void testEnergyType() {
        assertTrue("smelting auto-fills", EnergyType.SMELTING_ENERGY.isAutoFill());
        assertTrue("blasting auto-fills", EnergyType.BLASTING_ENERGY.isAutoFill());
        assertTrue("furnace_fuel does NOT auto-fill", !EnergyType.FURNACE_FUEL.isAutoFill());
        assertTrue("blaze_fuel does NOT auto-fill", !EnergyType.BLAZE_FUEL.isAutoFill());
        assertTrue("bottle_fuel does NOT auto-fill", !EnergyType.BOTTLE_FUEL.isAutoFill());
        assertTrue("8 types total", EnergyType.values().length == 8);
        assertTrue("smelting id", EnergyType.SMELTING_ENERGY.getId().equals("smelting_energy"));
        assertTrue("smelting tickRate 1", EnergyType.SMELTING_ENERGY.getTickRate() == 1);
    }

    private static void assertTrue(String message, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            MagicStorage.LOGGER.error("SelfTest FAILED: {}", message);
        }
    }
}
