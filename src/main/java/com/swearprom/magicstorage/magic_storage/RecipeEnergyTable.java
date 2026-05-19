package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.HashMap;
import java.util.Map;

public final class RecipeEnergyTable {
    private static final Map<RecipeType<?>, EnergyCost> TABLE = new HashMap<>();

    static {
        TABLE.put(RecipeType.SMELTING,
            new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200));
        TABLE.put(RecipeType.BLASTING,
            new EnergyCost(EnergyType.BLASTING_ENERGY, 100, EnergyType.FURNACE_FUEL, 100));
        TABLE.put(RecipeType.SMOKING,
            new EnergyCost(EnergyType.SMOKING_ENERGY, 100, EnergyType.FURNACE_FUEL, 100));
        TABLE.put(RecipeType.CAMPFIRE_COOKING,
            new EnergyCost(EnergyType.CAMPFIRE_ENERGY, 600, EnergyType.FURNACE_FUEL, 600));
    }

    public static EnergyCost getCost(RecipeType<?> recipeType) {
        return TABLE.get(recipeType);
    }

    public static EnergyCost getBrewingCost() {
        return new EnergyCost(EnergyType.BREW_ENERGY, 400, EnergyType.BLAZE_FUEL, 20);
    }
}
