package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class CraftingTerminalScreen extends StorageTerminalScreen<CraftingTerminalMenu> {

    private static final int GRID_TOP = 17;
    private static final int RECIPE_H = 42;
    private static final int CRAFTING_BOTTOM_HEIGHT = 183;

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
    }

    @Override
    protected int getBottomHeight() { return CRAFTING_BOTTOM_HEIGHT; }

    @Override
    protected int gridTopLocal() { return GRID_TOP; }

    @Override
    protected int playerInvLocalLeft() { return 7; }

    @Override
    protected int playerInvLocalTop() {
        return navYLocal() + 18 + 39;
    }

    private int recipeTopLocal() {
        return GRID_TOP + visibleRows * ROW_HEIGHT + 4;
    }

    private int navYLocal() {
        return recipeTopLocal() + RECIPE_H + 2;
    }

    @Override
    protected void init() {
        super.init();
        this.inventoryLabelY = playerInvLocalTop() - 12;
        int x = leftPos;
        int navY = topPos + navYLocal();

        prevRecipeBtn = Button.builder(Component.literal("\u25C0"), b -> clickMenuButton(8))
                .bounds(x + 7, navY, 16, 16).build();
        nextRecipeBtn = Button.builder(Component.literal("\u25B6"), b -> clickMenuButton(9))
                .bounds(x + 104, navY, 16, 16).build();
        craft1Btn = Button.builder(Component.literal("\u00D71"), b -> clickMenuButton(2))
                .bounds(x + 129, navY, 18, 16).build();
        craft8Btn = Button.builder(Component.literal("\u00D78"), b -> clickMenuButton(3))
                .bounds(x + 149, navY, 18, 16).build();
        craft64Btn = Button.builder(Component.literal("\u00D764"), b -> clickMenuButton(4))
                .bounds(x + 169, navY, 20, 16).build();

        int checkY = navY + 18;
        craftableOnlyCheck = Checkbox.builder(Component.translatable("gui.magic_storage.craftable_only"), font)
                .pos(x + 7, checkY).selected(getCraftMenu().isShowOnlyCraftable())
                .onValueChange((cb, v) -> clickMenuButton(6)).build();
        usePlayerInvCheck = Checkbox.builder(Component.translatable("gui.magic_storage.use_player_inv"), font)
                .pos(x + 7, checkY + 14).selected(getCraftMenu().isUsePlayerInventory())
                .onValueChange((cb, v) -> clickMenuButton(7)).build();
        compactModeCheck = Checkbox.builder(Component.literal("Compact"), font)
                .pos(x + 99, checkY).selected(getCraftMenu().isCompactMode())
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
        drawPanels(g, x, y);
        renderRecipePanel(g, x, y);

        int totalItems = menu.getTotalItemTypes();
        int maxOffset = Math.max(0, totalItems - visibleRows * 9);
        if (maxOffset > 0) {
            int sbY = y + getTopHeight();
            int sbH = visibleRows * 18;
            float ratio = (float) menu.getScrollOffset() / maxOffset;
            int thumbY = sbY + (int) (ratio * (sbH - 15));
            g.blit(ICONS_TEXTURE, x + SB_X, thumbY, 232, 0, 12, 15, 256, 256);
        }
    }

    private void renderRecipePanel(GuiGraphics g, int x, int y) {
        int rx = x + 8, ry = y + recipeTopLocal(), rw = imageWidth - 16;
        g.fill(rx, ry, rx + rw, ry + RECIPE_H, 0xC0101010);
        g.renderOutline(rx, ry, rw, RECIPE_H, 0xFF555555);

        CraftingTerminalMenu cm = getCraftMenu();
        ItemStack sel = cm.getSelectedStack();
        if (sel.isEmpty()) return;

        int recipes = cm.getRecipeCount();
        if (recipes <= 0) { g.drawString(font, "No recipe available", rx + 4, ry + 6, 0xAAAAAA); return; }

        int ri = Math.clamp(cm.getCurrentRecipeIndex(), 0, recipes - 1);

        g.drawString(font, sel.getHoverName().getString(), rx + 4, ry + 4, 0xFFFFFF);
        g.drawString(font, cm.getCurrentRecipeTypeLabel(), rx + 4, ry + 14, 0xAAAAAA);
        g.drawString(font, "Recipe " + (ri + 1) + "/" + recipes, rx + 80, ry + 4, 0xCCCCCC);

        int craftable = cm.getCraftableCount();
        if (craftable > 0) {
            String txt = "×" + craftable + " possible";
            g.drawString(font, txt, rx + rw - 4 - font.width(txt), ry + 14, 0x55FF55);
        } else {
            var missing = cm.getMissingPreview();
            String txt = missing.isEmpty() ? "missing materials"
                    : "missing: " + missing.get(0).getHoverName().getString();
            g.drawString(font, txt, rx + rw - 4 - font.width(txt), ry + 14, 0xFF5555);
        }

        g.drawString(font, "Preview is server-synced", rx + 4, ry + 36, 0x888888);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xCCCCCC);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xAAAAAA);
    }
}
