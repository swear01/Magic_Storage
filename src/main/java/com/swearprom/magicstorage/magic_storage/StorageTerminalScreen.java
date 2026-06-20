package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public class StorageTerminalScreen<T extends StorageTerminalMenu> extends AbstractContainerScreen<T> {

    protected static final ResourceLocation ICONS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "textures/gui/icons.png");

    protected static final int TOP_HEIGHT = 19;
    protected static final int BOTTOM_HEIGHT = 99;
    protected static final int ROW_HEIGHT = 18;
    protected static final int MIN_VISIBLE_ROWS = 3;
    protected static final int MAX_VISIBLE_ROWS = 9;

    protected static final int SB_X = 174;
    protected static final int SB_WIDTH = 12;
    protected static final int SB_SCROLLER_H = 15;
    private static final int SB_UV_X = 232;

    private static final int BUTTON_X = 188;
    private static final int BUTTON_W = 16;
    private static final int BUTTON_H = 16;

    private EditBox searchBox;
    private boolean isScrolling;
    private int searchTimer = 0;
    private String lastSentSearch = "";
    protected int visibleRows;
    private SearchMode searchMode = SearchMode.NORMAL;

    private Button sortOrderBtn;
    private Button sortModeBtn;
    private Button searchModeBtn;

    public StorageTerminalScreen(T menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    protected int getTopHeight() { return TOP_HEIGHT; }
    protected int getBottomHeight() { return BOTTOM_HEIGHT; }
    protected int gridTopLocal() { return getTopHeight(); }

    protected int computeVisibleRows() {
        return Math.clamp((this.height - getTopHeight() - getBottomHeight()) / ROW_HEIGHT,
                MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS);
    }

    @Override
    protected void init() {
        this.visibleRows = computeVisibleRows();
        this.imageWidth = 210;
        this.imageHeight = getTopHeight() + getBottomHeight() + visibleRows * ROW_HEIGHT;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = imageHeight - 94;
        super.init();

        int x = leftPos;
        int y = topPos;
        int gridTop = y + getTopHeight();

        this.searchBox = new EditBox(font, x + 102, y + 6, 78, font.lineHeight, Component.translatable("gui.magic_storage.search"));
        this.searchBox.setBordered(false);
        this.searchBox.setVisible(true);
        this.searchBox.setTextColor(0xFFFFFF);
        this.searchBox.setMaxLength(50);
        this.addRenderableWidget(searchBox);

        sortOrderBtn = Button.builder(Component.literal("v"), b -> { sendButton(11); setFocused(null); })
                .bounds(x + BUTTON_X, gridTop, BUTTON_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable("tooltip.magic_storage.sort_order"))).build();
        addRenderableWidget(sortOrderBtn);

        sortModeBtn = Button.builder(Component.literal("S"), b -> { sendButton(12); setFocused(null); })
                .bounds(x + BUTTON_X, gridTop + 20, BUTTON_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable("tooltip.magic_storage.sort_mode"))).build();
        addRenderableWidget(sortModeBtn);

        searchModeBtn = Button.builder(searchModeLabel(), b -> { cycleSearchMode(); setFocused(null); })
                .bounds(x + BUTTON_X, gridTop + 40, BUTTON_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable("tooltip.magic_storage.search_mode"))).build();
        addRenderableWidget(searchModeBtn);

        repositionPlayerInventory();
        int slotLimit = visibleRows * 9;
        for (int i = 0; i < StorageTerminalMenu.DISPLAY_SLOTS; i++) {
            if (menu.slots.get(i) instanceof GhostSlot g) g.activeLimit = slotLimit;
        }
        sendSettings();
    }

    protected int playerInvLocalTop() {
        return getTopHeight() + visibleRows * ROW_HEIGHT + 14;
    }

    protected int playerInvLocalLeft() {
        return 8;
    }

    protected void repositionPlayerInventory() {
        int playerInvTop = playerInvLocalTop();
        int left = playerInvLocalLeft();
        int start = StorageTerminalMenu.DISPLAY_SLOTS;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int i = start + col + row * 9;
                Slot old = menu.slots.get(i);
                Slot moved = new Slot(old.container, old.getContainerSlot(),
                        left + col * 18, playerInvTop + row * 18);
                moved.index = i;
                menu.slots.set(i, moved);
            }
        }
        for (int col = 0; col < 9; col++) {
            int i = start + 27 + col;
            Slot old = menu.slots.get(i);
            Slot moved = new Slot(old.container, old.getContainerSlot(),
                    left + col * 18, playerInvTop + 3 * 18 + 4);
            moved.index = i;
            menu.slots.set(i, moved);
        }
    }

    private Component searchModeLabel() {
        return switch (searchMode) {
            case NORMAL -> Component.literal("A");
            case TAG -> Component.literal("#");
            case MOD -> Component.literal("@");
        };
    }

    private void cycleSearchMode() {
        searchMode = searchMode.next();
        searchModeBtn.setMessage(searchModeLabel());
        sendSearchPacket();
    }

    private void sendSettings() {
        if (minecraft != null && minecraft.player != null && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new TerminalSettingsPacket(
                    menu.containerId, visibleRows));
        }
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
        if (hoveredSlot != null && hoveredSlot.index < visibleRows * 9
                && hoveredSlot.hasItem() && menu.getCarried().isEmpty()) {
            g.renderTooltip(font, hoveredSlot.getItem(), mx, my);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        int x = leftPos;
        int y = topPos;
        drawPanels(g, x, y);

        int totalItems = menu.getTotalItemTypes();
        int maxOffset = Math.max(0, totalItems - visibleRows * 9);
        if (maxOffset > 0) {
            int sbY = y + getTopHeight() + 2;
            int sbH = visibleRows * ROW_HEIGHT;
            float ratio = (float) menu.getScrollOffset() / maxOffset;
            int thumbY = sbY + (int) (ratio * (sbH - SB_SCROLLER_H));
            g.blit(ICONS_TEXTURE, x + SB_X, thumbY, SB_UV_X, 0, SB_WIDTH, SB_SCROLLER_H, 256, 256);
        }
    }

    protected void drawPanels(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xFF1E1E1E);
        int left = x + playerInvLocalLeft() - 1;
        int right = left + 9 * ROW_HEIGHT + 2;
        int gridTop = y + gridTopLocal();
        g.fill(left, gridTop, right, gridTop + visibleRows * ROW_HEIGHT, 0xFF1A1A1A);
        g.renderOutline(left, gridTop, right - left, visibleRows * ROW_HEIGHT, 0xFF555555);
        int invTop = y + playerInvLocalTop();
        g.fill(left, invTop, right, invTop + 4 * ROW_HEIGHT + 4, 0xFF252525);
        int sx = x + 100, sy = y + 4;
        g.fill(sx, sy, sx + 84, sy + 12, 0xFF000000);
        g.renderOutline(sx, sy, 84, 12, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xCCCCCC);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xAAAAAA);
        var countText = menu.getTypeCount() + " / " + menu.getMaxTypes() + " types";
        g.drawString(font, countText, imageWidth - 8 - font.width(countText), inventoryLabelY, 0xAAAAAA);
    }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (slot.index >= StorageTerminalMenu.DISPLAY_SLOTS) {
            super.renderSlot(g, slot);
            return;
        }
        if (slot.index >= visibleRows * 9) return;
        super.renderSlot(g, slot);
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
        if (hoveredSlot != null && hoveredSlot.index >= visibleRows * 9
                && hoveredSlot.index < StorageTerminalMenu.DISPLAY_SLOTS) {
            return false;
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
            else if (sy < 0 && menu.getScrollOffset() < Math.max(0, menu.getTotalItemTypes() - visibleRows * 9))
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
        searchTimer = 8;
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
        String text = searchMode.apply(searchBox.getValue());
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
        return mx >= left && mx < left + 9 * 18 && my >= top && my < top + visibleRows * ROW_HEIGHT;
    }

    boolean isOverScrollBar(double mx, double my) {
        return mx >= leftPos + SB_X && mx < leftPos + SB_X + SB_WIDTH
                && my >= topPos + getTopHeight() && my < topPos + getTopHeight() + visibleRows * ROW_HEIGHT;
    }

    void handleScrollClick(double my) {
        if (minecraft == null || minecraft.gameMode == null) return;
        int totalItems = menu.getTotalItemTypes();
        int maxOffset = Math.max(0, totalItems - visibleRows * 9);
        if (maxOffset <= 0) return;
        int sbTop = topPos + getTopHeight();
        int trackH = visibleRows * ROW_HEIGHT - SB_SCROLLER_H;
        float ratio = (float) (my - sbTop - SB_SCROLLER_H / 2) / trackH;
        int target = Math.clamp((int) (ratio * maxOffset), 0, maxOffset);
        target = (target / 9) * 9;
        int delta = target - menu.getScrollOffset();
        while (delta < 0) { sendButton(0); delta += 9; }
        while (delta >= 9) { sendButton(1); delta -= 9; }
    }
}
