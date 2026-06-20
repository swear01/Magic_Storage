package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class StorageTerminalMenu extends AbstractContainerMenu {

    static final int MAX_DISPLAY_ROWS = 9;
    static final int DISPLAY_COLS = 9;
    static final int DISPLAY_SLOTS = MAX_DISPLAY_ROWS * DISPLAY_COLS;

    private BlockPos corePos;
    protected final SimpleContainer displayInventory;
    protected int scrollOffset;
    protected int totalItemTypes;
    protected String currentFilter = "";
    protected int displayTypeCount = 0;
    protected int displayMaxTypes = 0;
    private int visibleRows = 6;
    private SortMode sortMode = SortMode.NAME;
    private SortOrder sortOrder = SortOrder.ASCENDING;
    private SearchMode searchMode = SearchMode.NORMAL;

    public StorageTerminalMenu(int containerId, Inventory playerInv, StorageCoreBlockEntity core) {
        this(MagicStorage.STORAGE_TERMINAL_MENU.get(), containerId, playerInv, core);
    }

    private void addTypeDataSlots() {
        addDataSlots(new net.minecraft.world.inventory.SimpleContainerData(5) {
            @Override public int get(int i) {
                return switch (i) {
                    case 0 -> displayTypeCount;
                    case 1 -> displayMaxTypes;
                    case 2 -> sortMode.ordinal();
                    case 3 -> sortOrder.ordinal();
                    default -> searchMode.ordinal();
                };
            }
            @Override public void set(int i, int v) {
                switch (i) {
                    case 0 -> displayTypeCount = v;
                    case 1 -> displayMaxTypes = v;
                    case 2 -> sortMode = SortMode.values()[v];
                    case 3 -> sortOrder = SortOrder.values()[v];
                    default -> searchMode = SearchMode.values()[v];
                }
            }
            @Override public int getCount() { return 5; }
        });
    }

    public int getTypeCount() { return displayTypeCount; }
    public int getMaxTypes() { return displayMaxTypes; }
    public SortMode getSortMode() { return sortMode; }
    public SortOrder getSortOrder() { return sortOrder; }
    public SearchMode getSearchMode() { return searchMode; }

    StorageTerminalMenu(MenuType<?> menuType, int containerId, Inventory playerInv, StorageCoreBlockEntity core) {
        super(menuType, containerId);
        this.corePos = core.getBlockPos();
        this.displayInventory = new SimpleContainer(DISPLAY_SLOTS);
        this.scrollOffset = 0;
        setupSlots(playerInv);
        refreshDisplayItems(core);
        addTypeDataSlots();
    }

    protected StorageTerminalMenu(MenuType<?> menuType, int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        super(menuType, containerId);
        this.corePos = buf.readBlockPos();
        this.displayInventory = new SimpleContainer(DISPLAY_SLOTS);
        this.scrollOffset = 0;
        setupSlots(playerInv);
        addTypeDataSlots();
    }

    protected void setupSlots(Inventory playerInv) {
        int gridTop = 19;
        for (int row = 0; row < MAX_DISPLAY_ROWS; row++) {
            for (int col = 0; col < DISPLAY_COLS; col++) {
                int slotIndex = col + row * DISPLAY_COLS;
                this.addSlot(new GhostSlot(displayInventory, slotIndex, 8 + col * 18, gridTop + row * 18));
            }
        }
        int playerInvTop = gridTop + MAX_DISPLAY_ROWS * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, playerInvTop + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, playerInvTop + 3 * 18 + 4));
        }
    }

    protected StorageCoreBlockEntity getCore(Level level) {
        if (level == null) return null;
        if (level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core) {
            return core;
        }
        return null;
    }

    public void refreshDisplayItems(StorageCoreBlockEntity core) {
        refreshDisplayItemsFiltered(core, currentFilter);
    }

    public void refreshDisplayItemsFiltered(StorageCoreBlockEntity core, String filter) {
        this.currentFilter = filter != null ? filter : "";
        displayInventory.clearContent();
        if (core == null) { totalItemTypes = 0; return; }
        java.util.List<ItemStack> stacks = core.getDisplayStacks(currentFilter, sortMode, sortOrder);
        totalItemTypes = stacks.size();
        displayTypeCount = core.getTypeCount();
        displayMaxTypes = core.getTotalTypeSlots();
        int maxOffset = Math.max(0, totalItemTypes - visibleRows * DISPLAY_COLS);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            int idx = scrollOffset + i;
            if (idx < stacks.size() && i < visibleRows * DISPLAY_COLS) {
                displayInventory.setItem(i, stacks.get(idx).copy());
            }
        }
    }

    public void scrollBy(int delta) {
        int maxOffset = Math.max(0, totalItemTypes - visibleRows * DISPLAY_COLS);
        scrollOffset = Math.clamp(scrollOffset + delta, 0, maxOffset);
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public int getTotalItemTypes() {
        return totalItemTypes;
    }

    public BlockPos getCorePos() {
        return corePos;
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS) {
            if (!player.level().isClientSide()) {
                Slot slot = getSlot(slotIndex);
                ItemStack displayStack = slot.getItem();
                if (!displayStack.isEmpty() && getCarried().isEmpty()) {
                    StorageCoreBlockEntity core = getCore(player.level());
                    if (core != null) {
                        var key = ItemKey.of(displayStack);
                        long actualCount = core.getItemCount(key);
                        long maxStack = displayStack.getMaxStackSize();
                        long amount = switch (clickType) {
                            case QUICK_MOVE -> Math.min(actualCount, maxStack * 36); // fill player inv
                            default -> button == 0
                                    ? Math.min(actualCount, maxStack)      // left: up to 1 stack
                                    : Math.max(1, Math.min(actualCount, maxStack) / 2); // right: half
                        };
                        if (amount <= 0) amount = 1;
                        ItemStack extracted = core.extractItem(key, amount);
                        if (!extracted.isEmpty()) {
                            if (clickType == ClickType.QUICK_MOVE) {
                                if (!player.getInventory().add(extracted)) {
                                    player.drop(extracted, false);
                                }
                            } else {
                                setCarried(extracted);
                            }
                            refreshDisplayItemsFiltered(core, currentFilter);
                            broadcastChanges();
                        }
                    }
                }
            }
            return;
        }
        super.clicked(slotIndex, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();

        if (index < DISPLAY_SLOTS) {
            if (!player.level().isClientSide()) {
                StorageCoreBlockEntity core = getCore(player.level());
                if (core != null) {
                    var key = ItemKey.of(stackInSlot);
                    long actualCount = core.getItemCount(key);
                    long amount = Math.min(actualCount, stackInSlot.getMaxStackSize() * 36);
                    ItemStack extracted = core.extractItem(key, amount);
                    if (!extracted.isEmpty()) {
                        if (!player.getInventory().add(extracted)) {
                            player.drop(extracted, false);
                        }
                        refreshDisplayItems(core);
                        broadcastChanges();
                    }
                }
            }
            return ItemStack.EMPTY;
        }

        if (!player.level().isClientSide()) {
            StorageCoreBlockEntity core = getCore(player.level());
            if (core != null) {
                ItemStack toInsert = stackInSlot.copy();
                long inserted = core.insertItem(toInsert);
                if (inserted > 0) {
                    stackInSlot.shrink((int) inserted);
                    slot.setChanged();
                    refreshDisplayItems(core);
                    broadcastChanges();
                }
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!player.level().isClientSide()) {
            StorageCoreBlockEntity core = getCore(player.level());
            if (core != null) {
                switch (buttonId) {
                    case 0 -> scrollBy(-DISPLAY_COLS);
                    case 1 -> scrollBy(DISPLAY_COLS);
                    case 11 -> sortOrder = SortOrder.toggle(sortOrder);
                    case 12 -> sortMode = sortMode.next();
                    case 13 -> searchMode = searchMode.next();
                }
                refreshDisplayItems(core);
            }
        }
        return true;
    }

    public void applySettings(TerminalSettingsPacket packet) {
        this.visibleRows = Math.clamp(packet.visibleRows(), 3, MAX_DISPLAY_ROWS);
    }

    public int getVisibleRows() {
        return visibleRows;
    }

    @Override
    public boolean stillValid(Player player) {
        if (corePos == null) return false;
        return player.level().getBlockState(corePos).getBlock() instanceof StorageCoreBlock
                && player.distanceToSqr(corePos.getX() + 0.5, corePos.getY() + 0.5, corePos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            displayInventory.clearContent();
        }
    }
}
