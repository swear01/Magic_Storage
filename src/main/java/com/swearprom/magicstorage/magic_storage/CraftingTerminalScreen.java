package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CraftingTerminalScreen extends StorageTerminalScreen<CraftingTerminalMenu> {

    private static final List<FuelTargetOption> FUEL_TARGET_OPTIONS = buildFuelTargetOptions();
    private static final int ITEM_ROW_BACKGROUND = 0xFFB8B8B8;
    private static final int ITEM_ROW_BORDER = 0xFF707070;
    private static final int ENERGY_ROW_BACKGROUND = 0xFF411B1B;
    private static final int ENERGY_ROW_BORDER = 0xFF9A4545;
    private static final int TOOL_ROW_BACKGROUND = 0xFF381515;
    private static final int TOOL_ROW_BORDER = 0xFF873737;

    private final NativeRecipeDiagramRenderer nativeRecipeDiagramRenderer;
    private final RecipeDiagramRenderer preferredRecipeDiagramRenderer;
    private Button prevRecipeBtn;
    private Button nextRecipeBtn;
    private Button craft1Btn;
    private Button craft8Btn;
    private Button craft64Btn;
    private Button craftMaxBtn;
    private TerminalIconButton storagePageBtn;
    private TerminalIconButton craftablePageBtn;
    private TerminalIconButton fuelPageBtn;
    private TerminalCycleButton playerInventoryRailBtn;
    private TerminalCycleButton outputDestinationRailBtn;
    private TerminalCycleButton fuelTargetSelector;
    private TerminalIconButton fuelTargetListBtn;
    private FuelTargetPopup fuelTargetPopup;
    private CraftingTerminalPage lastPage;
    private EnergyType lastFuelTarget;
    private int machinePage;
    private int reservePage;
    private RecipeDiagramRenderer.Geometry recipeDiagramGeometry;
    private int lastRecipeMouseX;
    private int lastRecipeMouseY;

    public CraftingTerminalScreen(CraftingTerminalMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        nativeRecipeDiagramRenderer = new NativeRecipeDiagramRenderer();
        preferredRecipeDiagramRenderer = ClientSetup.createRecipeDiagramRenderer();
    }

    @Override
    protected TerminalProfile terminalProfile() {
        return TerminalProfile.CRAFTING;
    }

    @Override
    protected int layoutMachineCount() {
        return MachineEnergyTable.entries().size();
    }

    @Override
    protected int layoutReserveCount() {
        return CraftingTerminalMenu.fuelTargets().size();
    }

    @Override
    protected void addTerminalProfileControls() {
        storagePageBtn = addItemButton(
                MagicStorage.STORAGE_TERMINAL_ITEM.get().getDefaultInstance(),
                Component.translatable("gui.magic_storage.page_storage"),
                geometry.railButtons().get(0),
                button -> clickMenuButton(CraftingTerminalMenu.STORAGE_PAGE_BUTTON));
        craftablePageBtn = addItemButton(
                Items.CRAFTING_TABLE.getDefaultInstance(),
                Component.translatable("gui.magic_storage.page_craftable"),
                geometry.railButtons().get(1),
                button -> clickMenuButton(CraftingTerminalMenu.CRAFTABLE_PAGE_BUTTON));
        fuelPageBtn = addItemButton(
                Items.COAL.getDefaultInstance(),
                Component.translatable("gui.magic_storage.page_fuel"),
                geometry.railButtons().get(2),
                button -> clickMenuButton(CraftingTerminalMenu.FUEL_PAGE_BUTTON));
        playerInventoryRailBtn = addItemCycleButton(
                Items.CHEST.getDefaultInstance(),
                Component.translatable("gui.magic_storage.use_player_inv"),
                geometry.railButtons().get(terminalProfile().playerInventorySourceIndex()),
                direction -> clickMenuButton(7));
        outputDestinationRailBtn = addItemCycleButton(
                MagicStorage.STORAGE_CORE_ITEM.get().getDefaultInstance(),
                Component.translatable("gui.magic_storage.output_destination"),
                geometry.railButtons().get(terminalProfile().outputDestinationIndex()),
                direction -> clickMenuButton(CraftingTerminalMenu.OUTPUT_DESTINATION_BUTTON));
    }

    @Override
    protected boolean isItemViewActive() {
        return menu.getPage().isItemPage();
    }

    @Override
    protected void init() {
        super.init();
        recipeDiagramGeometry = createRecipeDiagramGeometry();
        repositionFuelSlots();
        List<TerminalLayout.Rect> navigationButtons = geometry.recipeNavigationButtons();
        prevRecipeBtn = addRecipeNavigationButton(
                TerminalControlIcon.PREVIOUS,
                Component.literal("Previous recipe"),
                navigationButtons.get(0),
                button -> clickMenuButton(8));
        nextRecipeBtn = addRecipeNavigationButton(
                TerminalControlIcon.NEXT,
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

        TerminalLayout.Rect targetBounds = geometry.fuelTargetSelector();
        FuelTargetOption initialTarget = new FuelTargetOption(menu.getSelectedFuelTarget());
        fuelTargetSelector = addTextCycleButton(
                initialTarget.label(), targetBounds, this::selectAdjacentFuelTarget);
        fuelTargetListBtn = addItemButton(
                Items.BOOK.getDefaultInstance(),
                Component.translatable("gui.magic_storage.fuel_target_list"),
                geometry.fuelTargetListButton(),
                button -> toggleFuelTargetPopup());
        TerminalLayout.PopupList popup = geometry.fuelTargetPopup();
        fuelTargetPopup = new FuelTargetPopup(
                leftPos + popup.bounds().x(), topPos + popup.bounds().y(), popup);
        fuelTargetPopup.visible = false;
        fuelTargetPopup.active = false;
        addRenderableWidget(fuelTargetPopup);

        updatePageWidgets();
        updateSidebarState();
    }

    private RecipeDiagramRenderer.Geometry createRecipeDiagramGeometry() {
        return new RecipeDiagramRenderer.Geometry(
                recipeRect(geometry.recipeDiagram()),
                geometry.recipeInputSlots().stream().map(CraftingTerminalScreen::recipeRect).toList(),
                recipeRect(geometry.recipeArrow()),
                recipeRect(geometry.recipeOutput()),
                recipeRect(geometry.recipeStation()),
                recipeRect(geometry.recipeShapelessMarker()));
    }

    private static RecipeDiagramRenderer.Rect recipeRect(TerminalLayout.Rect bounds) {
        return new RecipeDiagramRenderer.Rect(
                bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private RecipeDiagramRenderer activeRecipeDiagramRenderer(RecipePresentation presentation) {
        return preferredRecipeDiagramRenderer.supports(presentation, recipeDiagramGeometry)
                ? preferredRecipeDiagramRenderer : nativeRecipeDiagramRenderer;
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
            TerminalControlIcon icon,
            Component narration,
            TerminalLayout.Rect bounds,
            Button.OnPress action
    ) {
        return addIconButton(icon, narration, bounds, action);
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
            TerminalLayout.Rect slot = TerminalLayout.centeredSlot(cell);
            replaceSlot(CraftingTerminalMenu.MACHINE_SLOT_START + machineSlot,
                    slot.x(), slot.y());
        }
    }

    private void clickMenuButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private void selectAdjacentFuelTarget(TerminalCycleDirection direction) {
        List<FuelTargetOption> options = fuelTargetOptions();
        int current = options.indexOf(new FuelTargetOption(menu.getSelectedFuelTarget()));
        if (current < 0) throw new IllegalStateException("Current Fuel target is not server-approved");
        int offset = direction == TerminalCycleDirection.NEXT ? 1 : -1;
        FuelTargetOption option = options.get(Math.floorMod(current + offset, options.size()));
        clickMenuButton(option.target() == null
                ? CraftingTerminalMenu.AUTO_FUEL_TARGET_BUTTON
                : CraftingTerminalMenu.fuelTargetButtonId(option.target()));
    }

    private void toggleFuelTargetPopup() {
        if (fuelTargetPopup.visible) {
            closeFuelTargetPopup();
            return;
        }
        int selected = fuelTargetOptions().indexOf(new FuelTargetOption(menu.getSelectedFuelTarget()));
        if (selected < 0) throw new IllegalStateException("Current Fuel target is not server-approved");
        fuelTargetPopup.reveal(selected);
        fuelTargetPopup.visible = true;
        fuelTargetPopup.active = true;
        setFocused(null);
    }

    private void closeFuelTargetPopup() {
        if (fuelTargetPopup == null) return;
        fuelTargetPopup.visible = false;
        fuelTargetPopup.active = false;
        setFocused(null);
    }

    private void selectFuelTarget(FuelTargetOption option) {
        clickMenuButton(option.target() == null
                ? CraftingTerminalMenu.AUTO_FUEL_TARGET_BUTTON
                : CraftingTerminalMenu.fuelTargetButtonId(option.target()));
        closeFuelTargetPopup();
    }

    private void updatePageWidgets() {
        CraftingTerminalPage page = menu.getPage();
        EnergyType target = menu.getSelectedFuelTarget();
        boolean itemPage = page.isItemPage();
        setItemViewControlsVisible(itemPage);

        setWidgetVisible(prevRecipeBtn, itemPage);
        setWidgetVisible(nextRecipeBtn, itemPage);
        setWidgetVisible(craft1Btn, itemPage);
        setWidgetVisible(craft8Btn, itemPage);
        setWidgetVisible(craft64Btn, itemPage);
        setWidgetVisible(craftMaxBtn, itemPage);
        setWidgetVisible(playerInventoryRailBtn, itemPage);
        setWidgetVisible(outputDestinationRailBtn, itemPage);

        storagePageBtn.active = page != CraftingTerminalPage.STORAGE;
        craftablePageBtn.active = page != CraftingTerminalPage.CRAFTABLE;
        fuelPageBtn.active = page != CraftingTerminalPage.FUEL;

        boolean fuel = page == CraftingTerminalPage.FUEL;
        setWidgetVisible(fuelTargetSelector, fuel);
        setWidgetVisible(fuelTargetListBtn, fuel);
        if (!fuel) closeFuelTargetPopup();
        if (fuel) {
            fuelTargetSelector.active = fuelTargetOptions().size() > 1;
            FuelTargetOption option = new FuelTargetOption(target);
            fuelTargetSelector.setMessage(option.label());
            fuelTargetSelector.setTooltip(createCycleTooltip(
                    "gui.magic_storage.fuel_target", option.label()));
        }
        updateCraftButtonState();
        lastPage = page;
        lastFuelTarget = target;
    }

    private void updateSidebarState() {
        storagePageBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.page_storage")));
        craftablePageBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.page_craftable")));
        fuelPageBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.page_fuel")));
        fuelTargetListBtn.setTooltip(Tooltip.create(Component.translatable("gui.magic_storage.fuel_target_list")));

        updateToggleButton(playerInventoryRailBtn, "gui.magic_storage.use_player_inv",
                menu.isUsePlayerInventory());
        Component outputDestination = switch (menu.getOutputDestination()) {
            case PLAYER -> Component.translatable("gui.magic_storage.output_destination.player");
            case STORAGE -> Component.translatable("gui.magic_storage.output_destination.storage");
        };
        updateCycleTooltip(outputDestinationRailBtn, "gui.magic_storage.output_destination",
                outputDestination);
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

    private void updateToggleButton(TerminalCycleButton button, String key, boolean enabled) {
        updateCycleTooltip(button, key, Component.translatable(enabled
                ? "gui.magic_storage.state_on"
                : "gui.magic_storage.state_off"));
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
            renderRecipePanel(graphics, partialTick, mouseX, mouseY);
        }
        renderSideRail(graphics);
    }

    private void renderSideRail(GuiGraphics graphics) {
        boolean itemPage = menu.getPage().isItemPage();
        boolean fuelPage = menu.getPage() == CraftingTerminalPage.FUEL;
        if (fuelPage) {
            drawRaisedPanel(graphics, leftPos, topPos, geometry.fuelRailPanel());
        }
        drawRailMarker(graphics, menu.getPage().ordinal(), fuelPage, 0xFF7A5B18);
        if (itemPage) {
            if (menu.isUsePlayerInventory()) {
                drawRailMarker(graphics, terminalProfile().playerInventorySourceIndex(), false, 0xFF2E7D32);
            }
            if (menu.getOutputDestination() == TerminalOutputDestination.STORAGE) {
                drawRailMarker(graphics, terminalProfile().outputDestinationIndex(), false, 0xFF1565C0);
            }
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
            TerminalLayout.Rect slot = TerminalLayout.centeredSlot(cell);
            int slotX = leftPos + slot.x();
            int slotY = topPos + slot.y();
            drawSlotFrame(graphics, slotX, slotY);
            if (entry.generatesEnergy()) {
                drawCenteredNoShadow(graphics,
                        formatAmount(menu.getEnergyAmount(entry.energyType())),
                        leftPos + cell.x() + cell.width() / 2,
                        topPos + cell.bottom() - font.lineHeight - 1, 0xFF202020);
            } else if (entry.category() == MachineEnergyTable.Category.CONSUMABLE) {
                drawCenteredNoShadow(graphics,
                        menu.hasInfiniteAxeEnergy() ? "∞" : formatAmount(menu.getAxeEnergyAmount()),
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
            TerminalLayout.Rect icon = TerminalLayout.centeredIcon(cell);
            int center = leftPos + cell.x() + cell.width() / 2;
            graphics.renderItem(type.representativeStack(), leftPos + icon.x(), topPos + icon.y());
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

    private void renderRecipePanel(
            GuiGraphics graphics,
            float partialTick,
            int mouseX,
            int mouseY
    ) {
        TerminalLayout.Rect panel = geometry.workspace();
        drawInsetPanel(graphics, leftPos, topPos, panel);
        TerminalLayout.Rect diagram = geometry.recipeDiagram();
        TerminalLayout.Rect ledger = geometry.recipeLedger();
        TerminalLayout.Rect footer = geometry.recipeFooter();
        graphics.fill(
                leftPos + diagram.x(), topPos + diagram.y(),
                leftPos + diagram.right(), topPos + diagram.bottom(), 0xFFE2E2E2);
        graphics.fill(
                leftPos + ledger.x(), topPos + ledger.y(),
                leftPos + ledger.right(), topPos + ledger.bottom(), 0xFFCACACA);
        graphics.fill(
                leftPos + footer.x(), topPos + footer.y() - 1,
                leftPos + footer.right(), topPos + footer.y(), 0xFF555555);

        RecipePresentation presentation = menu.getRecipePresentation();
        if (presentation.isEmpty()) {
            Component prompt = Component.translatable("gui.magic_storage.select_recipe_item");
            String visiblePrompt = font.plainSubstrByWidth(
                    prompt.getString(), Math.max(0, diagram.width() - 8));
            graphics.drawString(font, visiblePrompt,
                    leftPos + diagram.x() + Math.max(0,
                            (diagram.width() - font.width(visiblePrompt)) / 2),
                    topPos + diagram.y() + Math.max(0,
                            (diagram.height() - font.lineHeight) / 2),
                    0xFF606060, false);
            return;
        }

        lastRecipeMouseX = mouseX;
        lastRecipeMouseY = mouseY;
        activeRecipeDiagramRenderer(presentation).render(
                graphics,
                font,
                presentation,
                recipeDiagramGeometry,
                leftPos,
                topPos,
                mouseX,
                mouseY,
                partialTick);

        int recipeCount = menu.getRecipeCount();
        String recipePosition = recipeCount <= 0 ? "" :
                (Math.clamp(menu.getCurrentRecipeIndex(), 0, recipeCount - 1) + 1) + "/" + recipeCount;
        if (!recipePosition.isEmpty()) {
            graphics.drawString(font, recipePosition,
                    leftPos + diagram.right() - font.width(recipePosition),
                    topPos + diagram.y() + 1, 0xFF505050, false);
        }

        List<RecipePresentation.Resource> resources = presentation.resources();
        List<TerminalLayout.Rect> cells = geometry.recipeLedgerCells(resources.size());
        for (int index = 0; index < resources.size(); index++) {
            renderResourceRow(graphics, cells.get(index), resources.get(index));
        }
    }

    private void renderResourceRow(
            GuiGraphics graphics,
            TerminalLayout.Rect cell,
            RecipePresentation.Resource resource
    ) {
        int x = leftPos + cell.x();
        int y = topPos + cell.y();
        int bottom = y + cell.height();
        int background = switch (resource.kind()) {
            case ITEM -> ITEM_ROW_BACKGROUND;
            case ENERGY -> ENERGY_ROW_BACKGROUND;
            case TOOL -> TOOL_ROW_BACKGROUND;
        };
        int border = switch (resource.kind()) {
            case ITEM -> ITEM_ROW_BORDER;
            case ENERGY -> ENERGY_ROW_BORDER;
            case TOOL -> TOOL_ROW_BORDER;
        };
        graphics.fill(x, y, x + cell.width(), bottom, border);
        graphics.fill(x + 1, y + 1, x + cell.width() - 1, bottom - 1, background);
        int iconY = y + Math.max(0, (cell.height() - 16) / 2);
        ItemStack icon = resource.kind() == RecipePresentation.ResourceKind.ENERGY
                ? resource.energyType().representativeStack() : resource.stack();
        graphics.renderItem(icon, x + 1, iconY);
        String available = resource.infinite() ? "∞" : formatAmount(resource.available());
        String amount = available + "/" + formatAmount(resource.required());
        int textX = x + 18;
        String visible = font.plainSubstrByWidth(amount, Math.max(0, cell.width() - 20));
        graphics.drawString(font, visible, textX,
                y + Math.max(0, (cell.height() - font.lineHeight) / 2),
                resource.infinite() || resource.available() >= resource.required()
                        ? 0xFF63B563 : 0xFFE06060, false);
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
        if (fuelTargetPopup != null && fuelTargetPopup.visible
                && fuelTargetPopup.isMouseOver(mouseX, mouseY)) return;
        super.renderTooltip(graphics, mouseX, mouseY);
        if (menu.getPage().isItemPage()) {
            RecipePresentation presentation = menu.getRecipePresentation();
            if (presentation.isEmpty()) return;
            if (activeRecipeDiagramRenderer(presentation).renderTooltip(
                    graphics,
                    font,
                    presentation,
                    recipeDiagramGeometry,
                    leftPos,
                    topPos,
                    mouseX,
                    mouseY)) {
                return;
            }
            RecipePresentation.Resource resource = recipeResourceAt(
                    presentation, mouseX, mouseY);
            if (resource != null) {
                Component name = resource.kind() == RecipePresentation.ResourceKind.ENERGY
                        ? energyLabel(resource.energyType()) : resource.stack().getHoverName();
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
            } else if (entry.category() == MachineEnergyTable.Category.CONSUMABLE) {
                tooltip.set(0, Component.translatable("gui.magic_storage.axe_energy"));
                Object amount = menu.hasInfiniteAxeEnergy() ? "∞" : menu.getAxeEnergyAmount();
                tooltip.add(Component.translatable("tooltip.magic_storage.energy_stored",
                        amount));
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

    private RecipePresentation.Resource recipeResourceAt(
            RecipePresentation presentation,
            int mouseX,
            int mouseY
    ) {
        List<RecipePresentation.Resource> resources = presentation.resources();
        List<TerminalLayout.Rect> cells = geometry.recipeLedgerCells(resources.size());
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
            if (TerminalLayout.centeredSlot(cell).contains(
                    mouseX - leftPos, mouseY - topPos)) return first + index;
        }
        return -1;
    }

    private int storedFuelIndexAt(int mouseX, int mouseY) {
        int first = reservePage * geometry.reserveGrid().capacity();
        int visible = geometry.reserveGrid().visibleCount(reservePage);
        List<TerminalLayout.Rect> cells = geometry.reserveGrid().cells(reservePage);
        for (int index = 0; index < visible; index++) {
            TerminalLayout.Rect cell = cells.get(index);
            if (TerminalLayout.centeredIcon(cell).contains(
                    mouseX - leftPos, mouseY - topPos)) return first + index;
        }
        return -1;
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

    private final class FuelTargetPopup extends Button {
        private final TerminalLayout.PopupList popupGeometry;
        private int scrollOffset;

        private FuelTargetPopup(
                int x,
                int y,
                TerminalLayout.PopupList popupGeometry
        ) {
            super(x, y, popupGeometry.bounds().width(), popupGeometry.bounds().height(),
                    Component.translatable("gui.magic_storage.fuel_target_list"),
                    button -> { }, DEFAULT_NARRATION);
            this.popupGeometry = popupGeometry;
        }

        private void reveal(int optionIndex) {
            if (optionIndex < scrollOffset) {
                scrollOffset = optionIndex;
            } else if (optionIndex >= scrollOffset + popupGeometry.capacity()) {
                scrollOffset = optionIndex - popupGeometry.capacity() + 1;
            }
            scrollOffset = popupGeometry.clampScrollOffset(scrollOffset);
        }

        @Override
        protected boolean isValidClickButton(int button) {
            return button == 0;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            int localX = (int) mouseX - leftPos;
            int localY = (int) mouseY - topPos;
            List<TerminalLayout.Rect> rows = popupGeometry.rows(scrollOffset);
            for (int row = 0; row < rows.size(); row++) {
                if (rows.get(row).contains(localX, localY)) {
                    selectFuelTarget(fuelTargetOptions().get(scrollOffset + row));
                    return;
                }
            }
        }

        @Override
        public boolean mouseScrolled(
                double mouseX,
                double mouseY,
                double scrollX,
                double scrollY
        ) {
            if (!active || !visible || !isMouseOver(mouseX, mouseY) || scrollY == 0.0) return false;
            int direction = scrollY < 0 ? 1 : -1;
            scrollOffset = popupGeometry.clampScrollOffset(scrollOffset + direction);
            return true;
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics,
                int mouseX,
                int mouseY,
                float partialTick
        ) {
            drawRaisedPanel(graphics, leftPos, topPos, popupGeometry.bounds());
            List<FuelTargetOption> options = fuelTargetOptions();
            List<TerminalLayout.Rect> rows = popupGeometry.rows(scrollOffset);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                FuelTargetOption option = options.get(scrollOffset + rowIndex);
                TerminalLayout.Rect row = rows.get(rowIndex);
                boolean selected = Objects.equals(option.target(), menu.getSelectedFuelTarget());
                int left = leftPos + row.x();
                int top = topPos + row.y();
                int right = left + row.width();
                int bottom = top + row.height();
                graphics.fill(left, top, right, bottom, selected ? 0xFF315B78 : 0xFF8B8B8B);
                graphics.fill(left + 1, top + 1, right - 1, bottom - 1,
                        selected ? 0xFF5B89A8 : 0xFFC6C6C6);
                ItemStack icon = option.icon();
                graphics.renderItem(icon, left + 2, top + (row.height() - 16) / 2);
                String label = font.plainSubstrByWidth(
                        option.label().getString(), Math.max(0, row.width() - 24));
                graphics.drawString(font, label, left + 21,
                        top + Math.max(0, (row.height() - font.lineHeight) / 2),
                        selected ? 0xFFFFFFFF : 0xFF303030, false);
            }

            if (popupGeometry.maxScrollOffset() > 0) {
                TerminalLayout.Rect bounds = popupGeometry.bounds();
                int trackTop = topPos + bounds.y() + 2;
                int trackHeight = bounds.height() - 4;
                int thumbHeight = Math.max(6,
                        trackHeight * popupGeometry.capacity() / popupGeometry.itemCount());
                int travel = trackHeight - thumbHeight;
                int thumbTop = trackTop + travel * scrollOffset / popupGeometry.maxScrollOffset();
                int trackX = leftPos + bounds.right() - 2;
                graphics.fill(trackX, trackTop, trackX + 1, trackTop + trackHeight, 0xFF555555);
                graphics.fill(trackX - 1, thumbTop, trackX + 1, thumbTop + thumbHeight, 0xFFD8D8D8);
            }
        }
    }

    private static Component energyLabel(EnergyType type) {
        return Component.translatable("gui.magic_storage.energy." + type.getId());
    }

    private static String formatAmount(long amount) {
        return TerminalAmountFormatter.formatCompact(amount);
    }

    private record FuelTargetOption(EnergyType target) {
        private ItemStack icon() {
            return target == null
                    ? Items.COMPARATOR.getDefaultInstance()
                    : target.representativeStack().copyWithCount(1);
        }

        private Component label() {
            return target == null
                    ? Component.translatable("gui.magic_storage.fuel_target_auto")
                    : energyLabel(target);
        }
    }

    public List<Rect2i> getEmiExclusionAreas() {
        List<Rect2i> result = terminalExclusionAreas();
        if (fuelTargetPopup != null && fuelTargetPopup.visible) {
            TerminalLayout.Rect popup = geometry.fuelTargetPopup().bounds();
            result.add(new Rect2i(
                    leftPos + popup.x(), topPos + popup.y(), popup.width(), popup.height()));
        }
        return List.copyOf(result);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (fuelTargetPopup != null && fuelTargetPopup.visible) {
            if (fuelTargetPopup.isMouseOver(mouseX, mouseY)) {
                if (button == 0) fuelTargetPopup.onClick(mouseX, mouseY);
                return true;
            }
            if (!fuelTargetListBtn.isMouseOver(mouseX, mouseY)) {
                closeFuelTargetPopup();
                return true;
            }
        }
        if (menu.getPage().isItemPage()) {
            RecipePresentation presentation = menu.getRecipePresentation();
            if (!presentation.isEmpty()
                    && activeRecipeDiagramRenderer(presentation).mouseClicked(
                    presentation,
                    recipeDiagramGeometry,
                    leftPos,
                    topPos,
                    mouseX,
                    mouseY,
                    button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (fuelTargetPopup != null && fuelTargetPopup.visible && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeFuelTargetPopup();
            return true;
        }
        if (menu.getPage().isItemPage()) {
            RecipePresentation presentation = menu.getRecipePresentation();
            if (!presentation.isEmpty()
                    && activeRecipeDiagramRenderer(presentation).keyPressed(
                    presentation,
                    recipeDiagramGeometry,
                    leftPos,
                    topPos,
                    lastRecipeMouseX,
                    lastRecipeMouseY,
                    keyCode,
                    scanCode,
                    modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (menu.getPage() == CraftingTerminalPage.FUEL && scrollY != 0) {
            if (fuelTargetPopup.visible && fuelTargetPopup.isMouseOver(mouseX, mouseY)) {
                return fuelTargetPopup.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }
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
}
