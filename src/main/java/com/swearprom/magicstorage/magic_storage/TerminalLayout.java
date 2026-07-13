package com.swearprom.magicstorage.magic_storage;

import java.util.ArrayList;
import java.util.List;

final class TerminalLayout {
    static final int SLOT_SIZE = 18;
    static final int CONTROL_SIZE = SLOT_SIZE;
    static final int ICON_CANVAS_SIZE = 12;
    static final int TOP_HEIGHT = 19;
    static final int MIN_STORAGE_ROWS = 3;
    static final int MIN_CRAFTING_ROWS = 1;
    static final int MAX_ROWS = 9;
    static final int RAIL_GROUP_GAP = 6;

    private static final int NARROW_WIDTH = 210;
    private static final int SIDE_BY_SIDE_MIN_WIDTH = 360;
    private static final int SIDE_BY_SIDE_MAX_WIDTH = 390;
    private static final int STORAGE_BOTTOM_HEIGHT = 99;
    private static final int PLAYER_INVENTORY_HEIGHT = 4 * SLOT_SIZE + 4;
    private static final int OUTER_MARGIN = 16;
    private static final int PREFERRED_VERTICAL_MARGIN = 24;
    private static final int NARROW_MIN_VERTICAL_MARGIN = 4;
    private static final int NARROW_WORKSPACE_HEIGHT = 104;
    private static final int WORKSPACE_PLAYER_GAP = 2;
    private static final int PANEL_HEADER_HEIGHT = 15;
    private static final int FLOW_CELL_HEIGHT = SLOT_SIZE + 11;
    private static final int MACHINE_FLOW_ROWS = 2;
    private static final int MACHINE_PANEL_HEIGHT = PANEL_HEADER_HEIGHT
            + MACHINE_FLOW_ROWS * FLOW_CELL_HEIGHT + 2;
    private static final int FUEL_PANEL_HEIGHT = 50;
    private static final int FUEL_PANEL_GAP = 4;
    private static final int RAIL_BUTTON_SIZE = CONTROL_SIZE;
    private static final int RAIL_SPACING = 2;
    private static final int RAIL_TOP = 6;
    private static final int RAIL_FRAME_GAP = 1;
    private static final int RESOURCE_COUNT = 9;
    private static final int MACHINE_CELL_MIN_WIDTH = SLOT_SIZE * 2 - 2;
    private static final int MACHINE_CELL_PREFERRED_WIDTH = SLOT_SIZE * 4;
    private static final int RESERVE_CELL_MIN_WIDTH = SLOT_SIZE * 2 + 2;
    private static final int RECIPE_INSET = 4;
    private static final int RECIPE_SUMMARY_HEIGHT = 9;
    private static final int RECIPE_CELL_GAP = 2;
    private static final int CONTROL_GAP = 2;
    private static final int FUEL_CONTROL_INPUT_OFFSET = CONTROL_SIZE * 2 + 8;

    record Rect(int x, int y, int width, int height) {
        Rect {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Rectangle dimensions must be non-negative");
            }
        }

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(int pointX, int pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }

        boolean overlaps(Rect other) {
            return x < other.right() && right() > other.x
                    && y < other.bottom() && bottom() > other.y;
        }
    }

    record FlowGrid(
            Rect bounds,
            List<Rect> cells,
            int columns,
            int rows,
            int preferredCellWidth,
            int capacity,
            int itemCount
    ) {
        FlowGrid {
            cells = List.copyOf(cells);
        }

        int pageCount() {
            return Math.max(1, (itemCount + capacity - 1) / capacity);
        }

        int visibleCount(int page) {
            int first = Math.clamp(page, 0, pageCount() - 1) * capacity;
            return Math.max(0, Math.min(capacity, itemCount - first));
        }

        List<Rect> cells(int page) {
            return flowCells(bounds, visibleCount(page), columns, rows, preferredCellWidth);
        }
    }

    record Geometry(
            boolean wide,
            int imageWidth,
            int imageHeight,
            int visibleRows,
            Rect itemGrid,
            Rect searchBox,
            Rect searchBackground,
            Rect scrollbar,
            Rect playerInventory,
            Rect workspace,
            Rect recipeHeader,
            Rect recipeInputRegion,
            Rect recipeArrow,
            Rect recipeOutput,
            Rect recipeAvailableHeader,
            Rect recipeStatus,
            Rect recipeQuantityFooter,
            List<Rect> recipeResourceCells,
            List<Rect> recipeNavigationButtons,
            List<Rect> recipeCraftButtons,
            Rect machinePanel,
            Rect fuelPanel,
            Rect fuelControlPanel,
            Rect fuelTargetSelector,
            Rect fuelInput,
            FlowGrid machineGrid,
            FlowGrid reserveGrid,
            List<Rect> railButtons,
            List<Rect> fuelRailButtons,
            Rect railBounds,
            Rect railPanel,
            Rect fuelRailPanel,
            List<Rect> exclusionRects
    ) {
        Geometry {
            recipeResourceCells = List.copyOf(recipeResourceCells);
            recipeNavigationButtons = List.copyOf(recipeNavigationButtons);
            recipeCraftButtons = List.copyOf(recipeCraftButtons);
            railButtons = List.copyOf(railButtons);
            fuelRailButtons = List.copyOf(fuelRailButtons);
            exclusionRects = List.copyOf(exclusionRects);
        }

        int centeredFrameLeft(int screenWidth) {
            int groupLeft = Math.min(0, railPanel.x());
            int groupRight = Math.max(imageWidth, railPanel.right());
            return (screenWidth - (groupRight - groupLeft)) / 2 - groupLeft;
        }

        Rect frame() {
            return exclusionRects.getFirst();
        }
    }

    private TerminalLayout() {
    }

    static Geometry storage(int screenWidth, int screenHeight, int railButtonCount) {
        int rows = fixedBandRows(screenHeight, STORAGE_BOTTOM_HEIGHT, MIN_STORAGE_ROWS);
        int imageHeight = TOP_HEIGHT + STORAGE_BOTTOM_HEIGHT + rows * SLOT_SIZE;
        Rect itemGrid = new Rect(8, TOP_HEIGHT, 9 * SLOT_SIZE, rows * SLOT_SIZE);
        Rect playerInventory = new Rect(
                8, itemGrid.bottom() + 14, 9 * SLOT_SIZE, PLAYER_INVENTORY_HEIGHT);
        List<Rect> railButtons = railButtons(imageHeight, List.of(railButtonCount));
        Rect railBounds = boundsOf(railButtons);
        Rect railPanel = inflate(railBounds, 3);
        Rect empty = new Rect(0, 0, 0, 0);
        FlowGrid emptyFlow = emptyFlow();
        return new Geometry(
                false,
                NARROW_WIDTH,
                imageHeight,
                rows,
                itemGrid,
                new Rect(102, 6, 70, 9),
                new Rect(100, 4, 72, 12),
                new Rect(174, TOP_HEIGHT, 12, rows * SLOT_SIZE),
                playerInventory,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                List.of(),
                List.of(),
                List.of(),
                empty,
                empty,
                empty,
                empty,
                empty,
                emptyFlow,
                emptyFlow,
                railButtons,
                railButtons,
                railBounds,
                railPanel,
                railPanel,
                List.of(new Rect(0, 0, NARROW_WIDTH, imageHeight), railPanel)
        );
    }

    static Geometry crafting(int screenWidth, int screenHeight, int machineCount, int reserveCount) {
        if (machineCount < 0 || reserveCount < 0) {
            throw new IllegalArgumentException("Flow item counts must be non-negative");
        }

        int wideRows = fixedBandRows(screenHeight, STORAGE_BOTTOM_HEIGHT, MIN_STORAGE_ROWS);
        int wideItemBottom = TOP_HEIGHT + wideRows * SLOT_SIZE;
        int widePlayerY = Math.max(wideItemBottom + 14, TOP_HEIGHT + fuelGroupHeight());
        int wideHeight = widePlayerY + PLAYER_INVENTORY_HEIGHT + 8;
        RailGeometry wideRails = craftingRails(wideHeight);
        int availableSideBySideWidth = screenWidth - 2 * OUTER_MARGIN
                - railReservedWidth(wideRails.panel());
        boolean wide = availableSideBySideWidth >= SIDE_BY_SIDE_MIN_WIDTH;
        int imageWidth = wide
                ? Math.min(SIDE_BY_SIDE_MAX_WIDTH, availableSideBySideWidth)
                : NARROW_WIDTH;
        int rows = wide ? wideRows : narrowRows(screenHeight);

        Rect itemGrid = new Rect(8, TOP_HEIGHT, 9 * SLOT_SIZE, rows * SLOT_SIZE);
        Rect scrollbar = new Rect(174, TOP_HEIGHT, 12, rows * SLOT_SIZE);
        Rect playerInventory;
        Rect workspace;
        int imageHeight;
        if (wide) {
            int playerY = Math.max(itemGrid.bottom() + 14, TOP_HEIGHT + fuelGroupHeight());
            playerInventory = new Rect(
                    8, playerY, 9 * SLOT_SIZE, PLAYER_INVENTORY_HEIGHT);
            imageHeight = playerInventory.bottom() + 8;
            workspace = new Rect(190, TOP_HEIGHT, imageWidth - 198, imageHeight - TOP_HEIGHT - 8);
        } else {
            int workspaceY = itemGrid.bottom() + 4;
            int playerY = Math.max(
                    workspaceY + NARROW_WORKSPACE_HEIGHT + WORKSPACE_PLAYER_GAP,
                    TOP_HEIGHT + fuelGroupHeight());
            playerInventory = new Rect(8, playerY, 9 * SLOT_SIZE, PLAYER_INVENTORY_HEIGHT);
            imageHeight = playerInventory.bottom() + 8;
            workspace = new Rect(8, workspaceY, imageWidth - 16, NARROW_WORKSPACE_HEIGHT);
        }

        RailGeometry rails = craftingRails(imageHeight);
        if (wide) {
            int exactAvailableWidth = screenWidth - 2 * OUTER_MARGIN
                    - railReservedWidth(rails.panel());
            if (exactAvailableWidth < SIDE_BY_SIDE_MIN_WIDTH) {
                return craftingNarrow(screenWidth, screenHeight, machineCount, reserveCount);
            }
            imageWidth = Math.min(SIDE_BY_SIDE_MAX_WIDTH, exactAvailableWidth);
            workspace = new Rect(190, TOP_HEIGHT, imageWidth - 198, imageHeight - TOP_HEIGHT - 8);
        }

        return craftingGeometry(wide, imageWidth, imageHeight, rows, itemGrid, scrollbar,
                playerInventory, workspace, rails, machineCount, reserveCount);
    }

    private static Geometry craftingNarrow(
            int screenWidth,
            int screenHeight,
            int machineCount,
            int reserveCount
    ) {
        int rows = narrowRows(screenHeight);
        Rect itemGrid = new Rect(8, TOP_HEIGHT, 9 * SLOT_SIZE, rows * SLOT_SIZE);
        int workspaceY = itemGrid.bottom() + 4;
        int playerY = Math.max(
                workspaceY + NARROW_WORKSPACE_HEIGHT + WORKSPACE_PLAYER_GAP,
                TOP_HEIGHT + fuelGroupHeight());
        Rect playerInventory = new Rect(8, playerY, 9 * SLOT_SIZE, PLAYER_INVENTORY_HEIGHT);
        int imageHeight = playerInventory.bottom() + 8;
        Rect workspace = new Rect(8, workspaceY, NARROW_WIDTH - 16, NARROW_WORKSPACE_HEIGHT);
        return craftingGeometry(false, NARROW_WIDTH, imageHeight, rows, itemGrid,
                new Rect(174, TOP_HEIGHT, 12, rows * SLOT_SIZE), playerInventory, workspace,
                craftingRails(imageHeight), machineCount, reserveCount);
    }

    private static Geometry craftingGeometry(
            boolean wide,
            int imageWidth,
            int imageHeight,
            int rows,
            Rect itemGrid,
            Rect scrollbar,
            Rect playerInventory,
            Rect workspace,
            RailGeometry rails,
            int machineCount,
            int reserveCount
    ) {
        int fuelGroupTop = TOP_HEIGHT
                + Math.max(0, (playerInventory.y() - TOP_HEIGHT - fuelGroupHeight()) / 2);
        int machineTop = fuelGroupTop;
        Rect machinePanel = new Rect(8, machineTop, imageWidth - 16, MACHINE_PANEL_HEIGHT);
        Rect fuelPanel = new Rect(
                8, machinePanel.bottom() + FUEL_PANEL_GAP,
                imageWidth - 16, FUEL_PANEL_HEIGHT);
        Rect fuelControlPanel = wide
                ? new Rect(workspace.x(), playerInventory.y(), workspace.width(), playerInventory.height())
                : fuelPanel;
        Rect fuelTargetSelector;
        int fuelFlowTop;
        if (wide) {
            fuelTargetSelector = new Rect(
                    fuelControlPanel.x() + 4,
                    fuelControlPanel.y() + 4,
                    fuelControlPanel.width() - 8,
                    CONTROL_SIZE);
            fuelFlowTop = fuelPanel.y() + 20;
        } else {
            int selectorWidth = Math.clamp(fuelPanel.width() * 2 / 5, 92, 140);
            fuelTargetSelector = new Rect(
                    fuelPanel.right() - selectorWidth - 4,
                    fuelPanel.y() + 2,
                    selectorWidth,
                    CONTROL_SIZE);
            fuelFlowTop = fuelPanel.y() + 20;
        }
        FlowGrid machineGrid = flowGrid(
                new Rect(machinePanel.x() + 4, machinePanel.y() + PANEL_HEADER_HEIGHT,
                        machinePanel.width() - 12, machinePanel.height() - 17),
                machineCount, MACHINE_CELL_MIN_WIDTH, MACHINE_CELL_PREFERRED_WIDTH);
        Rect fuelInput = wide
                ? new Rect(
                        fuelControlPanel.x() + 8,
                        fuelControlPanel.y() + FUEL_CONTROL_INPUT_OFFSET,
                        SLOT_SIZE,
                        SLOT_SIZE)
                : new Rect(fuelPanel.x() + 8, fuelFlowTop, SLOT_SIZE, SLOT_SIZE);
        int reserveX = wide ? fuelPanel.x() + 4 : fuelPanel.x() + 34;
        int reserveWidth = wide ? fuelPanel.width() - 8 : fuelPanel.width() - 46;
        FlowGrid reserveGrid = flowGrid(
                new Rect(reserveX, fuelFlowTop,
                        reserveWidth, fuelPanel.bottom() - fuelFlowTop - 2),
                reserveCount, RESERVE_CELL_MIN_WIDTH, RESERVE_CELL_MIN_WIDTH);
        RecipeGeometry recipe = recipeGeometry(workspace, wide);
        return new Geometry(
                wide,
                imageWidth,
                imageHeight,
                rows,
                itemGrid,
                new Rect(102, 6, 70, 9),
                new Rect(100, 4, 72, 12),
                scrollbar,
                playerInventory,
                workspace,
                recipe.header(),
                recipe.inputRegion(),
                recipe.arrow(),
                recipe.output(),
                recipe.availableHeader(),
                recipe.status(),
                recipe.quantityFooter(),
                recipe.resourceCells(),
                recipe.navigationButtons(),
                recipe.craftButtons(),
                machinePanel,
                fuelPanel,
                fuelControlPanel,
                fuelTargetSelector,
                fuelInput,
                machineGrid,
                reserveGrid,
                rails.itemButtons(),
                rails.fuelButtons(),
                rails.bounds(),
                rails.panel(),
                rails.fuelPanel(),
                List.of(new Rect(0, 0, imageWidth, imageHeight), rails.panel())
        );
    }

    private static int fixedBandRows(int screenHeight, int bottomHeight, int minimum) {
        int preferredHeight = Math.max(0, screenHeight - 2 * PREFERRED_VERTICAL_MARGIN);
        int rows = (preferredHeight - TOP_HEIGHT - bottomHeight) / SLOT_SIZE;
        return Math.clamp(rows, minimum, MAX_ROWS);
    }

    private static int narrowRows(int screenHeight) {
        int maximumHeight = screenHeight - 2 * NARROW_MIN_VERTICAL_MARGIN;
        for (int rows = MAX_ROWS; rows >= MIN_CRAFTING_ROWS; rows--) {
            if (narrowImageHeight(rows) <= maximumHeight) return rows;
        }
        return MIN_CRAFTING_ROWS;
    }

    private static int narrowImageHeight(int rows) {
        int itemBottom = TOP_HEIGHT + rows * SLOT_SIZE;
        int workspaceBottom = itemBottom + 4 + NARROW_WORKSPACE_HEIGHT;
        int fuelGroupBottom = TOP_HEIGHT + fuelGroupHeight();
        int playerY = Math.max(workspaceBottom + WORKSPACE_PLAYER_GAP, fuelGroupBottom);
        return playerY + PLAYER_INVENTORY_HEIGHT + 8;
    }

    private static int fuelGroupHeight() {
        return MACHINE_PANEL_HEIGHT + FUEL_PANEL_GAP + FUEL_PANEL_HEIGHT;
    }

    private static RecipeGeometry recipeGeometry(Rect workspace, boolean wide) {
        Rect header = new Rect(
                workspace.x() + RECIPE_INSET,
                workspace.y() + 1,
                workspace.width() - RECIPE_INSET * 2,
                CONTROL_SIZE);
        Rect output = new Rect(
                header.right() - CONTROL_SIZE,
                header.y(),
                CONTROL_SIZE,
                CONTROL_SIZE);
        Rect arrow = new Rect(
                output.x() - CONTROL_GAP - CONTROL_SIZE,
                header.y(),
                CONTROL_SIZE,
                CONTROL_SIZE);
        Rect status = new Rect(
                header.x(), header.bottom(), header.width(), RECIPE_SUMMARY_HEIGHT);
        Rect availableHeader = new Rect(
                header.x(),
                status.bottom(),
                header.width(),
                RECIPE_SUMMARY_HEIGHT);
        Rect quantityFooter = new Rect(
                header.x(),
                workspace.bottom() - 1 - CONTROL_SIZE,
                header.width(),
                CONTROL_SIZE);
        Rect inputRegion = new Rect(
                header.x(),
                availableHeader.bottom(),
                header.width(),
                quantityFooter.y() - availableHeader.bottom());
        List<Rect> resourceCells = recipeResourceCells(inputRegion, wide);

        List<Rect> navigationButtons = List.of(
                new Rect(quantityFooter.x(), quantityFooter.y(), CONTROL_SIZE, CONTROL_SIZE),
                new Rect(quantityFooter.x() + CONTROL_SIZE + CONTROL_GAP,
                        quantityFooter.y(), CONTROL_SIZE, CONTROL_SIZE));
        List<Integer> craftWidths = List.of(
                CONTROL_SIZE,
                CONTROL_SIZE,
                CONTROL_SIZE + 8,
                CONTROL_SIZE + 12);
        int craftWidth = craftWidths.stream().mapToInt(Integer::intValue).sum()
                + CONTROL_GAP * (craftWidths.size() - 1);
        int craftX = quantityFooter.right() - craftWidth;
        List<Rect> craftButtons = new ArrayList<>(craftWidths.size());
        for (int width : craftWidths) {
            craftButtons.add(new Rect(craftX, quantityFooter.y(), width, CONTROL_SIZE));
            craftX += width + CONTROL_GAP;
        }
        if (navigationButtons.getLast().right() + CONTROL_GAP > craftButtons.getFirst().x()) {
            throw new IllegalArgumentException("Recipe footer controls do not fit workspace");
        }
        return new RecipeGeometry(
                header,
                inputRegion,
                arrow,
                output,
                availableHeader,
                status,
                quantityFooter,
                resourceCells,
                navigationButtons,
                craftButtons);
    }

    private static List<Rect> recipeResourceCells(Rect inputRegion, boolean wide) {
        int columns = wide ? 2 : 3;
        int rows = (RESOURCE_COUNT + columns - 1) / columns;
        int leftInset = 1;
        int rowGap = wide ? RECIPE_CELL_GAP : 0;
        Rect bounds = new Rect(
                inputRegion.x() + leftInset,
                inputRegion.y(),
                inputRegion.width() - leftInset * 2,
                inputRegion.height());
        int availableWidth = bounds.width() - RECIPE_CELL_GAP * (columns - 1);
        int availableHeight = bounds.height() - rowGap * (rows - 1);
        List<Rect> result = new ArrayList<>(RESOURCE_COUNT);
        for (int index = 0; index < RESOURCE_COUNT; index++) {
            int column = index % columns;
            int row = index / columns;
            int left = bounds.x() + column * availableWidth / columns
                    + column * RECIPE_CELL_GAP;
            int right = bounds.x() + (column + 1) * availableWidth / columns
                    + column * RECIPE_CELL_GAP;
            int top = bounds.y() + row * availableHeight / rows + row * rowGap;
            int bottom = bounds.y() + (row + 1) * availableHeight / rows + row * rowGap;
            result.add(new Rect(
                    left,
                    top,
                    right - left,
                    bottom - top));
        }
        return result;
    }

    private static FlowGrid flowGrid(
            Rect bounds,
            int itemCount,
            int minimumCellWidth,
            int preferredCellWidth
    ) {
        int maxColumns = Math.max(1, bounds.width() / minimumCellWidth);
        int maxRows = Math.max(1, bounds.height() / FLOW_CELL_HEIGHT);
        int capacity = maxColumns * maxRows;
        int visible = Math.min(itemCount, capacity);
        List<Rect> cells = flowCells(bounds, visible, maxColumns, maxRows, preferredCellWidth);
        return new FlowGrid(
                bounds, cells, maxColumns, maxRows, preferredCellWidth, capacity, itemCount);
    }

    private static List<Rect> flowCells(
            Rect bounds,
            int visible,
            int maxColumns,
            int maxRows,
            int preferredCellWidth
    ) {
        if (visible <= 0) return List.of();
        int largestColumns = Math.min(maxColumns, visible);
        int minimumColumns = (visible + maxRows - 1) / maxRows;
        int preferredColumns = Math.max(1, bounds.width() / preferredCellWidth);
        int columns = Math.clamp(preferredColumns, minimumColumns, largestColumns);
        int rows = (visible + columns - 1) / columns;
        List<Rect> cells = new ArrayList<>(visible);
        for (int index = 0; index < visible; index++) {
            int column = index % columns;
            int row = index / columns;
            int columnsInRow = Math.min(columns, visible - row * columns);
            int left = bounds.x() + column * bounds.width() / columnsInRow;
            int right = bounds.x() + (column + 1) * bounds.width() / columnsInRow;
            int top = bounds.y() + row * bounds.height() / rows;
            int bottom = bounds.y() + (row + 1) * bounds.height() / rows;
            cells.add(new Rect(
                    left,
                    top,
                    right - left,
                    bottom - top));
        }
        return cells;
    }

    private static FlowGrid emptyFlow() {
        return new FlowGrid(
                new Rect(0, 0, 0, 0), List.of(), 1, 1, 1, 1, 0);
    }

    private static RailGeometry craftingRails(int imageHeight) {
        List<Rect> itemButtons = railButtons(imageHeight, List.of(3, 3, 1));
        List<Rect> fuelButtons = railButtons(imageHeight, List.of(3));
        List<Rect> allButtons = new ArrayList<>(itemButtons.size() + fuelButtons.size());
        allButtons.addAll(itemButtons);
        allButtons.addAll(fuelButtons);
        Rect bounds = boundsOf(allButtons);
        return new RailGeometry(
                itemButtons,
                fuelButtons,
                bounds,
                inflate(bounds, 3),
                inflate(boundsOf(fuelButtons), 3));
    }

    private static List<Rect> railButtons(int imageHeight, List<Integer> groupSizes) {
        List<Rect> result = new ArrayList<>();
        int column = 0;
        int y = RAIL_TOP;
        boolean firstGroup = true;
        for (int groupSize : groupSizes) {
            if (groupSize <= 0) continue;
            if (!firstGroup) y += RAIL_GROUP_GAP;
            firstGroup = false;
            for (int index = 0; index < groupSize; index++) {
                if (y + RAIL_BUTTON_SIZE > imageHeight - RAIL_TOP) {
                    column++;
                    y = RAIL_TOP;
                }
                result.add(new Rect(
                        -(column + 1) * (RAIL_BUTTON_SIZE + RAIL_SPACING),
                        y,
                        RAIL_BUTTON_SIZE,
                        RAIL_BUTTON_SIZE));
                y += RAIL_BUTTON_SIZE + RAIL_SPACING;
            }
        }
        return result;
    }

    private static int railReservedWidth(Rect railPanel) {
        return Math.max(0, -railPanel.x()) + RAIL_FRAME_GAP;
    }

    private static Rect boundsOf(List<Rect> rectangles) {
        if (rectangles.isEmpty()) return new Rect(0, 0, 0, 0);
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Rect rectangle : rectangles) {
            left = Math.min(left, rectangle.x());
            top = Math.min(top, rectangle.y());
            right = Math.max(right, rectangle.right());
            bottom = Math.max(bottom, rectangle.bottom());
        }
        return new Rect(left, top, right - left, bottom - top);
    }

    private static Rect inflate(Rect rectangle, int amount) {
        if (rectangle.width() == 0 && rectangle.height() == 0) return rectangle;
        return new Rect(
                rectangle.x() - amount,
                rectangle.y() - amount,
                rectangle.width() + amount * 2,
                rectangle.height() + amount * 2);
    }

    private record RecipeGeometry(
            Rect header,
            Rect inputRegion,
            Rect arrow,
            Rect output,
            Rect availableHeader,
            Rect status,
            Rect quantityFooter,
            List<Rect> resourceCells,
            List<Rect> navigationButtons,
            List<Rect> craftButtons
    ) {
        RecipeGeometry {
            resourceCells = List.copyOf(resourceCells);
            navigationButtons = List.copyOf(navigationButtons);
            craftButtons = List.copyOf(craftButtons);
        }
    }

    private record RailGeometry(
            List<Rect> itemButtons,
            List<Rect> fuelButtons,
            Rect bounds,
            Rect panel,
            Rect fuelPanel
    ) {
        RailGeometry {
            itemButtons = List.copyOf(itemButtons);
            fuelButtons = List.copyOf(fuelButtons);
        }
    }
}
