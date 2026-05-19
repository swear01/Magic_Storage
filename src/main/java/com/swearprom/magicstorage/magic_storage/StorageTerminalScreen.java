package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class StorageTerminalScreen<T extends StorageTerminalMenu> extends AbstractContainerScreen<T> {

    protected static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "textures/gui/grid.png");
    protected static final ResourceLocation ICONS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "textures/gui/icons.png");

    protected static final int TOP_HEIGHT = 19;
    protected static final int VISIBLE_ROWS = 6;

    protected static final int SB_X = 174;
    protected static final int SB_WIDTH = 12;
    protected static final int SB_SCROLLER_H = 15;
    private static final int SB_UV_X = 232;

    private EditBox searchBox;
    private boolean isScrolling;
    private int searchTimer = 0;
    private String lastSentSearch = "";

    public StorageTerminalScreen(T menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 227;
        this.imageHeight = getTopHeight() + getBottomHeight() + (VISIBLE_ROWS * 18);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = imageHeight - 94;
    }

    protected int getTopHeight() { return TOP_HEIGHT; }
    protected int getBottomHeight() { return 99; }

    @Override
    protected void init() {
        super.init();
        int sbY = topPos + getTopHeight();
        if (menu.getTotalItemTypes() > VISIBLE_ROWS * 9) {
            sbY += 2;
        }
        this.searchBox = new EditBox(font, leftPos + 81, topPos + 6, 88, font.lineHeight, Component.translatable("gui.magic_storage.search"));
        this.searchBox.setBordered(false);
        this.searchBox.setVisible(true);
        this.searchBox.setTextColor(0xFFFFFF);
        this.searchBox.setMaxLength(50);
        this.addRenderableWidget(searchBox);
    }

    public void refreshSearch(String text) { searchBox.setValue(text); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        super.render(g, mx, my, partial);
        this.renderTooltip(g, mx, my);
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int mx, int my) {
        super.renderTooltip(g, mx, my);
        if (hoveredSlot != null && hoveredSlot.index < StorageTerminalMenu.DISPLAY_SLOTS
                && hoveredSlot.hasItem() && menu.getCarried().isEmpty()) {
            g.renderTooltip(font, hoveredSlot.getItem(), mx, my);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        int x = leftPos;
        int y = topPos;
        g.blit(GUI_TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        int totalItems = menu.getTotalItemTypes();
        int maxOffset = Math.max(0, totalItems - VISIBLE_ROWS * 9);
        if (maxOffset > 0) {
            int sbY = y + getTopHeight() + 2;
            int sbH = VISIBLE_ROWS * 18;
            float ratio = (float) menu.getScrollOffset() / maxOffset;
            int thumbY = sbY + (int) (ratio * (sbH - SB_SCROLLER_H));
            g.blit(ICONS_TEXTURE, x + SB_X, thumbY, SB_UV_X, 0, SB_WIDTH, SB_SCROLLER_H, 256, 256);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xCCCCCC);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xAAAAAA);
        // Type count display
        var countText = menu.getTypeCount() + " / " + menu.getMaxTypes() + " types";
        g.drawString(font, countText, imageWidth - 8 - font.width(countText), 6, 0xAAAAAA);
    }

    @Override
    protected boolean hasClickedOutside(double mx, double my, int l, int t, int button) {
        if (searchBox.isMouseOver(mx, my)) return false;
        return super.hasClickedOutside(mx, my, l, t, button);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (isOverScrollBar(mx, my) && button == 0) {
            isScrolling = true;
            handleScrollClick(my);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) isScrolling = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (isScrolling) { handleScrollClick(my); return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (isOverGrid(mx, my) || isOverScrollBar(mx, my)) {
            if (sy > 0 && menu.getScrollOffset() > 0) sendButton(0);
            else if (sy < 0 && menu.getScrollOffset() < Math.max(0, menu.getTotalItemTypes() - VISIBLE_ROWS * 9))
                sendButton(1);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (searchBox.keyPressed(key, scan, mods)) {
            scheduleSearch();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char cp, int mods) {
        if (searchBox.charTyped(cp, mods)) {
            scheduleSearch();
            return true;
        }
        return super.charTyped(cp, mods);
    }

    private void scheduleSearch() {
        searchTimer = 8; // send after 8 ticks of inactivity
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (searchTimer > 0) {
            searchTimer--;
            if (searchTimer == 0) {
                sendSearchPacket();
            }
        }
    }

    private void sendSearchPacket() {
        String text = searchBox.getValue();
        if (text.equals(lastSentSearch)) return;
        lastSentSearch = text;
        if (minecraft != null && minecraft.player != null && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new SearchFilterPacket(menu.containerId, text));
        }
    }

    void sendButton(int id) {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    boolean isOverGrid(double mx, double my) {
        int left = leftPos + 8;
        int top = topPos + getTopHeight();
        return mx >= left && mx < left + 9 * 18 && my >= top && my < top + VISIBLE_ROWS * 18;
    }

    boolean isOverScrollBar(double mx, double my) {
        return mx >= leftPos + SB_X && mx < leftPos + SB_X + SB_WIDTH
                && my >= topPos + getTopHeight() && my < topPos + getTopHeight() + VISIBLE_ROWS * 18;
    }

    void handleScrollClick(double my) {
        int totalItems = menu.getTotalItemTypes();
        int maxOffset = Math.max(0, totalItems - VISIBLE_ROWS * 9);
        if (maxOffset <= 0 || minecraft == null || minecraft.gameMode == null) return;
        int sbTop = topPos + getTopHeight();
        int trackH = VISIBLE_ROWS * 18 - SB_SCROLLER_H;
        float ratio = (float) (my - sbTop - SB_SCROLLER_H / 2) / trackH;
        int target = Math.clamp((int) (ratio * maxOffset), 0, maxOffset);
        target = (target / 9) * 9;
        int delta = target - menu.getScrollOffset();
        while (delta < 0) { sendButton(0); delta += 9; }
        while (delta >= 9) { sendButton(1); delta -= 9; }
    }
}
