package com.swearprom.magicstorage.magic_storage.compat;

import com.swearprom.magicstorage.magic_storage.CraftingTerminalMenu;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
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

    private static final int DISPLAY_SLOTS = 54;

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
            return handler.slots.subList(0, DISPLAY_SLOTS);
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
            return true;
        }

        @Override
        public boolean craft(EmiRecipe recipe, EmiCraftContext<CraftingTerminalMenu> context) {
            CraftingTerminalMenu menu = context.getScreenHandler();
            ItemStack output = recipe.getOutputs().get(0).getItemStack().copy();
            if (!output.isEmpty()) {
                for (int i = 0; i < DISPLAY_SLOTS; i++) {
                    Slot slot = menu.getSlot(i);
                    if (ItemStack.isSameItemSameComponents(slot.getItem(), output)) {
                        if (Minecraft.getInstance().gameMode != null) {
                            Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                                    menu.containerId, i, 0,
                                    net.minecraft.world.inventory.ClickType.PICKUP,
                                    Minecraft.getInstance().player);
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    }
}

