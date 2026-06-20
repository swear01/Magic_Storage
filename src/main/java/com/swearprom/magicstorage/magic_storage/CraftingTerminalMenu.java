package com.swearprom.magicstorage.magic_storage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CraftingTerminalMenu extends StorageTerminalMenu {

    private int selectedItemIndex = -1;
    private final List<RecipeHolder<?>> currentRecipes = new ArrayList<>();
    private int currentRecipeIndex = 0;
    private boolean showOnlyCraftable = false;
    private boolean usePlayerInventory = false;
    private boolean compactMode = true;
    private boolean dirtyRecipes = false;
    private int lastCheckedItem = -1;

    public CraftingTerminalMenu(int containerId, Inventory playerInv, StorageCoreBlockEntity core) {
        super(MagicStorage.CRAFTING_TERMINAL_MENU.get(), containerId, playerInv, core);
        addContainerData();
    }

    public CraftingTerminalMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        super(MagicStorage.CRAFTING_TERMINAL_MENU.get(), containerId, playerInv, buf);
        addContainerData();
    }

    private void addContainerData() {
        addDataSlots(new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> selectedItemIndex;
                    case 1 -> currentRecipeIndex;
                    case 2 -> showOnlyCraftable ? 1 : 0;
                    case 3 -> usePlayerInventory ? 1 : 0;
                    case 4 -> compactMode ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0 -> selectedItemIndex = value;
                    case 1 -> currentRecipeIndex = value;
                    case 2 -> showOnlyCraftable = value != 0;
                    case 3 -> usePlayerInventory = value != 0;
                    case 4 -> compactMode = value != 0;
                }
            }

            @Override
            public int getCount() {
                return 5;
            }
        });
    }

    @Override
    protected void setupSlots(Inventory playerInv) {
        for (int row = 0; row < MAX_DISPLAY_ROWS; row++) {
            for (int col = 0; col < DISPLAY_COLS; col++) {
                int slotIndex = col + row * DISPLAY_COLS;
                this.addSlot(new GhostSlot(displayInventory, slotIndex, 7 + col * 18, 17 + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 7 + col * 18, 183 + row * 18));
            }
        }

        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 7 + col * 18, 241));
        }
    }

    public int getSelectedItemIndex() {
        return selectedItemIndex;
    }

    public int getCurrentRecipeIndex() {
        return currentRecipeIndex;
    }

    public int getRecipeCount() {
        return currentRecipes.size();
    }

    public boolean isShowOnlyCraftable() {
        return showOnlyCraftable;
    }

    public boolean isUsePlayerInventory() {
        return usePlayerInventory;
    }

    public boolean isCompactMode() {
        return compactMode;
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS) {
            if (!player.level().isClientSide()) {
                Slot slot = getSlot(slotIndex);
                ItemStack displayStack = slot.getItem();
                if (!displayStack.isEmpty() && getCarried().isEmpty() && clickType == ClickType.PICKUP) {
                    selectedItemIndex = slotIndex;
                    lookUpRecipes(player.level(), displayStack);
                    broadcastChanges();
                }
            }
            return;
        }
        super.clicked(slotIndex, button, clickType, player);
    }

    void lookUpRecipes(Level level, ItemStack output) {
        currentRecipes.clear();
        currentRecipeIndex = 0;
        dirtyRecipes = false;
        lastCheckedItem = ItemStack.hashItemAndComponents(output);
        RecipeManager manager = level.getRecipeManager();

        RecipeType<?>[] types = {
                RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING,
                RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING,
                RecipeType.STONECUTTING, RecipeType.SMITHING
        };

        for (RecipeType<?> type : types) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) manager.getAllRecipesFor((RecipeType) type);
            for (RecipeHolder<?> holder : holders) {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (ItemStack.isSameItemSameComponents(result, output)) {
                    currentRecipes.add(holder);
                }
            }
        }

        currentRecipes.sort(Comparator.comparingInt(h -> getRecipeSortOrder(h.value().getType())));
    }

    private static int getRecipeSortOrder(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) return 0;
        if (type == RecipeType.SMELTING) return 1;
        if (type == RecipeType.BLASTING) return 2;
        if (type == RecipeType.SMOKING) return 3;
        if (type == RecipeType.CAMPFIRE_COOKING) return 4;
        if (type == RecipeType.STONECUTTING) return 5;
        if (type == RecipeType.SMITHING) return 6;
        return 99;
    }

    public List<RecipeHolder<?>> getCurrentRecipes() {
        return currentRecipes;
    }

    public void prevRecipe() {
        if (currentRecipes.isEmpty()) return;
        currentRecipeIndex = (currentRecipeIndex - 1 + currentRecipes.size()) % currentRecipes.size();
    }

    public void nextRecipe() {
        if (currentRecipes.isEmpty()) return;
        currentRecipeIndex = (currentRecipeIndex + 1) % currentRecipes.size();
    }

    public void toggleShowOnlyCraftable() {
        showOnlyCraftable = !showOnlyCraftable;
    }

    public void toggleUsePlayerInventory() {
        usePlayerInventory = !usePlayerInventory;
    }

    public void toggleCompactMode() {
        compactMode = !compactMode;
    }

    public void craftItem(int count, Player player) {
        if (currentRecipes.isEmpty() || currentRecipeIndex >= currentRecipes.size()) return;
        if (player.level().isClientSide()) return;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null) return;

        RecipeHolder<?> holder = currentRecipes.get(currentRecipeIndex);
        Recipe<?> recipe = holder.value();
        ItemStack result = recipe.getResultItem(player.level().registryAccess());
        if (result.isEmpty()) return;

        List<Ingredient> ingredients = recipe.getIngredients();
        EnergyCost energyCost = RecipeEnergyTable.getCost(recipe.getType());

        // Phase 1: simulate everything; mutate nothing.
        if (energyCost != null) {
            long pNeed = (long) energyCost.processAmount() * count;
            long fNeed = (long) energyCost.fuelAmount() * count;
            if (core.getEnergy(energyCost.processType()) < pNeed
                    || core.getEnergy(energyCost.fuelType()) < fNeed) return;
        }
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            if (!canConsumeIngredient(core, ingredient, count, player)) return;
        }

        // Phase 2: commit (Phase 1 guaranteed availability).
        if (energyCost != null) core.consumeEnergy(energyCost, count);
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            doConsumeIngredient(core, ingredient, count, player);
        }

        long total = (long) result.getCount() * count;
        while (total > 0) {
            int n = (int) Math.min(total, result.getMaxStackSize());
            ItemStack out = result.copy();
            out.setCount(n);
            if (!player.getInventory().add(out)) player.drop(out, false);
            total -= n;
        }

        refreshDisplayItems(core);
        broadcastChanges();
    }

    private long availableInPlayerInv(Player player, ItemStack match) {
        long avail = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack invStack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(invStack, match)) avail += invStack.getCount();
        }
        return avail;
    }

    private boolean canConsumeIngredient(StorageCoreBlockEntity core, Ingredient ingredient, int count, Player player) {
        for (ItemStack match : ingredient.getItems()) {
            long needed = (long) match.getCount() * count;
            long fromCore = core.extractItem(ItemKey.of(match), needed, true).getCount();
            if (fromCore >= needed) return true;
            if (usePlayerInventory && fromCore + availableInPlayerInv(player, match) >= needed) return true;
        }
        return false;
    }

    private void doConsumeIngredient(StorageCoreBlockEntity core, Ingredient ingredient, int count, Player player) {
        for (ItemStack match : ingredient.getItems()) {
            long needed = (long) match.getCount() * count;
            long fromCore = core.extractItem(ItemKey.of(match), needed, true).getCount();
            boolean coreEnough = fromCore >= needed;
            boolean comboEnough = usePlayerInventory && fromCore + availableInPlayerInv(player, match) >= needed;
            if (!coreEnough && !comboEnough) continue;
            long remaining = needed - core.extractItem(ItemKey.of(match), needed).getCount();
            for (int i = 0; usePlayerInventory && remaining > 0 && i < player.getInventory().getContainerSize(); i++) {
                ItemStack invStack = player.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(invStack, match)) {
                    int take = (int) Math.min(invStack.getCount(), remaining);
                    invStack.shrink(take);
                    remaining -= take;
                }
            }
            return;
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!player.level().isClientSide()) {
            StorageCoreBlockEntity core = getCore(player.level());
            switch (buttonId) {
                case 0, 1 -> {
                    if (core != null) {
                        return super.clickMenuButton(player, buttonId);
                    }
                    return true;
                }
                case 2 -> craftItem(1, player);
                case 3 -> craftItem(8, player);
                case 4 -> craftItem(64, player);
                case 5 -> craftItem(16, player);
                case 6 -> toggleShowOnlyCraftable();
                case 7 -> toggleUsePlayerInventory();
                case 8 -> prevRecipe();
                case 9 -> nextRecipe();
                case 10 -> toggleCompactMode();
                default -> {}
            }
            if (core != null && buttonId >= 6 && buttonId <= 10) {
                refreshDisplayItems(core);
            }
        }
        return true;
    }

    @Override
    public void refreshDisplayItems(StorageCoreBlockEntity core) {
        displayInventory.clearContent();
        if (core == null) {
            totalItemTypes = 0;
            return;
        }
        java.util.List<ItemStack> stacks = core.getDisplayStacks();
        java.util.List<ItemStack> displayStacks;

        if (compactMode) {
            Map<net.minecraft.world.item.Item, List<ItemStack>> grouped = new TreeMap<>(
                Comparator.comparing(item -> item.getDescription().getString()));
            for (ItemStack stack : stacks) {
                grouped.computeIfAbsent(stack.getItem(), k -> new ArrayList<>())
                    .add(stack);
            }
            displayStacks = new ArrayList<>();
            for (List<ItemStack> group : grouped.values()) {
                ItemStack representative = group.get(0).copy();
                int totalCount = 0;
                for (ItemStack s : group) {
                    totalCount += s.getCount();
                }
                representative.setCount(totalCount);
                displayStacks.add(representative);
            }
        } else {
            displayStacks = new ArrayList<>(stacks);
        }

        totalItemTypes = displayStacks.size();
        int vRows = getVisibleRows();
        int maxOffset = Math.max(0, totalItemTypes - vRows * DISPLAY_COLS);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            int idx = scrollOffset + i;
            if (idx < displayStacks.size() && i < vRows * DISPLAY_COLS) {
                displayInventory.setItem(i, displayStacks.get(idx).copy());
            }
        }
    }
}
