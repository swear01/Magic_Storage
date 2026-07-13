package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

final class CraftingStationTable {
    private CraftingStationTable() {
    }

    static boolean isAvailable(StorageCoreBlockEntity core, Recipe<?> recipe) {
        if (recipe instanceof AxeTransformationRecipe axeRecipe) {
            ItemStack tool = AxeTransformationCatalog.installedTool(core);
            return MachineEnergyTable.isInstalled(core, MachineEnergyTable.AXE_SLOT)
                    && AxeTransformationCatalog.remainingDurability(tool) > 0
                    && tool.canPerformAction(axeRecipe.ability());
        }
        int slot = requiredSlot(recipe.getType());
        return slot >= 0 && MachineEnergyTable.isInstalled(core, slot);
    }

    static int requiredSlot(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) return MachineEnergyTable.CRAFTING_TABLE_SLOT;
        if (type == RecipeType.SMELTING) return MachineEnergyTable.FURNACE_SLOT;
        if (type == RecipeType.BLASTING) return MachineEnergyTable.BLAST_FURNACE_SLOT;
        if (type == RecipeType.SMOKING) return MachineEnergyTable.SMOKER_SLOT;
        if (type == RecipeType.CAMPFIRE_COOKING) return MachineEnergyTable.CAMPFIRE_SLOT;
        if (type == RecipeType.STONECUTTING) return MachineEnergyTable.STONECUTTER_SLOT;
        if (type == RecipeType.SMITHING) return MachineEnergyTable.SMITHING_TABLE_SLOT;
        return -1;
    }
}
