package com.swearprom.magicstorage.magic_storage.compat;

import com.swearprom.magicstorage.magic_storage.CraftingTerminalMenu;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.StorageTerminalMenu;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

@EmiEntrypoint
public class MagicStorageEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(MagicStorage.CRAFTING_TERMINAL_MENU.get(), new CraftingTerminalHandler());
    }

    static class CraftingTerminalHandler implements StandardRecipeHandler<CraftingTerminalMenu> {

        @Override
        public List<Slot> getInputSources(CraftingTerminalMenu handler) {
            return handler.slots;
        }

        @Override
        public List<Slot> getCraftingSlots(CraftingTerminalMenu handler) {
            return handler.slots.subList(0, StorageTerminalMenu.DISPLAY_SLOTS);
        }

        @Override
        public List<Slot> getCraftingSlots(EmiRecipe recipe, CraftingTerminalMenu handler) {
            return getInputSources(handler);
        }

        @Override
        public boolean supportsRecipe(EmiRecipe recipe) {
            return true;
        }

        @Override
        public boolean canCraft(EmiRecipe recipe, EmiCraftContext<CraftingTerminalMenu> context) {
            return findOutputSlot(recipe, context.getScreenHandler()) >= 0;
        }

        @Override
        public boolean craft(EmiRecipe recipe, EmiCraftContext<CraftingTerminalMenu> context) {
            CraftingTerminalMenu menu = context.getScreenHandler();
            int slotIndex = findOutputSlot(recipe, menu);
            if (slotIndex >= 0) {
                if (Minecraft.getInstance().gameMode != null) {
                    Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                            menu.containerId, slotIndex, 0,
                            net.minecraft.world.inventory.ClickType.PICKUP,
                            Minecraft.getInstance().player);
                }
                return true;
            }
            return false;
        }

        private int findOutputSlot(EmiRecipe recipe, CraftingTerminalMenu menu) {
            if (recipe.getOutputs().isEmpty()) return -1;
            ItemStack output = recipe.getOutputs().get(0).getItemStack().copy();
            if (output.isEmpty()) return -1;
            for (int i = 0; i < StorageTerminalMenu.DISPLAY_SLOTS; i++) {
                Slot slot = menu.getSlot(i);
                if (ItemStack.isSameItemSameComponents(slot.getItem(), output)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
