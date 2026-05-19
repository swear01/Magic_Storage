package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CraftingTerminalScreen extends StorageTerminalScreen<CraftingTerminalMenu> {

    private static final ResourceLocation CRAFTING_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "textures/gui/crafting_grid.png");

    private static final int RECIPE_TOP = 131;
    private static final int RECIPE_H = 42;
    private static final int EXTRA_HEIGHT = 30;

    private Button prevRecipeBtn;
    private Button nextRecipeBtn;
    private Button craft1Btn;
    private Button craft8Btn;
    private Button craft64Btn;
    private Checkbox craftableOnlyCheck;
    private Checkbox usePlayerInvCheck;
    private Checkbox compactModeCheck;

    public CraftingTerminalScreen(CraftingTerminalMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageHeight = 256;
        this.inventoryLabelY = 178;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;
        int navY = y + RECIPE_TOP + RECIPE_H + 2;

        prevRecipeBtn = Button.builder(Component.literal("\u25C0"), b -> clickMenuButton(8))
                .bounds(x + 8, navY, 16, 16).build();
        nextRecipeBtn = Button.builder(Component.literal("\u25B6"), b -> clickMenuButton(9))
                .bounds(x + 105, navY, 16, 16).build();
        craft1Btn = Button.builder(Component.literal("\u00D71"), b -> clickMenuButton(2))
                .bounds(x + 130, navY, 18, 16).build();
        craft8Btn = Button.builder(Component.literal("\u00D78"), b -> clickMenuButton(3))
                .bounds(x + 150, navY, 18, 16).build();
        craft64Btn = Button.builder(Component.literal("\u00D764"), b -> clickMenuButton(4))
                .bounds(x + 170, navY, 20, 16).build();

        int checkY = navY + 18;
        craftableOnlyCheck = Checkbox.builder(Component.translatable("gui.magic_storage.craftable_only"), font)
                .pos(x + 8, checkY).selected(getCraftMenu().isShowOnlyCraftable())
                .onValueChange((cb, v) -> clickMenuButton(6)).build();
        usePlayerInvCheck = Checkbox.builder(Component.translatable("gui.magic_storage.use_player_inv"), font)
                .pos(x + 8, checkY + 14).selected(getCraftMenu().isUsePlayerInventory())
                .onValueChange((cb, v) -> clickMenuButton(7)).build();
        compactModeCheck = Checkbox.builder(Component.literal("Compact"), font)
                .pos(x + 100, checkY).selected(getCraftMenu().isCompactMode())
                .onValueChange((cb, v) -> clickMenuButton(10)).build();

        addRenderableWidget(prevRecipeBtn);
        addRenderableWidget(nextRecipeBtn);
        addRenderableWidget(craft1Btn);
        addRenderableWidget(craft8Btn);
        addRenderableWidget(craft64Btn);
        addRenderableWidget(craftableOnlyCheck);
        addRenderableWidget(usePlayerInvCheck);
        addRenderableWidget(compactModeCheck);
    }

    private CraftingTerminalMenu getCraftMenu() { return (CraftingTerminalMenu) menu; }

    private void clickMenuButton(int id) {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        int x = leftPos, y = topPos;
        g.blit(CRAFTING_GUI_TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
        renderRecipePanel(g, x, y);

        int totalItems = menu.getTotalItemTypes();
        int maxOffset = Math.max(0, totalItems - VISIBLE_ROWS * 9);
        if (maxOffset > 0) {
            int sbY = y + getTopHeight();
            int sbH = VISIBLE_ROWS * 18;
            float ratio = (float) menu.getScrollOffset() / maxOffset;
            int thumbY = sbY + (int) (ratio * (sbH - 15));
            g.blit(ICONS_TEXTURE, x + SB_X, thumbY, 232, 0, 12, 15, 256, 256);
        }
    }

    private void renderRecipePanel(GuiGraphics g, int x, int y) {
        int rx = x + 8, ry = y + RECIPE_TOP, rw = imageWidth - 16;
        g.fill(rx, ry, rx + rw, ry + RECIPE_H, 0xC0101010);
        g.renderOutline(rx, ry, rw, RECIPE_H, 0xFF555555);

        CraftingTerminalMenu cm = getCraftMenu();
        int idx = cm.getSelectedItemIndex();
        if (idx < 0 || idx >= cm.slots.size()) return;
        ItemStack sel = cm.getSlot(idx).getItem();
        if (sel.isEmpty()) return;

        List<RecipeHolder<?>> recipes = findRecipesClient(sel);
        if (recipes.isEmpty()) { g.drawString(font, "No recipe available", rx + 4, ry + 6, 0xAAAAAA); return; }

        int ri = Math.clamp(cm.getCurrentRecipeIndex(), 0, recipes.size() - 1);
        Recipe<?> recipe = recipes.get(ri).value();
        ItemStack result = recipe.getResultItem(minecraft != null && minecraft.level != null ? minecraft.level.registryAccess() : null);

        g.drawString(font, result.getHoverName().getString(), rx + 4, ry + 4, 0xFFFFFF);
        g.drawString(font, getTypeName(recipe.getType()), rx + 4, ry + 14, 0xAAAAAA);
        g.drawString(font, "Recipe " + (ri + 1) + "/" + recipes.size(), rx + 80, ry + 4, 0xCCCCCC);

        EnergyCost cost = RecipeEnergyTable.getCost(recipe.getType());
        if (cost != null && minecraft != null && minecraft.level != null) {
            var core = cm.getCore(minecraft.level);
            long pE = core != null ? core.getEnergy(cost.processType()) : 0;
            long fE = core != null ? core.getEnergy(cost.fuelType()) : 0;
            g.drawString(font, cost.processType().getId() + ": " + pE + "/" + cost.processAmount(), rx + 4, ry + 26, pE >= cost.processAmount() ? 0x55FF55 : 0xFF5555);
            g.drawString(font, cost.fuelType().getId() + ": " + fE + "/" + cost.fuelAmount(), rx + 90, ry + 26, fE >= cost.fuelAmount() ? 0x55FF55 : 0xFF5555);
        }

        StringBuilder mat = new StringBuilder();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            var items = ing.getItems();
            if (items.length > 0 && !mat.isEmpty()) mat.append(", ");
            if (items.length > 0) mat.append(items[0].getHoverName().getString());
        }
        if (mat.isEmpty()) mat.append("(none)");
        g.drawString(font, mat.toString(), rx + 4, ry + 36, 0x888888);
    }

    private List<RecipeHolder<?>> findRecipesClient(ItemStack output) {
        List<RecipeHolder<?>> matches = new ArrayList<>();
        if (minecraft == null || minecraft.level == null) return matches;
        RecipeManager mgr = minecraft.level.getRecipeManager();
        for (RecipeType<?> type : new RecipeType[]{RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING, RecipeType.STONECUTTING, RecipeType.SMITHING}) {
            @SuppressWarnings({"unchecked","rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) mgr.getAllRecipesFor((RecipeType) type);
            for (RecipeHolder<?> h : holders)
                if (ItemStack.isSameItemSameComponents(h.value().getResultItem(minecraft.level.registryAccess()), output))
                    matches.add(h);
        }
        return matches;
    }

    private String getTypeName(RecipeType<?> t) {
        if (t == RecipeType.CRAFTING) return "Crafting";
        if (t == RecipeType.SMELTING) return "Smelting";
        if (t == RecipeType.BLASTING) return "Blasting";
        if (t == RecipeType.SMOKING) return "Smoking";
        if (t == RecipeType.CAMPFIRE_COOKING) return "Campfire";
        if (t == RecipeType.STONECUTTING) return "Stonecutting";
        if (t == RecipeType.SMITHING) return "Smithing";
        return "Unknown";
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0x404040);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040);
    }
}
