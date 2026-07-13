package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

public final class RecipeEnergyTable {
    public static EnergyCost getCost(Recipe<?> recipe) {
        if (!(recipe instanceof AbstractCookingRecipe cookingRecipe)) return null;
        long cookingTime = cookingRecipe.getCookingTime();
        if (cookingTime <= 0) return null;

        EnergyType processType;
        RecipeType<?> recipeType = recipe.getType();
        if (recipeType == RecipeType.SMELTING) {
            processType = EnergyType.SMELTING_ENERGY;
        } else if (recipeType == RecipeType.BLASTING) {
            processType = EnergyType.BLASTING_ENERGY;
        } else if (recipeType == RecipeType.SMOKING) {
            processType = EnergyType.SMOKING_ENERGY;
        } else if (recipeType == RecipeType.CAMPFIRE_COOKING) {
            processType = EnergyType.CAMPFIRE_ENERGY;
        } else {
            return null;
        }
        return new EnergyCost(processType, cookingTime, EnergyType.FURNACE_FUEL, cookingTime);
    }
}
