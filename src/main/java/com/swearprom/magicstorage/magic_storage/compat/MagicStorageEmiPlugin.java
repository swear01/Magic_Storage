package com.swearprom.magicstorage.magic_storage.compat;

import com.swearprom.magicstorage.magic_storage.CraftingDestination;
import com.swearprom.magicstorage.magic_storage.CraftingTerminalMenu;
import com.swearprom.magicstorage.magic_storage.CraftingTerminalPage;
import com.swearprom.magicstorage.magic_storage.CraftingRecipeSelectionPacket;
import com.swearprom.magicstorage.magic_storage.CraftingTerminalScreen;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.StorageTerminalMenu;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

@EmiEntrypoint
public class MagicStorageEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(MagicStorage.CRAFTING_TERMINAL_MENU.get(), new CraftingTerminalHandler());
        registry.addExclusionArea(CraftingTerminalScreen.class, (screen, consumer) -> {
            for (var area : screen.getEmiExclusionAreas()) {
                consumer.accept(new Bounds(area.getX(), area.getY(), area.getWidth(), area.getHeight()));
            }
        });
    }

    static class CraftingTerminalHandler implements StandardRecipeHandler<CraftingTerminalMenu> {
        private static final int MAX_CRAFT_AMOUNT = 64;
        private static final int PLAYER_INVENTORY_SLOTS = 36;

        @Override
        public List<Slot> getInputSources(CraftingTerminalMenu handler) {
            if (!handler.getPage().isItemPage()) return List.of();
            int firstSlot = handler.getPage() == CraftingTerminalPage.STORAGE
                    ? 0 : StorageTerminalMenu.DISPLAY_SLOTS;
            int lastSlot = handler.isUsePlayerInventory()
                    ? StorageTerminalMenu.DISPLAY_SLOTS + PLAYER_INVENTORY_SLOTS
                    : StorageTerminalMenu.DISPLAY_SLOTS;
            return handler.slots.subList(firstSlot, Math.min(handler.slots.size(), lastSlot));
        }

        @Override
        public List<Slot> getCraftingSlots(CraftingTerminalMenu handler) {
            if (handler.getPage() != CraftingTerminalPage.STORAGE) return List.of();
            return handler.slots.subList(0, StorageTerminalMenu.DISPLAY_SLOTS);
        }

        @Override
        public List<Slot> getCraftingSlots(EmiRecipe recipe, CraftingTerminalMenu handler) {
            if (!handler.getPage().isItemPage()) return List.of();
            return getInputSources(handler);
        }

        @Override
        public boolean supportsRecipe(EmiRecipe recipe) {
            RecipeHolder<?> backingRecipe = recipe.getBackingRecipe();
            return backingRecipe != null && CraftingTerminalMenu.supportsRecipeContract(backingRecipe.value());
        }

        @Override
        public boolean canCraft(EmiRecipe recipe, EmiCraftContext<CraftingTerminalMenu> context) {
            CraftingTerminalMenu handler = context.getScreenHandler();
            return handler.getPage().isItemPage()
                    && supportsRecipe(recipe);
        }

        @Override
        public boolean craft(EmiRecipe recipe, EmiCraftContext<CraftingTerminalMenu> context) {
            CraftingTerminalMenu menu = context.getScreenHandler();
            if (!menu.getPage().isItemPage() || !supportsRecipe(recipe)) return false;
            RecipeHolder<?> backingRecipe = recipe.getBackingRecipe();
            Minecraft minecraft = Minecraft.getInstance();
            if (backingRecipe == null || minecraft.player == null || minecraft.getConnection() == null) return false;
            int amount = Math.max(1, Math.min(context.getAmount(), MAX_CRAFT_AMOUNT));
            CraftingDestination destination = switch (context.getDestination()) {
                case NONE -> CraftingDestination.NONE;
                case CURSOR -> CraftingDestination.CURSOR;
                case INVENTORY -> CraftingDestination.INVENTORY;
            };
            minecraft.getConnection().send(new CraftingRecipeSelectionPacket(
                    menu.containerId, backingRecipe.id(), amount, destination));
            return true;
        }
    }
}
