package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class CraftingTerminalScreen extends StorageTerminalScreen<CraftingTerminalMenu> {

    private static final List<FuelTargetOption> FUEL_TARGET_OPTIONS = buildFuelTargetOptions();

    private Button prevRecipeBtn;
    private Button nextRecipeBtn;
    private Button craft1Btn;
    private Button craft8Btn;
    private Button craft64Btn;
    private Button craftMaxBtn;
    private TerminalIconButton storagePageBtn;
    private TerminalIconButton craftablePageBtn;
    private TerminalIconButton fuelPageBtn;
    private TerminalIconButton sortOrderRailBtn;
    private TerminalIconButton sortModeRailBtn;
    private TerminalIconButton searchModeRailBtn;
    private TerminalIconButton playerInventoryRailBtn;
    private CycleButton<FuelTargetOption> fuelTargetSelector;
    private CraftingTerminalPage lastPage;
    private EnergyType lastFuelTarget;
    private int machinePage;
    private int reservePage;

    public CraftingTerminalScreen(CraftingTerminalMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected TerminalLayout.Geometry createGeometry() {
        return TerminalLayout.crafting(this.width, this.height,
                MachineEnergyTable.entries().size(), CraftingTerminalMenu.fuelTargets().size());
    }

    @Override
    protected int railButtonCount() {
        return 7;
    }

    @Override
    protected boolean isItemViewActive() {
        return menu.getPage().isItemPage();
    }

    @Override
    protected void init() {
        super.init();
        repositionFuelSlots();
        List<TerminalLayout.Rect> navigationButtons = geometry.recipeNavigationButtons();
        prevRecipeBtn = addRecipeNavigationButton(
                NavigationIcon.PREVIOUS,
                Component.literal("Previous recipe"),
                navigationButtons.get(0),
                button -> clickMenuButton(8));
        nextRecipeBtn = addRecipeNavigationButton(
                NavigationIcon.NEXT,
                Component.literal("Next recipe"),
                navigationButtons.get(1),
                button -> clickMenuButton(9));

        List<TerminalLayout.Rect> craftButtons = geometry.recipeCraftButtons();
        craft1Btn = addRecipeButton(
                Component.literal("×1"), craftButtons.get(0), button -> clickMenuButton(2));
        craft8Btn = addRecipeButton(
                Component.literal("×8"), craftButtons.get(1), button -> clickMenuButton(3));
        craft64Btn = addRecipeButton(
                Component.literal("×64"), craftButtons.get(2), button -> clickMenuButton(4));
        craftMaxBtn = addRecipeButton(
                Component.translatable("gui.magic_storage.craft_max"), craftButtons.get(3),
                button -> clickMenuButton(CraftingTerminalMenu.MAX_CRAFT_BUTTON));

        storagePageBtn = addSideButton(RailIcon.STORAGE,
                Component.translatable("gui.magic_storage.page_storage"), geometry.railButtons().get(0),
                button -> clickMenuButton(CraftingTerminalMenu.STORAGE_PAGE_BUTTON));
        craftablePageBtn = addSideButton(RailIcon.CRAFTABLE,
                Component.translatable("gui.magic_storage.page_craftable"), geometry.railButtons().get(1),
                button -> clickMenuButton(CraftingTerminalMenu.CRAFTABLE_PAGE_BUTTON));
        fuelPageBtn = addSideButton(RailIcon.FUEL,
                Component.translatable("gui.magic_storage.page_fuel"), geometry.railButtons().get(2),
                button -> clickMenuButton(CraftingTerminalMenu.FUEL_PAGE_BUTTON));

        sortOrderRailBtn = addSideButton(RailIcon.SORT_ASCENDING,
                Component.translatable("tooltip.magic_storage.sort_order"), geometry.railButtons().get(3),
                button -> clickMenuButton(11));
        sortModeRailBtn = addSideButton(RailIcon.SORT_NAME,
                Component.translatable("tooltip.magic_storage.sort_mode"), geometry.railButtons().get(4),
                button -> clickMenuButton(12));
        searchModeRailBtn = addSideButton(RailIcon.SEARCH,
                Component.translatable("tooltip.magic_storage.search_mode"), geometry.railButtons().get(5),
                button -> clickMenuButton(13));
        playerInventoryRailBtn = addSideButton(RailIcon.PLAYER_INVENTORY,
                Component.translatable("gui.magic_storage.use_player_inv"), geometry.railButtons().get(6),
                button -> clickMenuButton(7));

        TerminalLayout.Rect targetBounds = geometry.fuelTargetSelector();
        fuelTargetSelector = CycleButton.builder(FuelTargetOption::label)
                .withValues(fuelTargetOptions())
                .withInitialValue(new FuelTargetOption(menu.getSelectedFuelTarget()))
                .withTooltip(option -> Tooltip.create(
                        Component.translatable("gui.magic_storage.fuel_target")
                                .append(": ").append(option.label())))
                .create(
                        leftPos + targetBounds.x(),
                        topPos + targetBounds.y(),
                        targetBounds.width(),
                        targetBounds.height(),
                        Component.translatable("gui.magic_storage.fuel_target"),
                        (button, option) -> clickMenuButton(option.target() == null
                                ? CraftingTerminalMenu.AUTO_FUEL_TARGET_BUTTON
                                : CraftingTerminalMenu.fuelTargetButtonId(option.target())));
        addRenderableWidget(fuelTargetSelector);

        updatePageWidgets();
        updateSidebarState();
    }

    private Button addRecipeButton(
            Component message,
            TerminalLayout.Rect bounds,
            Button.OnPress action
    ) {
        Button button = Button.builder(message, action)
                .bounds(leftPos + bounds.x(), topPos + bounds.y(), bounds.width(), bounds.height())
                .build();
        addRenderableWidget(button);
        return button;
    }

    private Button addRecipeNavigationButton(
            NavigationIcon icon,
            Component narration,
            TerminalLayout.Rect bounds,
            Button.OnPress action
    ) {
        Button button = new RecipeNavigationButton(
                leftPos + bounds.x(), topPos + bounds.y(), bounds.width(), bounds.height(),
                narration, action, icon);
        addRenderableWidget(button);
        return button;
    }

    private TerminalIconButton addSideButton(
            RailIcon icon,
            Component narration,
            TerminalLayout.Rect bounds,
            Button.OnPress action
    ) {
        TerminalIconButton button = new TerminalIconButton(
                leftPos + bounds.x(), topPos + bounds.y(), bounds.width(), bounds.height(),
                narration, action, icon);
        addRenderableWidget(button);
        return button;
    }

    private void repositionFuelSlots() {
        TerminalLayout.Rect fuelInput = geometry.fuelInput();
        replaceSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT, fuelInput.x(), fuelInput.y());
        machinePage = Math.clamp(machinePage, 0, geometry.machineGrid().pageCount() - 1);
        reservePage = Math.clamp(reservePage, 0, geometry.reserveGrid().pageCount() - 1);
        int firstVisible = machinePage * geometry.machineGrid().capacity();
        List<TerminalLayout.Rect> cells = geometry.machineGrid().cells(machinePage);
        for (int machineSlot = 0; machineSlot < CraftingTerminalMenu.MACHINE_SLOT_COUNT; machineSlot++) {
            int visibleIndex = machineSlot - firstVisible;
            if (visibleIndex < 0 || visibleIndex >= cells.size()) {
                replaceSlot(CraftingTerminalMenu.MACHINE_SLOT_START + machineSlot, -9999, -9999);
                continue;
            }
            TerminalLayout.Rect cell = cells.get(visibleIndex);
            replaceSlot(CraftingTerminalMenu.MACHINE_SLOT_START + machineSlot,
                    cell.x() + (cell.width() - TerminalLayout.SLOT_SIZE) / 2, cell.y());
        }
    }

    private void clickMenuButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private void updatePageWidgets() {
        CraftingTerminalPage page = menu.getPage();
        EnergyType target = menu.getSelectedFuelTarget();
        boolean itemPage = page.isItemPage();
        setSearchControlVisible(itemPage);
        setViewButtonsVisible(false);

        setWidgetVisible(prevRecipeBtn, itemPage);
        setWidgetVisible(nextRecipeBtn, itemPage);
        setWidgetVisible(craft1Btn, itemPage);
        setWidgetVisible(craft8Btn, itemPage);
        setWidgetVisible(craft64Btn, itemPage);
        setWidgetVisible(craftMaxBtn, itemPage);
        setWidgetVisible(sortOrderRailBtn, itemPage);
        setWidgetVisible(sortModeRailBtn, itemPage);
        setWidgetVisible(searchModeRailBtn, itemPage);
        setWidgetVisible(playerInventoryRailBtn, itemPage);

        storagePageBtn.active = page != CraftingTerminalPage.STORAGE;
        craftablePageBtn.active = page != CraftingTerminalPage.CRAFTABLE;
        fuelPageBtn.active = page != CraftingTerminalPage.FUEL;

        boolean fuel = page == CraftingTerminalPage.FUEL;
        setWidgetVisible(fuelTargetSelector, fuel);
        if (fuel) {
            fuelTargetSelector.active = fuelTargetOptions().size() > 1;
            fuelTargetSelector.setValue(new FuelTargetOption(target));
        }
        updateCraftButtonState();
        lastPage = page;
        lastFuelTarget = target;
    }

    private void updateSidebarState() {
        storagePageBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.page_storage")));
        craftablePageBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.page_craftable")));
        fuelPageBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.page_fuel")));

        sortOrderRailBtn.setIcon(menu.getSortOrder() == SortOrder.ASCENDING
                ? RailIcon.SORT_ASCENDING : RailIcon.SORT_DESCENDING);
        sortModeRailBtn.setIcon(switch (menu.getSortMode()) {
            case NAME -> RailIcon.SORT_NAME;
            case QUANTITY -> RailIcon.SORT_QUANTITY;
            case ID -> RailIcon.SORT_ID;
        });
        searchModeRailBtn.setIcon(switch (menu.getSearchMode()) {
            case NORMAL -> RailIcon.SEARCH;
            case TAG -> RailIcon.SEARCH_TAG;
            case MOD -> RailIcon.SEARCH_MOD;
        });
        sortOrderRailBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.magic_storage.sort_order")
                .append(": ").append(menu.getSortOrder().name())));
        sortModeRailBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.magic_storage.sort_mode")
                .append(": ").append(menu.getSortMode().name())));
        searchModeRailBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.magic_storage.search_mode")
                .append(": ").append(searchModeLabel(menu.getSearchMode()))));

        updateToggleButton(playerInventoryRailBtn, "gui.magic_storage.use_player_inv",
                menu.isUsePlayerInventory());
    }

    private static Component searchModeLabel(SearchMode mode) {
        return Component.translatable(switch (mode) {
            case NORMAL -> "gui.magic_storage.search_mode.name";
            case TAG -> "gui.magic_storage.search_mode.tag";
            case MOD -> "gui.magic_storage.search_mode.mod";
        });
    }

    private void updateCraftButtonState() {
        if (craft1Btn == null) return;
        int craftable = menu.getCraftableCount();
        craft1Btn.active = craftable >= 1;
        craft8Btn.active = craftable >= 8;
        craft64Btn.active = craftable >= 64;
        craftMaxBtn.active = craftable >= 1;
        prevRecipeBtn.active = menu.getRecipeCount() > 1;
        nextRecipeBtn.active = menu.getRecipeCount() > 1;
    }

    private void updateToggleButton(TerminalIconButton button, String key, boolean enabled) {
        Component message = Component.translatable(key).append(": ")
                .append(Component.translatable(enabled
                        ? "gui.magic_storage.state_on"
                        : "gui.magic_storage.state_off"));
        button.setMessage(message);
        button.setTooltip(Tooltip.create(message));
    }

    private void setWidgetVisible(AbstractWidget widget, boolean visible) {
        if (!visible && getFocused() == widget) setFocused(null);
        widget.visible = visible;
        widget.active = visible;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (lastPage != menu.getPage() || lastFuelTarget != menu.getSelectedFuelTarget()) {
            updatePageWidgets();
        }
        updateCraftButtonState();
        updateSidebarState();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (menu.getPage() == CraftingTerminalPage.FUEL) {
            renderFuelPage(graphics);
        } else {
            super.renderBg(graphics, partialTick, mouseX, mouseY);
            renderRecipePanel(graphics);
        }
        renderSideRail(graphics);
    }

    private void renderSideRail(GuiGraphics graphics) {
        boolean itemPage = menu.getPage().isItemPage();
        boolean fuelPage = menu.getPage() == CraftingTerminalPage.FUEL;
        drawRaisedPanel(graphics, leftPos, topPos,
                fuelPage ? geometry.fuelRailPanel() : geometry.railPanel());
        drawRailMarker(graphics, menu.getPage().ordinal(), fuelPage, 0xFF7A5B18);
        if (itemPage) {
            if (menu.isUsePlayerInventory()) drawRailMarker(graphics, 6, false, 0xFF2E7D32);
        }
    }

    private void drawRailMarker(GuiGraphics graphics, int railIndex, boolean fuelLayout, int color) {
        List<TerminalLayout.Rect> buttons = fuelLayout
                ? geometry.fuelRailButtons() : geometry.railButtons();
        if (railIndex < 0 || railIndex >= buttons.size()) return;
        TerminalLayout.Rect button = buttons.get(railIndex);
        int x = leftPos + button.x() - 2;
        int y = topPos + button.y() + 3;
        graphics.fill(x, y, x + 2, y + button.height() - 6, color);
    }

    private void renderFuelPage(GuiGraphics graphics) {
        drawRaisedPanel(graphics, leftPos, topPos, geometry.frame());
        drawInsetPanel(graphics, leftPos, topPos, geometry.playerInventory());
        renderMachinePanel(graphics);
        renderFuelPanel(graphics);
        renderFuelControlPanel(graphics);
    }

    private void renderMachinePanel(GuiGraphics graphics) {
        TerminalLayout.Rect panel = geometry.machinePanel();
        drawInsetPanel(graphics, leftPos, topPos, panel);
        int panelX = leftPos + panel.x();
        int panelY = topPos + panel.y();
        graphics.drawString(font, Component.translatable("gui.magic_storage.installed_machines"),
                panelX + 5, panelY + 5, 0xFF404040, false);
        drawFlowPageIndicator(
                graphics, panel, machinePage, geometry.machineGrid().pageCount(), panel.right() - 5);

        List<MachineEnergyTable.Entry> entries = MachineEnergyTable.entries();
        int first = machinePage * geometry.machineGrid().capacity();
        int visible = geometry.machineGrid().visibleCount(machinePage);
        List<TerminalLayout.Rect> cells = geometry.machineGrid().cells(machinePage);
        for (int index = 0; index < visible; index++) {
            MachineEnergyTable.Entry entry = entries.get(first + index);
            TerminalLayout.Rect cell = cells.get(index);
            int slotX = leftPos + cell.x() + (cell.width() - TerminalLayout.SLOT_SIZE) / 2;
            int slotY = topPos + cell.y();
            drawSlotFrame(graphics, slotX, slotY);
            if (entry.generatesEnergy()) {
                drawCenteredNoShadow(graphics,
                        formatAmount(menu.getEnergyAmount(entry.energyType())),
                        leftPos + cell.x() + cell.width() / 2,
                        topPos + cell.bottom() - font.lineHeight - 1, 0xFF202020);
            }
        }
    }

    private void renderFuelPanel(GuiGraphics graphics) {
        TerminalLayout.Rect panel = geometry.fuelPanel();
        drawInsetPanel(graphics, leftPos, topPos, panel);
        int panelX = leftPos + panel.x();
        int panelY = topPos + panel.y();
        int pageCount = geometry.reserveGrid().pageCount();
        TerminalLayout.Rect selector = geometry.fuelTargetSelector();
        int headingRight = geometry.wide() ? panel.right() - 5 : selector.x() - 4;
        if (pageCount > 1) {
            String pageText = (Math.clamp(reservePage, 0, pageCount - 1) + 1) + "/" + pageCount;
            headingRight -= font.width(pageText) + 4;
        }
        Component heading = Component.translatable("gui.magic_storage.energy_reserves");
        String visibleHeading = font.plainSubstrByWidth(
                heading.getString(), Math.max(0, headingRight - panel.x() - 5));
        graphics.drawString(font, visibleHeading, panelX + 5, panelY + 5, 0xFF404040, false);
        drawFlowPageIndicator(graphics, panel, reservePage, pageCount,
                geometry.wide() ? panel.right() - 5 : selector.x() - 4);

        List<EnergyType> reserveTypes = CraftingTerminalMenu.fuelTargets();
        int first = reservePage * geometry.reserveGrid().capacity();
        int visible = geometry.reserveGrid().visibleCount(reservePage);
        List<TerminalLayout.Rect> cells = geometry.reserveGrid().cells(reservePage);
        for (int index = 0; index < visible; index++) {
            EnergyType type = reserveTypes.get(first + index);
            TerminalLayout.Rect cell = cells.get(index);
            int center = leftPos + cell.x() + cell.width() / 2;
            graphics.renderItem(type.representativeStack(), center - 8, topPos + cell.y());
            drawCenteredNoShadow(graphics, formatAmount(menu.getEnergyAmount(type)), center,
                    topPos + cell.bottom() - font.lineHeight - 1, 0xFF202020);
        }
    }

    private void renderFuelControlPanel(GuiGraphics graphics) {
        TerminalLayout.Rect panel = geometry.fuelControlPanel();
        if (!panel.equals(geometry.fuelPanel())) {
            drawInsetPanel(graphics, leftPos, topPos, panel);
        }
        TerminalLayout.Rect input = geometry.fuelInput();
        drawSlotFrame(graphics, leftPos + input.x(), topPos + input.y());
        if (geometry.wide()) {
            graphics.drawString(font, Component.translatable("gui.magic_storage.fuel_input"),
                    leftPos + input.right() + 5, topPos + input.y() + 5, 0xFF404040, false);
        }
    }

    private void drawFlowPageIndicator(
            GuiGraphics graphics,
            TerminalLayout.Rect panel,
            int page,
            int pageCount,
            int right
    ) {
        if (pageCount <= 1) return;
        String text = (Math.clamp(page, 0, pageCount - 1) + 1) + "/" + pageCount;
        graphics.drawString(font, text,
                leftPos + right - font.width(text),
                topPos + panel.y() + 5, 0xFF606060, false);
    }

    private void drawCenteredNoShadow(
            GuiGraphics graphics,
            String text,
            int centerX,
            int y,
            int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private static void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        graphics.fill(x, y, x + 17, y + 17, 0xFF8B8B8B);
        graphics.fill(x + 16, y, x + 17, y + 17, 0xFFFFFFFF);
        graphics.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
    }

    private void renderRecipePanel(GuiGraphics graphics) {
        TerminalLayout.Rect panel = geometry.workspace();
        drawInsetPanel(graphics, leftPos, topPos, panel);
        drawInsetPanel(graphics, leftPos, topPos, geometry.recipeInputRegion());
        TerminalLayout.Rect footer = geometry.recipeQuantityFooter();
        graphics.fill(
                leftPos + footer.x(), topPos + footer.y() - 1,
                leftPos + footer.right(), topPos + footer.y(), 0xFF555555);

        ItemStack selected = menu.getSelectedStack();
        if (selected.isEmpty()) {
            TerminalLayout.Rect inputRegion = geometry.recipeInputRegion();
            Component prompt = Component.translatable("gui.magic_storage.select_recipe_item");
            String visiblePrompt = font.plainSubstrByWidth(
                    prompt.getString(), Math.max(0, inputRegion.width() - 8));
            graphics.drawString(font, visiblePrompt,
                    leftPos + inputRegion.x() + Math.max(0,
                            (inputRegion.width() - font.width(visiblePrompt)) / 2),
                    topPos + inputRegion.y() + Math.max(0,
                            (inputRegion.height() - font.lineHeight) / 2),
                    0xFF606060, false);
            return;
        }

        int recipeCount = menu.getRecipeCount();
        TerminalLayout.Rect header = geometry.recipeHeader();
        TerminalLayout.Rect arrow = geometry.recipeArrow();
        TerminalLayout.Rect output = geometry.recipeOutput();
        int textRight = arrow.x() - 2;
        String recipePosition = recipeCount > 0
                ? (Math.clamp(menu.getCurrentRecipeIndex(), 0, recipeCount - 1) + 1) + " / " + recipeCount
                : "";
        int positionX = textRight - font.width(recipePosition);
        String stationType = "Station · " + menu.getCurrentRecipeTypeLabel();
        String visibleStation = font.plainSubstrByWidth(
                stationType, Math.max(0, positionX - header.x() - 2));
        graphics.drawString(font, visibleStation,
                leftPos + header.x(), topPos + header.y(), 0xFF505050, false);
        if (!recipePosition.isEmpty()) {
            graphics.drawString(font, recipePosition,
                    leftPos + positionX, topPos + header.y(), 0xFF505050, false);
        }
        String outputName = font.plainSubstrByWidth(
                selected.getHoverName().getString(), Math.max(0, textRight - header.x()));
        graphics.drawString(font, outputName,
                leftPos + header.x(), topPos + header.y() + font.lineHeight,
                0xFF202020, false);
        drawRecipeArrow(graphics, arrow, recipeCount > 0 ? 0xFF5A5A5A : 0xFF8A8A8A);
        drawSlotFrame(graphics, leftPos + output.x(), topPos + output.y());
        graphics.renderItem(selected.copyWithCount(1), leftPos + output.x(), topPos + output.y());

        if (recipeCount <= 0) {
            drawClippedString(graphics, Component.translatable("gui.magic_storage.no_recipe"),
                    geometry.recipeStatus(), 0xFF9A2020);
            return;
        }

        int craftable = menu.getCraftableCount();
        Component status = craftable > 0
                ? Component.translatable("gui.magic_storage.ready_to_craft")
                        .append(": ×").append(Integer.toString(craftable))
                : Component.translatable("gui.magic_storage.missing_energy_or_materials");
        int statusColor = craftable > 0 ? 0xFF2E7D32 : 0xFF9A2020;
        drawClippedString(graphics, status, geometry.recipeStatus(), statusColor);
        drawClippedString(graphics, Component.translatable("gui.magic_storage.available_required"),
                geometry.recipeAvailableHeader(), 0xFF505050);

        List<ResourceRow> resources = recipeResources();
        List<TerminalLayout.Rect> cells = geometry.recipeResourceCells();
        for (int index = 0; index < resources.size(); index++) {
            renderResourceRow(graphics, cells.get(index), resources.get(index));
        }
    }

    private void drawClippedString(
            GuiGraphics graphics,
            Component text,
            TerminalLayout.Rect bounds,
            int color
    ) {
        String visible = font.plainSubstrByWidth(text.getString(), bounds.width());
        graphics.drawString(font, visible,
                leftPos + bounds.x(), topPos + bounds.y(), color, false);
    }

    private void drawRecipeArrow(GuiGraphics graphics, TerminalLayout.Rect bounds, int color) {
        int iconX = leftPos + bounds.x()
                + (bounds.width() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
        int iconY = topPos + bounds.y()
                + (bounds.height() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
        drawArrowIcon(graphics, iconX, iconY, true, color);
    }

    private List<ResourceRow> recipeResources() {
        List<ResourceRow> resources = new java.util.ArrayList<>();
        for (CraftingTerminalMenu.IngredientPreview ingredient : menu.getIngredientPreview()) {
            resources.add(new ResourceRow(
                    ingredient.stack(), null, ingredient.available(), ingredient.required()));
        }
        for (CraftingTerminalMenu.EnergyPreview energy : menu.getEnergyPreview()) {
            resources.add(new ResourceRow(
                    ItemStack.EMPTY, energy.type(), energy.available(), energy.required()));
        }
        return resources;
    }

    private void renderResourceRow(
            GuiGraphics graphics,
            TerminalLayout.Rect cell,
            ResourceRow resource
    ) {
        int x = leftPos + cell.x();
        int y = topPos + cell.y();
        int bottom = y + cell.height();
        graphics.fill(x, y, x + cell.width(), bottom, 0x55373737);
        int iconY = y + Math.max(0, (cell.height() - 16) / 2);
        if (!resource.stack().isEmpty()) {
            graphics.renderItem(resource.stack(), x, iconY);
        } else if (resource.energyType() != null) {
            graphics.renderItem(resource.energyType().representativeStack(), x, iconY);
        }
        String amount = formatAmount(resource.available()) + "/" + formatAmount(resource.required());
        int textX = x + 18;
        String visible = font.plainSubstrByWidth(amount, Math.max(0, cell.width() - 20));
        graphics.drawString(font, visible, textX,
                y + Math.max(0, (cell.height() - font.lineHeight) / 2),
                resource.available() >= resource.required() ? 0xFF63B563 : 0xFFE06060, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFF404040, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, 0xFF404040, false);
        if (menu.getPage() == CraftingTerminalPage.FUEL) drawTypeCapacity(graphics);
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (menu.getPage().isItemPage()) {
            ItemStack selected = menu.getSelectedStack();
            if (!selected.isEmpty()
                    && geometry.recipeOutput().contains(mouseX - leftPos, mouseY - topPos)) {
                graphics.renderTooltip(font, selected, mouseX, mouseY);
                return;
            }
            ResourceRow resource = recipeResourceAt(mouseX, mouseY);
            if (resource != null) {
                Component name = resource.stack().isEmpty()
                        ? energyLabel(resource.energyType())
                        : resource.stack().getHoverName();
                graphics.renderComponentTooltip(font, List.of(
                        name,
                        Component.translatable("gui.magic_storage.available_amount",
                                formatAmount(resource.available())),
                        Component.translatable("gui.magic_storage.required_for_one",
                                formatAmount(resource.required()))
                ), mouseX, mouseY);
            }
            return;
        }

        int machineIndex = machineEnergyIndexAt(mouseX, mouseY);
        if (machineIndex >= 0) {
            ItemStack installed = menu.getSlot(
                    CraftingTerminalMenu.MACHINE_SLOT_START + machineIndex).getItem();
            MachineEnergyTable.Entry entry = MachineEnergyTable.get(machineIndex);
            if (entry == null) return;
            Component machineName = installed.isEmpty()
                    ? new ItemStack(entry.machine()).getHoverName()
                    : installed.getHoverName();
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(machineName);
            if (entry.generatesEnergy()) {
                int rate = installed.isEmpty() || !entry.accepts(installed)
                        ? 0 : installed.getCount() * entry.energyPerTick();
                tooltip.add(Component.translatable("tooltip.magic_storage.machine_rate", rate));
                tooltip.add(Component.translatable("tooltip.magic_storage.energy_stored",
                        menu.getEnergyAmount(entry.energyType())));
            }
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
            return;
        }

        int fuelIndex = storedFuelIndexAt(mouseX, mouseY);
        if (fuelIndex >= 0) {
            EnergyType type = CraftingTerminalMenu.fuelTargets().get(fuelIndex);
            graphics.renderComponentTooltip(font, List.of(
                    energyLabel(type),
                    Component.translatable("tooltip.magic_storage.energy_stored",
                            menu.getEnergyAmount(type))
            ), mouseX, mouseY);
            return;
        }

        TerminalLayout.Rect input = geometry.fuelInput();
        if (menu.getSlot(CraftingTerminalMenu.FUEL_INPUT_SLOT).getItem().isEmpty()
                && input.contains(mouseX - leftPos, mouseY - topPos)) {
            graphics.renderTooltip(font, Component.translatable("gui.magic_storage.fuel_input"), mouseX, mouseY);
        }
    }

    private ResourceRow recipeResourceAt(int mouseX, int mouseY) {
        List<ResourceRow> resources = recipeResources();
        List<TerminalLayout.Rect> cells = geometry.recipeResourceCells();
        for (int index = 0; index < resources.size(); index++) {
            TerminalLayout.Rect cell = cells.get(index);
            if (cell.contains(mouseX - leftPos, mouseY - topPos)) return resources.get(index);
        }
        return null;
    }

    private int machineEnergyIndexAt(int mouseX, int mouseY) {
        int first = machinePage * geometry.machineGrid().capacity();
        int visible = geometry.machineGrid().visibleCount(machinePage);
        List<TerminalLayout.Rect> cells = geometry.machineGrid().cells(machinePage);
        for (int index = 0; index < visible; index++) {
            TerminalLayout.Rect cell = cells.get(index);
            if (cell.contains(mouseX - leftPos, mouseY - topPos)) return first + index;
        }
        return -1;
    }

    private int storedFuelIndexAt(int mouseX, int mouseY) {
        int first = reservePage * geometry.reserveGrid().capacity();
        int visible = geometry.reserveGrid().visibleCount(reservePage);
        List<TerminalLayout.Rect> cells = geometry.reserveGrid().cells(reservePage);
        for (int index = 0; index < visible; index++) {
            TerminalLayout.Rect cell = cells.get(index);
            if (cell.contains(mouseX - leftPos, mouseY - topPos)) return first + index;
        }
        return -1;
    }

    private static void drawArrowIcon(
            GuiGraphics graphics,
            int x,
            int y,
            boolean right,
            int color
    ) {
        int centerY = y + TerminalLayout.ICON_CANVAS_SIZE / 2;
        if (right) {
            graphics.fill(x, centerY - 1, x + 8, centerY + 1, color);
        } else {
            graphics.fill(x + 4, centerY - 1,
                    x + TerminalLayout.ICON_CANVAS_SIZE, centerY + 1, color);
        }
        int tipX = right ? x + TerminalLayout.ICON_CANVAS_SIZE - 1 : x;
        for (int offset = -5; offset <= 5; offset++) {
            int width = 5 - Math.abs(offset);
            int left = right ? tipX - width : tipX;
            int rightEdge = right ? tipX + 1 : tipX + width + 1;
            graphics.fill(left, centerY + offset, rightEdge, centerY + offset + 1, color);
        }
    }

    private static void drawRailIcon(
            GuiGraphics graphics,
            int x,
            int y,
            RailIcon icon,
            int color
    ) {
        switch (icon) {
            case STORAGE -> {
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 3; column++) {
                        graphics.fill(x + column * 4, y + row * 4,
                                x + column * 4 + 3, y + row * 4 + 3, color);
                    }
                }
            }
            case FUEL -> {
                graphics.fill(x + 5, y, x + 7, y + 3, color);
                graphics.fill(x + 3, y + 2, x + 9, y + 7, color);
                graphics.fill(x + 1, y + 5, x + 11, y + 10, color);
                graphics.fill(x + 3, y + 9, x + 9, y + 12, color);
                graphics.fill(x + 5, y + 6, x + 7, y + 10, 0xFF555555);
            }
            case SORT_ASCENDING, SORT_DESCENDING -> {
                boolean ascending = icon == RailIcon.SORT_ASCENDING;
                int tipY = ascending ? y : y + 11;
                int baseY = ascending ? y + 11 : y;
                graphics.fill(x + 2, Math.min(tipY, baseY), x + 4, Math.max(tipY, baseY) + 1, color);
                graphics.fill(x, ascending ? y + 2 : y + 8,
                        x + 6, ascending ? y + 4 : y + 10, color);
                graphics.fill(x + 7, y + 1, x + 12, y + 3, color);
                graphics.fill(x + 7, y + 5, x + 11, y + 7, color);
                graphics.fill(x + 7, y + 9, x + 10, y + 11, color);
            }
            case SORT_NAME -> {
                graphics.fill(x, y, x + 12, y + 2, color);
                graphics.fill(x, y + 5, x + 9, y + 7, color);
                graphics.fill(x, y + 10, x + 6, y + 12, color);
            }
            case SORT_QUANTITY -> {
                graphics.fill(x, y + 8, x + 3, y + 12, color);
                graphics.fill(x + 4, y + 5, x + 7, y + 12, color);
                graphics.fill(x + 8, y, x + 12, y + 12, color);
            }
            case SORT_ID -> {
                graphics.fill(x, y, x + 2, y + 12, color);
                graphics.fill(x + 4, y, x + 10, y + 2, color);
                graphics.fill(x + 4, y + 10, x + 10, y + 12, color);
                graphics.fill(x + 9, y + 2, x + 12, y + 10, color);
            }
            case SEARCH -> {
                graphics.fill(x + 1, y + 1, x + 8, y + 3, color);
                graphics.fill(x + 1, y + 3, x + 3, y + 8, color);
                graphics.fill(x + 6, y + 3, x + 8, y + 8, color);
                graphics.fill(x + 3, y + 7, x + 8, y + 9, color);
                graphics.fill(x + 7, y + 8, x + 12, y + 12, color);
            }
            case SEARCH_TAG -> {
                graphics.fill(x + 3, y + 1, x + 5, y + 12, color);
                graphics.fill(x + 8, y + 1, x + 10, y + 12, color);
                graphics.fill(x + 1, y + 4, x + 12, y + 6, color);
                graphics.fill(x + 1, y + 8, x + 12, y + 10, color);
            }
            case SEARCH_MOD -> {
                graphics.fill(x + 2, y + 1, x + 10, y + 3, color);
                graphics.fill(x + 1, y + 2, x + 3, y + 10, color);
                graphics.fill(x + 2, y + 9, x + 10, y + 11, color);
                graphics.fill(x + 9, y + 2, x + 11, y + 9, color);
                graphics.fill(x + 4, y + 4, x + 9, y + 6, color);
                graphics.fill(x + 4, y + 5, x + 6, y + 9, color);
                graphics.fill(x + 7, y + 5, x + 9, y + 10, color);
            }
            case CRAFTABLE -> {
                graphics.fill(x, y + 5, x + 3, y + 8, color);
                graphics.fill(x + 2, y + 7, x + 6, y + 11, color);
                graphics.fill(x + 5, y + 5, x + 8, y + 9, color);
                graphics.fill(x + 7, y + 2, x + 12, y + 6, color);
            }
            case PLAYER_INVENTORY -> {
                graphics.fill(x + 2, y, x + 10, y + 2, color);
                graphics.fill(x, y + 2, x + 12, y + 12, color);
                graphics.fill(x + 2, y + 4, x + 10, y + 10, 0xFF555555);
                graphics.fill(x + 5, y + 6, x + 7, y + 9, color);
            }
        }
    }

    private static List<FuelTargetOption> buildFuelTargetOptions() {
        List<FuelTargetOption> options = new ArrayList<>();
        options.add(new FuelTargetOption(null));
        for (EnergyType target : CraftingTerminalMenu.fuelTargets()) {
            options.add(new FuelTargetOption(target));
        }
        return List.copyOf(options);
    }

    private static List<FuelTargetOption> fuelTargetOptions() {
        return FUEL_TARGET_OPTIONS;
    }

    private static Component energyLabel(EnergyType type) {
        return Component.translatable("gui.magic_storage.energy." + type.getId());
    }

    private static String formatAmount(long amount) {
        return TerminalAmountFormatter.formatCompact(amount);
    }

    private record ResourceRow(
            ItemStack stack,
            EnergyType energyType,
            long available,
            long required
    ) {
    }

    private record FuelTargetOption(EnergyType target) {
        private Component label() {
            return target == null
                    ? Component.translatable("gui.magic_storage.fuel_target_auto")
                    : energyLabel(target);
        }
    }

    private enum RailIcon {
        STORAGE,
        FUEL,
        SORT_ASCENDING,
        SORT_DESCENDING,
        SORT_NAME,
        SORT_QUANTITY,
        SORT_ID,
        SEARCH,
        SEARCH_TAG,
        SEARCH_MOD,
        CRAFTABLE,
        PLAYER_INVENTORY
    }

    private enum NavigationIcon {
        PREVIOUS,
        NEXT
    }

    private static final class RecipeNavigationButton extends Button {
        private final NavigationIcon icon;

        private RecipeNavigationButton(
                int x,
                int y,
                int width,
                int height,
                Component narration,
                OnPress onPress,
                NavigationIcon icon
        ) {
            super(x, y, width, height, narration, onPress, DEFAULT_NARRATION);
            this.icon = icon;
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            int iconX = getX() + (getWidth() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
            int iconY = getY() + (getHeight() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
            drawArrowIcon(graphics, iconX, iconY, icon == NavigationIcon.NEXT,
                    active ? 0xFFFFFFFF : 0xFF777777);
        }
    }

    private static final class TerminalIconButton extends Button {
        private RailIcon icon;

        private TerminalIconButton(
                int x,
                int y,
                int width,
                int height,
                Component narration,
                OnPress onPress,
                RailIcon icon
        ) {
            super(x, y, width, height, narration, onPress, DEFAULT_NARRATION);
            this.icon = icon;
        }

        private void setIcon(RailIcon icon) {
            this.icon = icon;
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            int iconX = getX() + (getWidth() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
            int iconY = getY() + (getHeight() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
            drawRailIcon(graphics, iconX, iconY, icon, active ? 0xFFFFFFFF : 0xFF777777);
        }
    }

    public List<Rect2i> getEmiExclusionAreas() {
        return terminalExclusionAreas();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (menu.getPage() == CraftingTerminalPage.FUEL && scrollY != 0) {
            if (fuelTargetSelector.visible && fuelTargetSelector.active
                    && fuelTargetSelector.isMouseOver(mouseX, mouseY)) {
                return fuelTargetSelector.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }
            int localX = (int) mouseX - leftPos;
            int localY = (int) mouseY - topPos;
            int direction = scrollY < 0 ? 1 : -1;
            if (geometry.machinePanel().contains(localX, localY)
                    && geometry.machineGrid().pageCount() > 1) {
                int next = Math.clamp(machinePage + direction, 0, geometry.machineGrid().pageCount() - 1);
                if (next != machinePage) {
                    machinePage = next;
                    repositionFuelSlots();
                }
                return true;
            }
            if (geometry.fuelPanel().contains(localX, localY)
                    && geometry.reserveGrid().pageCount() > 1) {
                reservePage = Math.clamp(
                        reservePage + direction, 0, geometry.reserveGrid().pageCount() - 1);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled && getFocused() instanceof AbstractButton) {
            setFocused(null);
            setDragging(false);
        }
        return handled;
    }
}
