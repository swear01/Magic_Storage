package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public class CraftingTerminalMenu extends StorageTerminalMenu {

    public record IngredientPreview(ItemStack stack, long available, int required) {}
    public record EnergyPreview(EnergyType type, long available, long required) {}
    public record CraftPreview(
            int craftable,
            List<ItemStack> missing,
            List<IngredientPreview> ingredients,
            List<EnergyPreview> energies
    ) {}
    private record IngredientNeed(RecipeIngredient ingredient, long count) {}
    private record IngredientSource(ItemKey key, int playerSlot, ItemStack stack, long amount) {}
    private record PlayerReservation(ItemKey key, int count) {}
    private record IngredientPlan(
            Map<ItemKey, Long> coreReservations,
            Map<Integer, PlayerReservation> playerReservations,
            Map<ItemKey, Long> consumedItems,
            List<Map<ItemKey, Long>> allocations
    ) {}
    private record ToolUsePlan(long amount) {}
    private record DeliveryPlan(
            List<ItemStack> playerInventory,
            ItemStack carried,
            Map<ItemKey, Long> coreOutputs,
            ToolUsePlan toolUse
    ) {}
    private record CraftPlan(long crafts, IngredientPlan ingredients, DeliveryPlan delivery) {}
    private enum DeliveryTarget {
        CURSOR,
        PLAYER,
        STORAGE;

        private static DeliveryTarget from(CraftingDestination destination) {
            return switch (destination) {
                case CURSOR -> CURSOR;
                case INVENTORY -> PLAYER;
                case NONE -> throw new IllegalArgumentException("NONE has no delivery target");
            };
        }

        private static DeliveryTarget from(TerminalOutputDestination destination) {
            return switch (destination) {
                case PLAYER -> PLAYER;
                case STORAGE -> STORAGE;
            };
        }
    }

    static final class RecipeIngredient {
        private final Object identity;
        private final Predicate<ItemStack> matcher;
        private final List<ItemStack> items;
        private final boolean empty;

        private RecipeIngredient(
                Object identity,
                Predicate<ItemStack> matcher,
                List<ItemStack> items,
                boolean empty
        ) {
            this.identity = identity;
            this.matcher = matcher;
            this.items = items.stream().map(stack -> stack.copyWithCount(1)).toList();
            this.empty = empty;
        }

        static RecipeIngredient of(Ingredient ingredient) {
            return new RecipeIngredient(
                    ingredient,
                    ingredient,
                    Arrays.asList(ingredient.getItems()),
                    ingredient.isEmpty()
            );
        }

        static RecipeIngredient smithing(
                SmithingIngredientIdentity identity,
                Predicate<ItemStack> matcher,
                List<ItemStack> items
        ) {
            return new RecipeIngredient(identity, matcher, items, false);
        }

        boolean test(ItemStack stack) {
            return matcher.test(stack);
        }

        List<ItemStack> items() {
            return items;
        }

        boolean isEmpty() {
            return empty;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RecipeIngredient ingredient
                    && identity.equals(ingredient.identity);
        }

        @Override
        public int hashCode() {
            return identity.hashCode();
        }
    }

    private record SmithingIngredientIdentity(Recipe<?> recipe, int role) {}

    private static final class FlowEdge {
        private final int to;
        private final int reverseIndex;
        private final long originalCapacity;
        private long capacity;

        private FlowEdge(int to, int reverseIndex, long capacity) {
            this.to = to;
            this.reverseIndex = reverseIndex;
            this.originalCapacity = capacity;
            this.capacity = capacity;
        }
    }

    private static final int MAX_INGREDIENTS = RecipePresentation.MAX_ITEM_RESOURCES;
    private static final int PRESENTATION_OUTPUT_SLOT = 0;
    private static final int ITEM_RESOURCE_SLOT_START = PRESENTATION_OUTPUT_SLOT + 1;
    private static final int PRESENTATION_STATION_SLOT = ITEM_RESOURCE_SLOT_START + MAX_INGREDIENTS;
    private static final int PRESENTATION_INPUT_SLOT_START = PRESENTATION_STATION_SLOT + 1;
    private static final int PRESENTATION_TOOL_SLOT =
            PRESENTATION_INPUT_SLOT_START + RecipePresentation.MAX_INPUTS;
    private static final int PRESENTATION_METADATA_SLOT = PRESENTATION_TOOL_SLOT + 1;
    private static final int SELECTION_SLOTS = PRESENTATION_METADATA_SLOT + 1;
    private static final int PREVIEW_CAP = 9999;
    private static final int MAX_RECIPE_REQUEST = 64;
    public static final int FUEL_INPUT_SLOT = DISPLAY_SLOTS + PLAYER_INVENTORY_SLOTS;
    public static final int MACHINE_SLOT_START = FUEL_INPUT_SLOT + 1;
    public static final int MACHINE_SLOT_COUNT = MachineEnergyTable.size();
    private static final int MACHINE_SLOT_END = MACHINE_SLOT_START + MACHINE_SLOT_COUNT;
    private static final int MACHINE_SLOT_X = 18;
    private static final int MACHINE_SLOT_Y = 42;
    private static final int MACHINE_SLOT_GAP = 38;
    private static final int FUEL_INPUT_X = 18;
    private static final int FUEL_INPUT_Y = 126;
    static final int MAX_CRAFT_BUTTON = 5;
    static final int CRAFTABLE_PAGE_BUTTON = 6;
    static final int OUTPUT_DESTINATION_BUTTON = 10;
    static final int STORAGE_PAGE_BUTTON = 14;
    static final int FUEL_PAGE_BUTTON = 15;
    static final int AUTO_FUEL_TARGET_BUTTON = 16;
    private static final int FUEL_TARGET_BUTTON_BASE = 19;
    private static final List<EnergyType> FUEL_TARGETS = Arrays.stream(EnergyType.values())
            .filter(type -> !type.isMachineGenerated())
            .toList();
    private static final int FUEL_TARGET_BUTTON_COUNT = FUEL_TARGETS.size();
    private static final int ENERGY_DATA_START = 8;
    private static final int ENERGY_DATA_SLOTS = EnergyType.values().length * 4;
    private static final int INGREDIENT_AVAILABLE_DATA_START = ENERGY_DATA_START + ENERGY_DATA_SLOTS;
    private static final int PROCESS_REQUIRED_DATA_START = INGREDIENT_AVAILABLE_DATA_START + MAX_INGREDIENTS * 4;
    private static final int FUEL_REQUIRED_DATA_START = PROCESS_REQUIRED_DATA_START + 4;
    private static final int AXE_ENERGY_DATA_START = FUEL_REQUIRED_DATA_START + 4;
    private static final int AXE_INFINITE_DATA_SLOT = AXE_ENERGY_DATA_START + 4;
    private static final int CRAFTING_DATA_SLOTS = AXE_INFINITE_DATA_SLOT + 1;
    private static final List<RecipeType<?>> SUPPORTED_RECIPE_TYPES = List.of(
            RecipeType.CRAFTING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
    );
    private static final Map<Recipe<?>, List<RecipeIngredient>> SMITHING_INGREDIENT_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final EnergyType[] ENERGY_SYNC_ORDER = EnergyType.values();

    private ItemKey selectedKey = null;
    private ResourceLocation selectedRecipeId;
    private SimpleContainer selectionContainer;
    private SimpleContainer fuelContainer;
    private SimpleContainer axeInputContainer;
    private Container machineContainer;
    private final List<RecipeHolder<?>> currentRecipes = new ArrayList<>();
    private final CraftableRecipeCatalog craftableRecipeCatalog = new CraftableRecipeCatalog();
    private final AxeTransformationCatalog axeTransformationCatalog = new AxeTransformationCatalog();
    private int currentRecipeIndex = 0;
    private int recipeCount = 0;
    private int currentRecipeTypeOrder = -1;
    private int craftableCount = 0;
    private boolean usePlayerInventory = false;
    private TerminalOutputDestination outputDestination = TerminalOutputDestination.PLAYER;
    private boolean dirtyRecipes = false;
    private int lastCheckedItem = -1;
    private final Inventory playerInventory;
    private CraftingTerminalPage page = CraftingTerminalPage.STORAGE;
    private EnergyType selectedFuelTarget;
    private final long[] energyAmounts = new long[EnergyType.values().length];
    private final long[] ingredientAvailable = new long[MAX_INGREDIENTS];
    private long processRequired;
    private long fuelRequired;
    private long axeEnergyAmount;
    private boolean infiniteAxeEnergy;
    private boolean processingFuelInput;
    private boolean processingAxeInput;
    private int lastPlayerInventoryFingerprint;
    private long lastTopologyRevision;
    private long lastMachineRevision;

    public CraftingTerminalMenu(int containerId, Inventory playerInv, StorageCoreBlockEntity core) {
        this(containerId, playerInv, core, core.getBlockPos(), false);
    }

    public CraftingTerminalMenu(int containerId, Inventory playerInv, StorageCoreBlockEntity core, BlockPos accessPos, boolean remoteAccess) {
        super(MagicStorage.CRAFTING_TERMINAL_MENU.get(), containerId, playerInv, core, accessPos, remoteAccess);
        this.playerInventory = playerInv;
        this.lastPlayerInventoryFingerprint = playerInventoryFingerprint();
        this.lastTopologyRevision = core.getTopologyRevision();
        this.lastMachineRevision = core.getMachineRevision();
        refreshEnergyAmounts(core);
        addContainerData();
        refreshDisplayItems(core);
    }

    public CraftingTerminalMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        super(MagicStorage.CRAFTING_TERMINAL_MENU.get(), containerId, playerInv, buf);
        this.playerInventory = playerInv;
        this.lastPlayerInventoryFingerprint = playerInventoryFingerprint();
        addContainerData();
    }

    private void addContainerData() {
        addDataSlots(new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> currentRecipeIndex;
                    case 1 -> usePlayerInventory ? 1 : 0;
                    case 2 -> craftableCount;
                    case 3 -> recipeCount;
                    case 4 -> currentRecipeTypeOrder;
                    case 5 -> page.ordinal();
                    case 6 -> encodeFuelTarget(selectedFuelTarget);
                    case 7 -> outputDestination.ordinal();
                    default -> getPreviewData(index);
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0 -> currentRecipeIndex = value;
                    case 1 -> usePlayerInventory = value != 0;
                    case 2 -> craftableCount = value;
                    case 3 -> recipeCount = value;
                    case 4 -> currentRecipeTypeOrder = value;
                    case 5 -> page = CraftingTerminalPage.fromOrdinal(value);
                    case 6 -> selectedFuelTarget = decodeFuelTarget(value);
                    case 7 -> outputDestination = TerminalOutputDestination.byId(value);
                    default -> setPreviewData(index, value);
                }
            }

            @Override
            public int getCount() {
                return CRAFTING_DATA_SLOTS;
            }
        });
    }

    private int getPreviewData(int index) {
        if (index >= ENERGY_DATA_START && index < INGREDIENT_AVAILABLE_DATA_START) {
            int relative = index - ENERGY_DATA_START;
            EnergyType type = ENERGY_SYNC_ORDER[relative / 4];
            return getLongPart(energyAmounts[type.ordinal()], relative % 4);
        }
        if (index >= INGREDIENT_AVAILABLE_DATA_START && index < PROCESS_REQUIRED_DATA_START) {
            int relative = index - INGREDIENT_AVAILABLE_DATA_START;
            return getLongPart(ingredientAvailable[relative / 4], relative % 4);
        }
        if (index >= PROCESS_REQUIRED_DATA_START && index < FUEL_REQUIRED_DATA_START) {
            return getLongPart(processRequired, index - PROCESS_REQUIRED_DATA_START);
        }
        if (index >= FUEL_REQUIRED_DATA_START && index < CRAFTING_DATA_SLOTS) {
            if (index < AXE_ENERGY_DATA_START) {
                return getLongPart(fuelRequired, index - FUEL_REQUIRED_DATA_START);
            }
            if (index < AXE_INFINITE_DATA_SLOT) {
                return getLongPart(axeEnergyAmount, index - AXE_ENERGY_DATA_START);
            }
            return infiniteAxeEnergy ? 1 : 0;
        }
        return 0;
    }

    private void setPreviewData(int index, int value) {
        if (index >= ENERGY_DATA_START && index < INGREDIENT_AVAILABLE_DATA_START) {
            int relative = index - ENERGY_DATA_START;
            EnergyType type = ENERGY_SYNC_ORDER[relative / 4];
            int energyIndex = type.ordinal();
            energyAmounts[energyIndex] = setLongPart(energyAmounts[energyIndex], relative % 4, value);
            return;
        }
        if (index >= INGREDIENT_AVAILABLE_DATA_START && index < PROCESS_REQUIRED_DATA_START) {
            int relative = index - INGREDIENT_AVAILABLE_DATA_START;
            int ingredient = relative / 4;
            ingredientAvailable[ingredient] = setLongPart(
                    ingredientAvailable[ingredient], relative % 4, value);
            return;
        }
        if (index >= PROCESS_REQUIRED_DATA_START && index < FUEL_REQUIRED_DATA_START) {
            processRequired = setLongPart(processRequired, index - PROCESS_REQUIRED_DATA_START, value);
            return;
        }
        if (index >= FUEL_REQUIRED_DATA_START && index < AXE_ENERGY_DATA_START) {
            fuelRequired = setLongPart(fuelRequired, index - FUEL_REQUIRED_DATA_START, value);
            return;
        }
        if (index >= AXE_ENERGY_DATA_START && index < AXE_INFINITE_DATA_SLOT) {
            axeEnergyAmount = setLongPart(axeEnergyAmount, index - AXE_ENERGY_DATA_START, value);
            return;
        }
        if (index == AXE_INFINITE_DATA_SLOT) {
            infiniteAxeEnergy = value != 0;
        }
    }

    private static int getLongPart(long value, int part) {
        return (int) ((value >>> (part * 16)) & 0xFFFFL);
    }

    private static long setLongPart(long current, int part, int value) {
        int shift = part * 16;
        long mask = 0xFFFFL << shift;
        return (current & ~mask) | (((long) value & 0xFFFFL) << shift);
    }

    private static int encodeFuelTarget(EnergyType target) {
        if (target == null) return 0;
        int index = FUEL_TARGETS.indexOf(target);
        return index < 0 ? 0 : index + 1;
    }

    private static EnergyType decodeFuelTarget(int value) {
        int index = value - 1;
        return index >= 0 && index < FUEL_TARGETS.size() ? FUEL_TARGETS.get(index) : null;
    }

    @Override
    protected void setupSlots(Inventory playerInv) {
        for (int row = 0; row < MAX_DISPLAY_ROWS; row++) {
            for (int col = 0; col < DISPLAY_COLS; col++) {
                int slotIndex = col + row * DISPLAY_COLS;
                this.addSlot(new GhostSlot(displayInventory, slotIndex, 7 + col * 18, 17 + row * 18) {
                    @Override
                    public boolean isActive() {
                        return page.isItemPage() && super.isActive();
                    }
                });
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

        fuelContainer = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                onFuelInputChanged();
            }
        };
        this.addSlot(new Slot(fuelContainer, 0, FUEL_INPUT_X, FUEL_INPUT_Y) {
            @Override
            public boolean isActive() {
                return page == CraftingTerminalPage.FUEL;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return page == CraftingTerminalPage.FUEL && isCompatibleWithSelectedFuelTarget(stack);
            }

            @Override
            public boolean mayPickup(Player player) {
                return page == CraftingTerminalPage.FUEL && super.mayPickup(player);
            }
        });

        StorageCoreBlockEntity core = getCore(playerInv.player.level());
        machineContainer = core != null
                ? core.getMachineContainer()
                : new SimpleContainer(MACHINE_SLOT_COUNT);
        axeInputContainer = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                onAxeInputChanged();
            }
        };
        for (int machineSlot = 0; machineSlot < MACHINE_SLOT_COUNT; machineSlot++) {
            int mappedSlot = machineSlot;
            MachineEnergyTable.Entry descriptor = MachineEnergyTable.get(mappedSlot);
            boolean consumable = descriptor != null
                    && descriptor.category() == MachineEnergyTable.Category.CONSUMABLE;
            Container slotContainer = consumable ? axeInputContainer : machineContainer;
            int containerSlot = consumable ? 0 : mappedSlot;
            this.addSlot(new Slot(slotContainer, containerSlot,
                    MACHINE_SLOT_X + mappedSlot * MACHINE_SLOT_GAP, MACHINE_SLOT_Y) {
                private boolean hasRetainedLegacyInput() {
                    return consumable && !machineContainer.getItem(mappedSlot).isEmpty();
                }

                @Override
                public ItemStack getItem() {
                    return hasRetainedLegacyInput()
                            ? machineContainer.getItem(mappedSlot).copy()
                            : super.getItem();
                }

                @Override
                public ItemStack remove(int amount) {
                    return hasRetainedLegacyInput()
                            ? machineContainer.removeItem(mappedSlot, amount)
                            : super.remove(amount);
                }

                @Override
                public void set(ItemStack stack) {
                    if (hasRetainedLegacyInput()) {
                        machineContainer.setItem(mappedSlot, stack);
                    } else {
                        super.set(stack);
                    }
                }

                @Override
                public boolean isActive() {
                    return page == CraftingTerminalPage.FUEL;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    if (page != CraftingTerminalPage.FUEL) return false;
                    StorageCoreBlockEntity currentCore = getCore(playerInv.player.level());
                    MachineEnergyTable.Entry entry = MachineEnergyTable.get(mappedSlot);
                    if (currentCore == null || currentCore.isConflicted()
                            || entry == null || !entry.accepts(stack)) return false;
                    if (hasRetainedLegacyInput()) return false;
                    return entry.category() == MachineEnergyTable.Category.CONSUMABLE
                            ? currentCore.canAddAxeEnergy(stack)
                            : entry.maxInstalledCount() > 0;
                }

                @Override
                public int getMaxStackSize(ItemStack stack) {
                    MachineEnergyTable.Entry entry = MachineEnergyTable.get(mappedSlot);
                    if (entry == null) return 0;
                    if (entry.category() == MachineEnergyTable.Category.CONSUMABLE) {
                        return Math.min(64, stack.getMaxStackSize());
                    }
                    return Math.min(entry.maxInstalledCount(), stack.getMaxStackSize());
                }

                @Override
                public boolean mayPickup(Player player) {
                    return page == CraftingTerminalPage.FUEL && super.mayPickup(player);
                }
            });
        }

        selectionContainer = new SimpleContainer(SELECTION_SLOTS);
        for (int i = 0; i < SELECTION_SLOTS; i++) {
            this.addSlot(new Slot(selectionContainer, i, -9999, -9999) {
                @Override public boolean isActive() { return false; }
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });
        }
    }

    public ItemStack getSelectedStack() {
        return selectionContainer != null
                ? selectionContainer.getItem(PRESENTATION_OUTPUT_SLOT)
                : ItemStack.EMPTY;
    }

    public List<ItemStack> getMissingPreview() {
        List<ItemStack> missing = new ArrayList<>();
        for (IngredientPreview ingredient : getIngredientPreview()) {
            if (ingredient.available() < ingredient.required()) {
                missing.add(ingredient.stack().copyWithCount(ingredient.required()));
            }
        }
        return missing;
    }

    public List<IngredientPreview> getIngredientPreview() {
        List<IngredientPreview> result = new ArrayList<>();
        if (selectionContainer == null) return result;
        for (int ingredient = 0; ingredient < MAX_INGREDIENTS; ingredient++) {
            ItemStack stack = selectionContainer.getItem(ITEM_RESOURCE_SLOT_START + ingredient);
            if (stack.isEmpty()) continue;
            int required = stack.getCount();
            result.add(new IngredientPreview(
                    stack.copyWithCount(1), ingredientAvailable[ingredient], required));
        }
        return result;
    }

    public RecipePresentation getRecipePresentation() {
        if (selectionContainer == null) return RecipePresentation.empty();
        RecipePresentation.Metadata metadata = RecipePresentation.metadataFromCarrier(
                selectionContainer.getItem(PRESENTATION_METADATA_SLOT));
        if (metadata == null) return RecipePresentation.empty();

        List<ItemStack> inputs = new ArrayList<>(RecipePresentation.MAX_INPUTS);
        for (int input = 0; input < RecipePresentation.MAX_INPUTS; input++) {
            inputs.add(selectionContainer.getItem(PRESENTATION_INPUT_SLOT_START + input).copy());
        }
        List<RecipePresentation.Resource> resources = new ArrayList<>();
        for (int item = 0; item < metadata.itemResourceCount(); item++) {
            ItemStack stack = selectionContainer.getItem(ITEM_RESOURCE_SLOT_START + item);
            if (stack.isEmpty()) {
                throw new IllegalStateException("Recipe presentation item resource is missing");
            }
            resources.add(RecipePresentation.Resource.item(
                    stack.copyWithCount(1), ingredientAvailable[item], stack.getCount()));
        }
        for (EnergyPreview energy : getEnergyPreview()) {
            resources.add(RecipePresentation.Resource.energy(
                    energy.type(), energy.available(), energy.required()));
        }
        if (metadata.toolRequired() > 0) {
            ItemStack tool = selectionContainer.getItem(PRESENTATION_TOOL_SLOT);
            if (tool.isEmpty()) {
                throw new IllegalStateException("Recipe presentation tool resource is missing");
            }
            resources.add(RecipePresentation.Resource.tool(
                    tool, metadata.toolAvailable(), metadata.toolRequired(), metadata.toolInfinite()));
        }
        return new RecipePresentation(
                metadata,
                inputs,
                selectionContainer.getItem(PRESENTATION_OUTPUT_SLOT),
                selectionContainer.getItem(PRESENTATION_STATION_SLOT),
                resources);
    }

    public List<EnergyPreview> getEnergyPreview() {
        List<EnergyPreview> result = new ArrayList<>(2);
        EnergyType processType = getCurrentProcessEnergyType();
        if (processType != null && processRequired > 0) {
            result.add(new EnergyPreview(processType, getEnergyAmount(processType), processRequired));
        }
        if (fuelRequired > 0) {
            result.add(new EnergyPreview(
                    EnergyType.FURNACE_FUEL, getEnergyAmount(EnergyType.FURNACE_FUEL), fuelRequired));
        }
        return result;
    }

    private EnergyType getCurrentProcessEnergyType() {
        return switch (currentRecipeTypeOrder) {
            case 1 -> EnergyType.SMELTING_ENERGY;
            case 2 -> EnergyType.BLASTING_ENERGY;
            case 3 -> EnergyType.SMOKING_ENERGY;
            case 4 -> EnergyType.CAMPFIRE_ENERGY;
            default -> null;
        };
    }

    public int getCraftableCount() {
        return craftableCount;
    }

    public int getCurrentRecipeIndex() {
        return currentRecipeIndex;
    }

    public int getRecipeCount() {
        return recipeCount;
    }

    public String getCurrentRecipeTypeLabel() {
        return switch (currentRecipeTypeOrder) {
            case 0 -> "Crafting";
            case 1 -> "Smelting";
            case 2 -> "Blasting";
            case 3 -> "Smoking";
            case 4 -> "Campfire";
            case 5 -> "Stonecutting";
            case 6 -> "Smithing";
            case 7 -> "Axe";
            default -> "No recipe";
        };
    }

    public boolean isUsePlayerInventory() {
        return usePlayerInventory;
    }

    public TerminalOutputDestination getOutputDestination() {
        return outputDestination;
    }

    public CraftingTerminalPage getPage() {
        return page;
    }

    public EnergyType getSelectedFuelTarget() {
        return selectedFuelTarget;
    }

    public long getEnergyAmount(EnergyType type) {
        return energyAmounts[type.ordinal()];
    }

    public long getAxeEnergyAmount() {
        return axeEnergyAmount;
    }

    public boolean hasInfiniteAxeEnergy() {
        return infiniteAxeEnergy;
    }

    static int fuelTargetButtonId(EnergyType target) {
        int index = FUEL_TARGETS.indexOf(target);
        if (index < 0) throw new IllegalArgumentException("Not a fuel target: " + target);
        return FUEL_TARGET_BUTTON_BASE + index;
    }

    static List<EnergyType> fuelTargets() {
        return FUEL_TARGETS;
    }

    private boolean isCompatibleWithSelectedFuelTarget(ItemStack stack) {
        if (!FuelTable.isFuel(stack)) return false;
        return selectedFuelTarget == null || FuelTable.getFuelValues(stack).stream()
                .anyMatch(value -> value.pool() == selectedFuelTarget);
    }

    private FuelValue resolveFuelValue(ItemStack stack, StorageCoreBlockEntity core) {
        if (selectedFuelTarget == null) {
            return FuelTable.getAutoFuelValue(stack, core::getEnergy);
        }
        return FuelTable.getFuelValues(stack).stream()
                .filter(value -> value.pool() == selectedFuelTarget)
                .findFirst()
                .orElse(null);
    }

    private void onFuelInputChanged() {
        if (processingFuelInput || fuelContainer == null || playerInventory == null
                || page != CraftingTerminalPage.FUEL) return;
        Player player = playerInventory.player;
        if (player.level().isClientSide()) return;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null) return;
        ItemStack stack = fuelContainer.getItem(0);
        if (!stack.isEmpty()) {
            convertFuelStack(core, stack, getSlot(FUEL_INPUT_SLOT), player);
        }
    }

    private void onAxeInputChanged() {
        if (processingAxeInput || axeInputContainer == null || playerInventory == null
                || page != CraftingTerminalPage.FUEL) return;
        Player player = playerInventory.player;
        if (player.level().isClientSide()) return;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null) return;
        ItemStack stack = axeInputContainer.getItem(0);
        if (stack.isEmpty() || !core.addAxeEnergy(stack)) return;

        processingAxeInput = true;
        try {
            axeInputContainer.removeItemNoUpdate(0);
            axeInputContainer.setChanged();
        } finally {
            processingAxeInput = false;
        }
        refreshEnergyAmounts(core);
    }

    private boolean convertFuelStack(
            StorageCoreBlockEntity core,
            ItemStack stack,
            Slot sourceSlot,
            Player player
    ) {
        FuelValue fuelValue = resolveFuelValue(stack, core);
        if (fuelValue == null) return false;
        int convertedCount = stack.getCount();
        ItemStack remainder = stack.hasCraftingRemainingItem()
                ? stack.getCraftingRemainingItem()
                : ItemStack.EMPTY;
        if (!core.addFuel(stack, fuelValue.pool())) return false;

        processingFuelInput = true;
        try {
            if (remainder.isEmpty()) {
                sourceSlot.set(ItemStack.EMPTY);
            } else {
                returnFuelRemainders(sourceSlot, remainder, convertedCount, player);
            }
            sourceSlot.setChanged();
        } finally {
            processingFuelInput = false;
        }
        refreshEnergyAmounts(core);
        return true;
    }

    private void returnFuelRemainders(Slot sourceSlot, ItemStack template, int count, Player player) {
        int inSlot = Math.min(count, template.getMaxStackSize());
        sourceSlot.set(template.copyWithCount(inSlot));
        int remaining = count - inSlot;
        while (remaining > 0) {
            int amount = Math.min(remaining, template.getMaxStackSize());
            ItemStack extra = template.copyWithCount(amount);
            if (!player.getInventory().add(extra) && !extra.isEmpty()) {
                player.drop(extra, false);
            }
            remaining -= amount;
        }
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        if (slotIndex == FUEL_INPUT_SLOT && page != CraftingTerminalPage.FUEL) return;
        if (slotIndex >= MACHINE_SLOT_START && slotIndex < MACHINE_SLOT_END
                && page != CraftingTerminalPage.FUEL) return;
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS) {
            if (!page.isItemPage()) return;
            if (clickType == ClickType.QUICK_MOVE && (button == 0 || button == 1)) {
                if (page == CraftingTerminalPage.STORAGE) {
                    super.clicked(slotIndex, button, clickType, player);
                }
                return;
            }
            if (clickType != ClickType.PICKUP || button < 0 || button > 1) return;
            if (!player.level().isClientSide()) {
                Slot slot = getSlot(slotIndex);
                ItemStack displayStack = slot.getItem();
                if (!displayStack.isEmpty() && getCarried().isEmpty()) {
                    selectItem(player.level(), displayStack);
                    StorageCoreBlockEntity core = getCore(player.level());
                    if (core != null) updatePreview(core, player);
                    broadcastChanges();
                }
            }
            return;
        }
        super.clicked(slotIndex, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        if (index == FUEL_INPUT_SLOT) {
            ItemStack original = slot.getItem().copy();
            ItemStack stack = slot.getItem();
            if (!moveItemStackTo(stack, DISPLAY_SLOTS, FUEL_INPUT_SLOT, false)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            return original;
        }

        if (index >= MACHINE_SLOT_START && index < MACHINE_SLOT_END) {
            if (page != CraftingTerminalPage.FUEL) return ItemStack.EMPTY;
            ItemStack original = slot.getItem().copy();
            ItemStack stack = slot.getItem();
            if (!moveItemStackTo(stack, DISPLAY_SLOTS, FUEL_INPUT_SLOT, false)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            broadcastChanges();
            return original;
        }

        if (page == CraftingTerminalPage.FUEL) {
            if (index < DISPLAY_SLOTS || index >= FUEL_INPUT_SLOT) return ItemStack.EMPTY;
            ItemStack original = slot.getItem().copy();
            if (!player.level().isClientSide()) {
                StorageCoreBlockEntity core = getCore(player.level());
                int machineSlot = MachineEnergyTable.findSlot(slot.getItem());
                if (machineSlot >= 0) {
                    if (core == null || core.isConflicted()) return ItemStack.EMPTY;
                    ItemStack stack = slot.getItem();
                    MachineEnergyTable.Entry entry = MachineEnergyTable.get(machineSlot);
                    if (entry != null && entry.category() == MachineEnergyTable.Category.CONSUMABLE) {
                        if (!core.addAxeEnergy(stack)) return ItemStack.EMPTY;
                        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
                        else slot.setChanged();
                        refreshEnergyAmounts(core);
                        broadcastChanges();
                        return original;
                    }
                    if (!moveItemStackTo(stack,
                            MACHINE_SLOT_START + machineSlot,
                            MACHINE_SLOT_START + machineSlot + 1,
                            false)) return ItemStack.EMPTY;
                    if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
                    else slot.setChanged();
                    broadcastChanges();
                    return original;
                }
                if (core != null && convertFuelStack(core, slot.getItem(), slot, player)) {
                    broadcastChanges();
                    return original;
                }
            }
            return ItemStack.EMPTY;
        }

        if (page == CraftingTerminalPage.CRAFTABLE && index < DISPLAY_SLOTS) {
            return ItemStack.EMPTY;
        }

        return super.quickMoveStack(player, index);
    }

    private void selectItem(Level level, ItemStack stack) {
        ItemStack identity = TerminalDisplayStack.strip(stack);
        selectedRecipeId = null;
        selectedKey = ItemKey.of(identity);
        selectionContainer.setItem(PRESENTATION_OUTPUT_SLOT, selectedKey.toStack(1));
        lookUpRecipes(level, identity);
    }

    public boolean selectRecipe(Level level, int displaySlot, ResourceLocation recipeId, Player player) {
        if (!page.isItemPage() || level.isClientSide()
                || displaySlot < 0 || displaySlot >= DISPLAY_SLOTS) return false;
        StorageCoreBlockEntity core = getCore(level);
        if (core == null) return false;
        ItemStack displayStack = getSlot(displaySlot).getItem();
        if (displayStack.isEmpty()) return false;
        if (page == CraftingTerminalPage.CRAFTABLE) {
            if (!isCraftableOutput(core, displayStack, player)) return false;
        } else if (core.getItemCount(ItemKey.of(displayStack)) <= 0) {
            return false;
        }

        RecipeHolder<?> holder = resolveRecipeById(level, core, recipeId);
        if (holder == null || !supportsRecipeContract(holder.value())
                || !CraftingStationTable.isAvailable(core, holder.value())) return false;
        ItemStack result = holder.value().getResultItem(level.registryAccess());
        if (!ItemStack.isSameItemSameComponents(result, TerminalDisplayStack.strip(displayStack))) return false;
        return selectRecipeById(level, recipeId, player, core);
    }

    public boolean handleRecipeRequest(
            Level level,
            ResourceLocation recipeId,
            int amount,
            CraftingDestination destination,
            Player player
    ) {
        if (!page.isItemPage() || level.isClientSide()
                || destination == null || amount <= 0 || amount > MAX_RECIPE_REQUEST) return false;
        StorageCoreBlockEntity core = getCore(level);
        if (core == null || core.isConflicted()) return false;
        RecipeHolder<?> holder = resolveRecipeById(level, core, recipeId);
        if (holder == null || !supportsRecipeContract(holder.value())
                || !CraftingStationTable.isAvailable(core, holder.value())) return false;
        ItemStack result = holder.value().getResultItem(level.registryAccess());
        if (result.isEmpty()) return false;
        if (destination == CraftingDestination.NONE) {
            return selectRecipeById(level, recipeId, player, core);
        }

        CraftPreview preview = computeCraftPreviewFor(holder.value(), core, player);
        int maximumCrafts = Math.min(amount, preview.craftable());
        DeliveryTarget deliveryTarget = DeliveryTarget.from(destination);
        int crafts = 0;
        IngredientPlan ingredientPlan = null;
        DeliveryPlan deliveryPlan = null;
        for (int candidate = maximumCrafts; candidate > 0; candidate--) {
            IngredientPlan candidatePlan = planIngredients(
                    core, recipeIngredients(holder.value()), candidate, player);
            DeliveryPlan candidateDelivery = candidatePlan == null ? null : planDelivery(
                    core, candidatePlan, holder.value(), candidate, deliveryTarget, player);
            if (candidateDelivery != null) {
                crafts = candidate;
                ingredientPlan = candidatePlan;
                deliveryPlan = candidateDelivery;
                break;
            }
        }
        if (crafts <= 0 || ingredientPlan == null || deliveryPlan == null) return false;
        EnergyCost energyCost = RecipeEnergyTable.getCost(holder.value());
        if (!commitCraft(core, ingredientPlan, deliveryPlan, player, energyCost, crafts)) return false;
        selectRecipeById(level, recipeId, player, core);
        refreshDisplayItems(core);
        updatePreview(core, player);
        broadcastChanges();
        return true;
    }

    private boolean selectRecipeById(
            Level level,
            ResourceLocation recipeId,
            Player player,
            StorageCoreBlockEntity core
    ) {
        RecipeHolder<?> holder = resolveRecipeById(level, core, recipeId);
        if (holder == null || !supportsRecipeContract(holder.value())
                || !CraftingStationTable.isAvailable(core, holder.value())) return false;
        ItemStack result = holder.value().getResultItem(level.registryAccess());
        if (result.isEmpty()) return false;

        selectItem(level, result);
        for (int i = 0; i < currentRecipes.size(); i++) {
            if (!currentRecipes.get(i).id().equals(recipeId)) continue;
            selectedRecipeId = recipeId;
            currentRecipeIndex = i;
            syncRecipeMetadata();
            updatePreview(core, player);
            broadcastChanges();
            return true;
        }
        clearSelection();
        return false;
    }

    private void clearSelection() {
        selectedKey = null;
        selectedRecipeId = null;
        selectionContainer.clearContent();
        currentRecipes.clear();
        currentRecipeIndex = 0;
        recipeCount = 0;
        currentRecipeTypeOrder = -1;
        craftableCount = 0;
        Arrays.fill(ingredientAvailable, 0);
        processRequired = 0;
        fuelRequired = 0;
    }

    void lookUpRecipes(Level level, ItemStack output) {
        output = TerminalDisplayStack.strip(output);
        selectedRecipeId = null;
        currentRecipes.clear();
        currentRecipeIndex = 0;
        dirtyRecipes = false;
        lastCheckedItem = ItemStack.hashItemAndComponents(output);
        RecipeManager manager = level.getRecipeManager();
        StorageCoreBlockEntity core = getCore(level);
        if (core == null) {
            syncRecipeMetadata();
            return;
        }

        for (RecipeType<?> type : SUPPORTED_RECIPE_TYPES) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) manager.getAllRecipesFor((RecipeType) type);
            for (RecipeHolder<?> holder : holders) {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (supportsRecipeContract(recipe)
                        && CraftingStationTable.isAvailable(core, recipe)
                        && ItemStack.isSameItemSameComponents(result, output)) {
                    currentRecipes.add(holder);
                }
            }
        }
        for (RecipeHolder<?> holder : axeTransformationCatalog.recipes(level, core)) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (ItemStack.isSameItemSameComponents(result, output)) currentRecipes.add(holder);
        }

        currentRecipes.sort(Comparator
                .comparingInt((RecipeHolder<?> holder) -> getRecipeSortOrder(holder.value()))
                .thenComparing(holder -> holder.id().toString()));
        syncRecipeMetadata();
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

    private static int getRecipeSortOrder(Recipe<?> recipe) {
        if (recipe instanceof AxeTransformationRecipe) return 7;
        return getRecipeSortOrder(recipe.getType());
    }

    static List<RecipeType<?>> supportedRecipeTypes() {
        return SUPPORTED_RECIPE_TYPES;
    }

    public List<RecipeHolder<?>> getCurrentRecipes() {
        return currentRecipes;
    }

    public void prevRecipe() {
        if (currentRecipes.isEmpty()) return;
        currentRecipeIndex = (currentRecipeIndex - 1 + currentRecipes.size()) % currentRecipes.size();
        syncRecipeMetadata();
    }

    public void nextRecipe() {
        if (currentRecipes.isEmpty()) return;
        currentRecipeIndex = (currentRecipeIndex + 1) % currentRecipes.size();
        syncRecipeMetadata();
    }

    private void syncRecipeMetadata() {
        recipeCount = currentRecipes.size();
        if (currentRecipes.isEmpty() || currentRecipeIndex >= currentRecipes.size()) {
            currentRecipeTypeOrder = -1;
            clearRecipePresentation();
        } else {
            RecipeHolder<?> holder = currentRecipes.get(currentRecipeIndex);
            if (selectedRecipeId != null) selectedRecipeId = holder.id();
            currentRecipeTypeOrder = getRecipeSortOrder(holder.value());
            if (!playerInventory.player.level().isClientSide()) {
                StorageCoreBlockEntity core = getCore(playerInventory.player.level());
                if (core == null) {
                    clearRecipePresentation();
                } else {
                    applyPreviewData(emptyCraftPreview());
                    syncRecipePresentation(
                            holder,
                            emptyCraftPreview(),
                            snapshotIngredientSources(core, playerInventory.player),
                            core,
                            false);
                }
            }
        }
    }

    public void toggleUsePlayerInventory() {
        usePlayerInventory = !usePlayerInventory;
    }

    public void craftItem(int count, Player player) {
        tryCraftItem(count, player);
    }

    private boolean tryCraftItem(long count, Player player) {
        if (count <= 0 || !page.isItemPage()
                || currentRecipes.isEmpty() || currentRecipeIndex >= currentRecipes.size()) return false;
        if (player.level().isClientSide()) return false;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null || core.isConflicted()) return false;

        RecipeHolder<?> holder = resolveCurrentRecipe(player.level());
        if (holder == null) return false;
        Recipe<?> recipe = holder.value();
        EnergyCost energyCost = RecipeEnergyTable.getCost(recipe);
        if (!hasEnergyForCrafts(core, energyCost, count)) return false;
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        CraftPlan plan = planCraft(
                core, recipe, count, DeliveryTarget.from(outputDestination), player, sources);
        if (plan == null || !commitCraft(
                core, plan.ingredients(), plan.delivery(), player, energyCost, plan.crafts())) return false;

        refreshDisplayItems(core);
        updatePreview(core, player);
        broadcastChanges();
        return true;
    }

    private void craftMaximum(Player player) {
        if (!page.isItemPage() || player.level().isClientSide()) return;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null || core.isConflicted()) return;
        RecipeHolder<?> holder = resolveCurrentRecipe(player.level());
        if (holder == null) return;
        Recipe<?> recipe = holder.value();
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        long resourceMaximum = maximumResourceCrafts(recipe, core, sources);
        CraftPlan plan = largestDeliverablePlan(
                core, recipe, resourceMaximum, DeliveryTarget.from(outputDestination), player, sources);
        EnergyCost energyCost = RecipeEnergyTable.getCost(recipe);
        if (plan == null || !commitCraft(
                core, plan.ingredients(), plan.delivery(), player, energyCost, plan.crafts())) return;
        refreshDisplayItems(core);
        updatePreview(core, player);
        broadcastChanges();
    }

    private CraftPlan largestDeliverablePlan(
            StorageCoreBlockEntity core,
            Recipe<?> recipe,
            long maximum,
            DeliveryTarget destination,
            Player player,
            List<IngredientSource> sources
    ) {
        if (maximum <= 0) return null;
        CraftPlan maximumPlan = planCraft(core, recipe, maximum, destination, player, sources);
        if (maximumPlan != null) return maximumPlan;

        long low = 0;
        long high = maximum - 1;
        CraftPlan best = null;
        while (low < high) {
            long distance = high - low;
            long candidate = low + distance / 2 + distance % 2;
            CraftPlan candidatePlan = planCraft(core, recipe, candidate, destination, player, sources);
            if (candidatePlan != null) {
                low = candidate;
                best = candidatePlan;
            } else {
                high = candidate - 1;
            }
        }
        if (low <= 0) return null;
        return best != null && best.crafts() == low
                ? best
                : planCraft(core, recipe, low, destination, player, sources);
    }

    private CraftPlan planCraft(
            StorageCoreBlockEntity core,
            Recipe<?> recipe,
            long crafts,
            DeliveryTarget destination,
            Player player,
            List<IngredientSource> sources
    ) {
        if (crafts <= 0 || !hasEnergyForCrafts(core, RecipeEnergyTable.getCost(recipe), crafts)) return null;
        IngredientPlan ingredients = planIngredients(recipeIngredients(recipe), crafts, sources);
        if (ingredients == null) return null;
        DeliveryPlan delivery = planDelivery(core, ingredients, recipe, crafts, destination, player);
        return delivery == null ? null : new CraftPlan(crafts, ingredients, delivery);
    }

    private long maximumResourceCrafts(
            Recipe<?> recipe,
            StorageCoreBlockEntity core,
            List<IngredientSource> sources
    ) {
        List<RecipeIngredient> ingredients = recipeIngredients(recipe);
        long totalAvailable = 0;
        for (IngredientSource source : sources) {
            totalAvailable = saturatingAdd(totalAvailable, source.amount());
        }
        long ingredientsPerCraft = ingredients.stream().filter(ingredient -> !ingredient.isEmpty()).count();
        if (ingredientsPerCraft <= 0) return 0;
        long high = totalAvailable / ingredientsPerCraft;
        for (IngredientNeed need : summarizeIngredients(ingredients)) {
            long available = 0;
            for (IngredientSource source : sources) {
                if (need.ingredient().test(source.stack())) {
                    available = saturatingAdd(available, source.amount());
                }
            }
            high = Math.min(high, available / need.count());
        }
        if (recipe instanceof AxeTransformationRecipe) {
            long available = core.hasInfiniteAxeEnergy() ? Long.MAX_VALUE : core.getAxeEnergy();
            high = Math.min(high, available);
        }
        EnergyCost cost = RecipeEnergyTable.getCost(recipe);
        if (cost != null) {
            if (cost.processAmount() > 0) {
                high = Math.min(high, core.getEnergy(cost.processType()) / cost.processAmount());
            }
            if (cost.fuelAmount() > 0) {
                high = Math.min(high, core.getEnergy(cost.fuelType()) / cost.fuelAmount());
            }
        }
        if (high <= 0) return 0;

        long low = 0;
        while (low < high) {
            long distance = high - low;
            long candidate = low + distance / 2 + distance % 2;
            if (planIngredients(ingredients, candidate, sources) != null) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }
        return low;
    }

    private static boolean hasEnergyForCrafts(
            StorageCoreBlockEntity core,
            EnergyCost energyCost,
            long crafts
    ) {
        if (crafts <= 0) return false;
        if (energyCost == null) return true;
        try {
            long processNeed = Math.multiplyExact(energyCost.processAmount(), crafts);
            long fuelNeed = Math.multiplyExact(energyCost.fuelAmount(), crafts);
            return core.getEnergy(energyCost.processType()) >= processNeed
                    && core.getEnergy(energyCost.fuelType()) >= fuelNeed;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private RecipeHolder<?> resolveCurrentRecipe(Level level) {
        if (currentRecipes.isEmpty() || currentRecipeIndex >= currentRecipes.size()) return null;
        RecipeHolder<?> cachedHolder = currentRecipes.get(currentRecipeIndex);
        StorageCoreBlockEntity core = getCore(level);
        RecipeHolder<?> currentHolder = resolveRecipeById(level, core, cachedHolder.id());
        if (currentHolder == null || !supportsRecipeContract(currentHolder.value())
                || !CraftingStationTable.isAvailable(core, currentHolder.value())) return null;

        ItemStack result = currentHolder.value().getResultItem(level.registryAccess());
        if (result.isEmpty()) return null;
        ItemStack selectedOutput = selectedKey != null
                ? selectedKey.toStack(1)
                : cachedHolder.value().getResultItem(level.registryAccess());
        if (!ItemStack.isSameItemSameComponents(result, selectedOutput)) return null;

        currentRecipes.set(currentRecipeIndex, currentHolder);
        return currentHolder;
    }

    private RecipeHolder<?> resolveRecipeById(
            Level level,
            StorageCoreBlockEntity core,
            ResourceLocation recipeId
    ) {
        RecipeHolder<?> managed = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (managed != null) return managed;
        return core == null ? null : axeTransformationCatalog.byId(level, core, recipeId);
    }

    public CraftPreview computeCraftPreview(StorageCoreBlockEntity core, Player player) {
        if (core == null || currentRecipes.isEmpty() || currentRecipeIndex >= currentRecipes.size())
            return emptyCraftPreview();
        Level level = core.getLevel();
        if (level == null) return emptyCraftPreview();
        RecipeHolder<?> holder = resolveCurrentRecipe(level);
        if (holder == null) return emptyCraftPreview();
        return computeCraftPreviewFor(holder.value(), core, player);
    }

    private static CraftPreview emptyCraftPreview() {
        return new CraftPreview(0, List.of(), List.of(), List.of());
    }

    private CraftPreview computeCraftPreviewFor(Recipe<?> recipe, StorageCoreBlockEntity core, Player player) {
        return computeCraftPreviewFor(recipe, core, snapshotIngredientSources(core, player));
    }

    private CraftPreview computeCraftPreviewFor(
            Recipe<?> recipe,
            StorageCoreBlockEntity core,
            List<IngredientSource> sources
    ) {
        return computeCraftPreviewFor(recipe, core, sources, PREVIEW_CAP);
    }

    private CraftPreview computeCraftPreviewFor(
            Recipe<?> recipe,
            StorageCoreBlockEntity core,
            List<IngredientSource> sources,
            int craftLimit
    ) {
        if (core.isConflicted() || !supportsRecipeContract(recipe)
                || !CraftingStationTable.isAvailable(core, recipe)) {
            return emptyCraftPreview();
        }
        long max = craftLimit;
        List<ItemStack> missing = new ArrayList<>();
        List<IngredientPreview> ingredientPreviews = new ArrayList<>();
        List<RecipeIngredient> ingredients = recipeIngredients(recipe);
        for (IngredientNeed need : summarizeIngredients(ingredients)) {
            long avail = 0;
            for (IngredientSource source : sources) {
                if (need.ingredient().test(source.stack())) {
                    avail = avail > Long.MAX_VALUE - source.amount()
                            ? Long.MAX_VALUE
                            : avail + source.amount();
                }
            }
            max = Math.min(max, avail / need.count());
            ItemStack representative = ingredientRepresentative(need.ingredient(), sources);
            if (!representative.isEmpty()) {
                ingredientPreviews.add(new IngredientPreview(
                        representative.copyWithCount(1), avail, Math.toIntExact(need.count())));
            }
            if (avail < need.count()) {
                List<ItemStack> items = need.ingredient().items();
                if (!items.isEmpty() && missing.size() < MAX_INGREDIENTS) missing.add(items.getFirst().copy());
            }
        }
        if (recipe instanceof AxeTransformationRecipe) {
            long available = core.hasInfiniteAxeEnergy() ? Long.MAX_VALUE : core.getAxeEnergy();
            max = Math.min(max, available);
        }

        EnergyCost cost = RecipeEnergyTable.getCost(recipe);
        List<EnergyPreview> energyPreviews = new ArrayList<>(2);
        if (cost != null) {
            energyPreviews.add(new EnergyPreview(
                    cost.processType(), core.getEnergy(cost.processType()), cost.processAmount()));
            energyPreviews.add(new EnergyPreview(
                    cost.fuelType(), core.getEnergy(cost.fuelType()), cost.fuelAmount()));
            if (cost.processAmount() > 0)
                max = Math.min(max, core.getEnergy(cost.processType()) / cost.processAmount());
            if (cost.fuelAmount() > 0)
                max = Math.min(max, core.getEnergy(cost.fuelType()) / cost.fuelAmount());
        }

        int upperBound = (int) Math.clamp(max, 0, craftLimit);
        int low = 0;
        int high = upperBound;
        while (low < high) {
            int candidate = (int) (((long) low + high + 1L) / 2L);
            if (planIngredients(ingredients, candidate, sources) != null) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }
        int craftable = low;
        if (craftable == 0 && upperBound > 0 && missing.isEmpty()) {
            for (RecipeIngredient ingredient : ingredients) {
                if (!ingredient.isEmpty() && !ingredient.items().isEmpty()) {
                    missing.add(ingredient.items().getFirst().copy());
                    break;
                }
            }
        }
        if (craftable > 0) missing.clear();
        return new CraftPreview(craftable, missing, ingredientPreviews, energyPreviews);
    }

    private static ItemStack ingredientRepresentative(
            RecipeIngredient ingredient,
            List<IngredientSource> sources
    ) {
        for (IngredientSource source : sources) {
            if (ingredient.test(source.stack())) return source.stack().copyWithCount(1);
        }
        List<ItemStack> displayItems = ingredient.items();
        if (!displayItems.isEmpty()) return displayItems.getFirst().copyWithCount(1);
        return ItemStack.EMPTY;
    }

    private void syncRecipePresentation(
            RecipeHolder<?> holder,
            CraftPreview preview,
            List<IngredientSource> sources,
            StorageCoreBlockEntity core,
            boolean includeResources
    ) {
        Recipe<?> recipe = holder.value();
        RecipePresentationKind kind = presentationKind(recipe);
        int width = presentationWidth(recipe);
        int height = presentationHeight(recipe);
        List<ItemStack> inputs = presentationInputs(recipe, sources);
        ItemStack output = presentationOutput(holder, sources, core.getLevel());
        ItemStack station = presentationStation(recipe, core);
        if (output.isEmpty()) throw new IllegalStateException("Selected recipe has no presentation output");

        selectionContainer.clearContent();
        selectionContainer.setItem(PRESENTATION_OUTPUT_SLOT, output.copy());
        selectionContainer.setItem(PRESENTATION_STATION_SLOT, station.copyWithCount(1));
        for (int input = 0; input < RecipePresentation.MAX_INPUTS; input++) {
            selectionContainer.setItem(
                    PRESENTATION_INPUT_SLOT_START + input, inputs.get(input).copy());
        }

        int itemResourceCount = 0;
        if (includeResources) {
            List<IngredientNeed> needs = summarizeIngredients(recipeIngredients(recipe));
            itemResourceCount = needs.size();
            for (int item = 0; item < itemResourceCount; item++) {
                IngredientPreview resource;
                if (item < preview.ingredients().size()) {
                    resource = preview.ingredients().get(item);
                } else {
                    IngredientNeed need = needs.get(item);
                    long available = 0;
                    for (IngredientSource source : sources) {
                        if (need.ingredient().test(source.stack())) {
                            available = saturatingAdd(available, source.amount());
                        }
                    }
                    resource = new IngredientPreview(
                            ingredientRepresentative(need.ingredient(), sources),
                            available,
                            Math.toIntExact(need.count()));
                }
                if (resource.stack().isEmpty()) {
                    throw new IllegalStateException("Recipe presentation item resource has no representative");
                }
                selectionContainer.setItem(
                        ITEM_RESOURCE_SLOT_START + item,
                        resource.stack().copyWithCount(resource.required()));
                ingredientAvailable[item] = resource.available();
            }
        }

        long toolAvailable = 0;
        long toolRequired = 0;
        boolean toolInfinite = false;
        if (recipe instanceof AxeTransformationRecipe) {
            toolInfinite = core.hasInfiniteAxeEnergy();
            toolAvailable = toolInfinite ? Long.MAX_VALUE : core.getAxeEnergy();
            if (toolAvailable <= 0) throw new IllegalStateException("Selected axe recipe has no Axe Energy");
            toolRequired = 1;
            selectionContainer.setItem(PRESENTATION_TOOL_SLOT, AxeEnergy.representativeStack());
        }

        RecipePresentation.Metadata metadata = new RecipePresentation.Metadata(
                holder.id(),
                kind,
                width,
                height,
                recipe instanceof ShapelessRecipe,
                itemResourceCount,
                toolAvailable,
                toolRequired,
                toolInfinite);
        selectionContainer.setItem(
                PRESENTATION_METADATA_SLOT, RecipePresentation.metadataCarrier(metadata));
    }

    private void clearRecipePresentation() {
        if (selectionContainer != null) selectionContainer.clearContent();
        Arrays.fill(ingredientAvailable, 0);
        processRequired = 0;
        fuelRequired = 0;
        craftableCount = 0;
    }

    private static RecipePresentationKind presentationKind(Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
            return RecipePresentationKind.CRAFTING;
        }
        if (recipe instanceof AbstractCookingRecipe) return RecipePresentationKind.COOKING;
        if (recipe instanceof StonecutterRecipe) return RecipePresentationKind.STONECUTTING;
        if (recipe instanceof SmithingTransformRecipe) return RecipePresentationKind.SMITHING;
        if (recipe instanceof AxeTransformationRecipe) return RecipePresentationKind.AXE;
        throw new IllegalArgumentException("Unsupported recipe presentation: " + recipe.getClass().getName());
    }

    private static int presentationWidth(Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getWidth();
        if (recipe instanceof ShapelessRecipe) return 3;
        if (recipe instanceof SmithingTransformRecipe) return 3;
        return 1;
    }

    private static int presentationHeight(Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getHeight();
        if (recipe instanceof ShapelessRecipe) return 3;
        return 1;
    }

    private static List<ItemStack> presentationInputs(
            Recipe<?> recipe,
            List<IngredientSource> sources
    ) {
        List<RecipeIngredient> ingredients = recipe instanceof SmithingTransformRecipe
                ? recipeIngredients(recipe)
                : recipe.getIngredients().stream().map(RecipeIngredient::of).toList();
        if (ingredients.size() > RecipePresentation.MAX_INPUTS) {
            throw new IllegalArgumentException("Recipe presentation has more than nine inputs");
        }
        List<ItemStack> inputs = new ArrayList<>(
                Collections.nCopies(RecipePresentation.MAX_INPUTS, ItemStack.EMPTY));
        for (int input = 0; input < ingredients.size(); input++) {
            RecipeIngredient ingredient = ingredients.get(input);
            if (!ingredient.isEmpty()) {
                inputs.set(input, ingredientRepresentative(ingredient, sources));
            }
        }
        return List.copyOf(inputs);
    }

    private ItemStack presentationOutput(
            RecipeHolder<?> holder,
            List<IngredientSource> sources,
            Level level
    ) {
        if (level == null) throw new IllegalStateException("Selected recipe has no level");
        Recipe<?> recipe = holder.value();
        if (recipe instanceof SmithingTransformRecipe smithing) {
            List<RecipeIngredient> roles = recipeIngredients(recipe);
            if (roles.size() != 3) {
                throw new IllegalStateException("Smithing presentation requires three input roles");
            }
            List<ItemStack> inputs = roles.stream()
                    .map(role -> ingredientRepresentative(role, sources))
                    .toList();
            if (inputs.stream().anyMatch(ItemStack::isEmpty)) {
                RecipePresentation.Metadata previous = RecipePresentation.metadataFromCarrier(
                        selectionContainer.getItem(PRESENTATION_METADATA_SLOT));
                ItemStack previousOutput = selectionContainer.getItem(PRESENTATION_OUTPUT_SLOT);
                if (previous != null
                        && previous.recipeId().equals(holder.id())
                        && !previousOutput.isEmpty()) {
                    return previousOutput.copy();
                }
                return smithing.getResultItem(level.registryAccess()).copy();
            }
            return smithing.assemble(new SmithingRecipeInput(
                    inputs.get(0), inputs.get(1), inputs.get(2)), level.registryAccess());
        }
        return recipe.getResultItem(level.registryAccess()).copy();
    }

    private static ItemStack presentationStation(
            Recipe<?> recipe,
            StorageCoreBlockEntity core
    ) {
        if (recipe instanceof AxeTransformationRecipe) {
            if (!core.hasAxeEnergy(1)) throw new IllegalStateException("Axe presentation has no Axe Energy");
            return AxeEnergy.representativeStack();
        }
        int stationSlot = CraftingStationTable.requiredSlot(recipe.getType());
        MachineEnergyTable.Entry station = MachineEnergyTable.get(stationSlot);
        if (station == null) throw new IllegalStateException("Recipe presentation has no station descriptor");
        ItemStack installed = core.getMachineContainer().getItem(stationSlot);
        if (!station.accepts(installed)) {
            throw new IllegalStateException("Recipe presentation station is not installed");
        }
        return installed.copyWithCount(1);
    }

    private IngredientPlan planIngredients(
            StorageCoreBlockEntity core,
            List<RecipeIngredient> ingredients,
            long crafts,
            Player player
    ) {
        return planIngredients(ingredients, crafts, snapshotIngredientSources(core, player));
    }

    private List<IngredientSource> snapshotIngredientSources(StorageCoreBlockEntity core, Player player) {
        List<IngredientSource> sources = new ArrayList<>();
        for (ItemStack displayStack : core.getDisplayStacks()) {
            ItemKey key = ItemKey.of(displayStack);
            long amount = core.getItemCount(key);
            if (amount > 0) {
                sources.add(new IngredientSource(key, -1, key.toStack(1), amount));
            }
        }
        if (usePlayerInventory && player != null) {
            for (int slot = 0; slot < PLAYER_INVENTORY_SLOTS; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    sources.add(new IngredientSource(ItemKey.of(stack), slot, stack.copyWithCount(1), stack.getCount()));
                }
            }
        }
        sources.sort(Comparator
                .comparing((IngredientSource source) ->
                        BuiltInRegistries.ITEM.getKey(source.stack().getItem()).toString())
                .thenComparing(source -> source.key().components().toString())
                .thenComparingInt(IngredientSource::playerSlot));
        return List.copyOf(sources);
    }

    private IngredientPlan planIngredients(
            List<RecipeIngredient> ingredients,
            long crafts,
            List<IngredientSource> sources
    ) {
        if (crafts <= 0) return null;
        List<IngredientNeed> needs = aggregateIngredients(ingredients, crafts);
        if (needs.isEmpty()) return null;

        int sourceNode = 0;
        int sourceStart = 1;
        int needStart = sourceStart + sources.size();
        int sinkNode = needStart + needs.size();
        List<List<FlowEdge>> graph = new ArrayList<>(sinkNode + 1);
        for (int i = 0; i <= sinkNode; i++) graph.add(new ArrayList<>());

        long totalRequired = 0;
        for (int i = 0; i < sources.size(); i++) {
            addFlowEdge(graph, sourceNode, sourceStart + i, sources.get(i).amount());
        }
        for (int i = 0; i < needs.size(); i++) {
            IngredientNeed need = needs.get(i);
            try {
                totalRequired = Math.addExact(totalRequired, need.count());
            } catch (ArithmeticException e) {
                return null;
            }
            addFlowEdge(graph, needStart + i, sinkNode, need.count());
            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                IngredientSource source = sources.get(sourceIndex);
                if (need.ingredient().test(source.stack())) {
                    addFlowEdge(graph, sourceStart + sourceIndex, needStart + i, source.amount());
                }
            }
        }
        if (maximumFlow(graph, sourceNode, sinkNode) != totalRequired) return null;

        Map<ItemKey, Long> coreReservations = new HashMap<>();
        Map<Integer, PlayerReservation> playerReservations = new HashMap<>();
        Map<ItemKey, Long> consumedItems = new HashMap<>();
        List<Map<ItemKey, Long>> allocations = new ArrayList<>(needs.size());
        for (int i = 0; i < needs.size(); i++) allocations.add(new LinkedHashMap<>());
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            IngredientSource source = sources.get(sourceIndex);
            long used = 0;
            for (FlowEdge edge : graph.get(sourceStart + sourceIndex)) {
                if (edge.to >= needStart && edge.to < sinkNode) {
                    long allocated = edge.originalCapacity - edge.capacity;
                    used += allocated;
                    if (allocated > 0) {
                        allocations.get(edge.to - needStart).merge(source.key(), allocated, Math::addExact);
                    }
                }
            }
            if (used <= 0) continue;
            consumedItems.merge(source.key(), used, Long::sum);
            if (source.playerSlot() < 0) {
                coreReservations.merge(source.key(), used, Long::sum);
            } else {
                int count = Math.toIntExact(used);
                playerReservations.merge(
                        source.playerSlot(),
                        new PlayerReservation(source.key(), count),
                        (left, right) -> new PlayerReservation(left.key(), Math.addExact(left.count(), right.count()))
                );
            }
        }
        return new IngredientPlan(
                coreReservations,
                playerReservations,
                consumedItems,
                allocations.stream().map(Map::copyOf).toList()
        );
    }

    private DeliveryPlan planDelivery(
            StorageCoreBlockEntity core,
            IngredientPlan ingredientPlan,
            Recipe<?> recipe,
            long crafts,
            DeliveryTarget destination,
            Player player
    ) {
        if (crafts <= 0) return null;
        ToolUsePlan toolUse = recipe instanceof AxeTransformationRecipe
                ? planAxeUse(core, crafts)
                : null;
        if (recipe instanceof AxeTransformationRecipe && toolUse == null) return null;
        List<ItemStack> inventory = new ArrayList<>(PLAYER_INVENTORY_SLOTS);
        for (int slot = 0; slot < PLAYER_INVENTORY_SLOTS; slot++) {
            inventory.add(player.getInventory().getItem(slot).copy());
        }
        for (Map.Entry<Integer, PlayerReservation> entry : ingredientPlan.playerReservations().entrySet()) {
            ItemStack stack = inventory.get(entry.getKey());
            PlayerReservation reservation = entry.getValue();
            if (stack.isEmpty() || !ItemKey.of(stack).equals(reservation.key())
                    || stack.getCount() < reservation.count()) return null;
            stack.shrink(reservation.count());
        }

        ItemStack carried = getCarried().copy();
        Map<ItemKey, Long> primaryOutputs = planPrimaryOutputs(core, ingredientPlan, recipe, crafts);
        if (primaryOutputs == null || primaryOutputs.isEmpty()) return null;
        Map<ItemKey, Long> coreOutputs = new LinkedHashMap<>();
        if (destination == DeliveryTarget.CURSOR) {
            if (primaryOutputs.size() != 1) return null;
            Map.Entry<ItemKey, Long> output = primaryOutputs.entrySet().iterator().next();
            ItemStack result = output.getKey().toStack(1);
            long primaryCount = output.getValue();
            if (primaryCount > result.getMaxStackSize()) return null;
            if (carried.isEmpty()) {
                carried = result.copyWithCount((int) primaryCount);
            } else if (ItemStack.isSameItemSameComponents(carried, result)
                    && primaryCount <= carried.getMaxStackSize() - carried.getCount()) {
                carried.grow((int) primaryCount);
            } else {
                return null;
            }
        } else if (destination == DeliveryTarget.PLAYER) {
            for (Map.Entry<ItemKey, Long> output : primaryOutputs.entrySet()) {
                if (!addOutputToInventoryThenCore(
                        inventory, output.getKey().toStack(1), output.getValue(), coreOutputs)) {
                    return null;
                }
            }
        } else if (destination == DeliveryTarget.STORAGE) {
            for (Map.Entry<ItemKey, Long> output : primaryOutputs.entrySet()) {
                if (!addCoreOutput(coreOutputs, output.getKey(), output.getValue())) return null;
            }
        } else {
            return null;
        }

        for (Map.Entry<ItemKey, Long> entry : ingredientPlan.consumedItems().entrySet()) {
            ItemStack consumed = entry.getKey().toStack(1);
            if (!consumed.hasCraftingRemainingItem()) continue;
            ItemStack remainder = consumed.getCraftingRemainingItem();
            if (remainder.isEmpty()) continue;
            long remainderCount;
            try {
                remainderCount = Math.multiplyExact(entry.getValue(), (long) remainder.getCount());
            } catch (ArithmeticException e) {
                return null;
            }
            if (destination == DeliveryTarget.STORAGE) {
                if (!addCoreOutput(coreOutputs, ItemKey.of(remainder), remainderCount)) return null;
            } else if (!addOutputToInventoryThenCore(
                    inventory, remainder, remainderCount, coreOutputs)) {
                return null;
            }
        }
        if (!coreCanAcceptAfterIngredients(core, ingredientPlan, coreOutputs)) return null;
        return new DeliveryPlan(List.copyOf(inventory), carried, Map.copyOf(coreOutputs), toolUse);
    }

    private static ToolUsePlan planAxeUse(
            StorageCoreBlockEntity core,
            long crafts
    ) {
        return core.hasAxeEnergy(crafts) ? new ToolUsePlan(crafts) : null;
    }

    private static Map<ItemKey, Long> planPrimaryOutputs(
            StorageCoreBlockEntity core,
            IngredientPlan ingredientPlan,
            Recipe<?> recipe,
            long crafts
    ) {
        Level level = core.getLevel();
        if (level == null) return null;
        if (recipe instanceof SmithingTransformRecipe smithing) {
            List<Map<ItemKey, Long>> allocations = ingredientPlan.allocations();
            if (allocations.size() != 3
                    || allocationAmount(allocations.get(0)) != crafts
                    || allocationAmount(allocations.get(1)) != crafts
                    || allocationAmount(allocations.get(2)) != crafts) {
                return null;
            }
            ItemStack template = firstAllocatedStack(allocations.get(0));
            ItemStack addition = firstAllocatedStack(allocations.get(2));
            if (template.isEmpty() || addition.isEmpty()) return null;

            Map<ItemKey, Long> outputs = new LinkedHashMap<>();
            try {
                for (Map.Entry<ItemKey, Long> base : allocations.get(1).entrySet()) {
                    SmithingRecipeInput input = new SmithingRecipeInput(
                            template, base.getKey().toStack(1), addition);
                    if (!smithing.matches(input, level)) return null;
                    ItemStack result = smithing.assemble(input, level.registryAccess());
                    if (result.isEmpty()) return null;
                    long amount = Math.multiplyExact(base.getValue(), (long) result.getCount());
                    outputs.merge(ItemKey.of(result), amount, Math::addExact);
                }
            } catch (ArithmeticException e) {
                return null;
            }
            return outputs;
        }

        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) return null;
        try {
            long amount = Math.multiplyExact((long) result.getCount(), crafts);
            return Map.of(ItemKey.of(result), amount);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static long allocationAmount(Map<ItemKey, Long> allocation) {
        long total = 0;
        try {
            for (long amount : allocation.values()) total = Math.addExact(total, amount);
        } catch (ArithmeticException e) {
            return -1;
        }
        return total;
    }

    private static ItemStack firstAllocatedStack(Map<ItemKey, Long> allocation) {
        for (Map.Entry<ItemKey, Long> entry : allocation.entrySet()) {
            if (entry.getValue() > 0) return entry.getKey().toStack(1);
        }
        return ItemStack.EMPTY;
    }

    private static boolean addOutputToInventoryThenCore(
            List<ItemStack> inventory,
            ItemStack template,
            long amount,
            Map<ItemKey, Long> coreOutputs
    ) {
        if (template.isEmpty() || amount <= 0) return false;
        long remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) continue;
            int inserted = (int) Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
            if (inserted > 0) {
                stack.grow(inserted);
                remaining -= inserted;
            }
        }
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            if (!inventory.get(slot).isEmpty()) continue;
            int inserted = (int) Math.min(remaining, template.getMaxStackSize());
            inventory.set(slot, template.copyWithCount(inserted));
            remaining -= inserted;
        }
        if (remaining > 0) {
            return addCoreOutput(coreOutputs, ItemKey.of(template), remaining);
        }
        return true;
    }

    private static boolean addCoreOutput(
            Map<ItemKey, Long> coreOutputs,
            ItemKey key,
            long amount
    ) {
        if (amount <= 0) return false;
        long existing = coreOutputs.getOrDefault(key, 0L);
        if (existing > Long.MAX_VALUE - amount) return false;
        coreOutputs.put(key, existing + amount);
        return true;
    }

    private static boolean coreCanAcceptAfterIngredients(
            StorageCoreBlockEntity core,
            IngredientPlan ingredientPlan,
            Map<ItemKey, Long> coreOutputs
    ) {
        int typeCount = core.getTypeCount();
        Map<ItemKey, Long> remainingByKey = new HashMap<>();
        for (ItemStack stack : core.getDisplayStacks()) {
            ItemKey key = ItemKey.of(stack);
            remainingByKey.put(key, core.getItemCount(key));
        }
        for (Map.Entry<ItemKey, Long> entry : ingredientPlan.coreReservations().entrySet()) {
            long remaining = remainingByKey.getOrDefault(entry.getKey(), 0L) - entry.getValue();
            if (remaining < 0) return false;
            if (remaining == 0) {
                if (remainingByKey.remove(entry.getKey()) != null) typeCount--;
            } else {
                remainingByKey.put(entry.getKey(), remaining);
            }
        }
        for (Map.Entry<ItemKey, Long> entry : coreOutputs.entrySet()) {
            long existing = remainingByKey.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() <= 0 || existing > Long.MAX_VALUE - entry.getValue()) return false;
            if (existing == 0) {
                if (typeCount >= core.getTotalTypeSlots()) return false;
                typeCount++;
            }
            remainingByKey.put(entry.getKey(), existing + entry.getValue());
        }
        return true;
    }

    private boolean commitCraft(
            StorageCoreBlockEntity core,
            IngredientPlan plan,
            DeliveryPlan delivery,
            Player player,
            EnergyCost energyCost,
            long crafts
    ) {
        core.beginMutationBatch();
        try {
            ToolUsePlan toolUse = delivery.toolUse();
            if (toolUse != null && !core.hasAxeEnergy(toolUse.amount())) return false;
            for (Map.Entry<Integer, PlayerReservation> entry : plan.playerReservations().entrySet()) {
                ItemStack stack = player.getInventory().getItem(entry.getKey());
                PlayerReservation reservation = entry.getValue();
                if (stack.isEmpty() || !ItemKey.of(stack).equals(reservation.key()) || stack.getCount() < reservation.count()) {
                    return false;
                }
            }
            for (Map.Entry<ItemKey, Long> entry : plan.coreReservations().entrySet()) {
                if (!canExtractCoreReservation(core, entry.getKey(), entry.getValue())) {
                    return false;
                }
            }

            Map<ItemKey, Long> extractedFromCore = new LinkedHashMap<>();
            for (Map.Entry<ItemKey, Long> entry : plan.coreReservations().entrySet()) {
                long extracted = core.extractItemCount(
                        entry.getKey(), entry.getValue(), Action.EXECUTE, Actor.magicCrafting());
                if (extracted != entry.getValue()) {
                    if (extracted > 0) extractedFromCore.put(entry.getKey(), extracted);
                    rollbackCoreExtractions(core, extractedFromCore);
                    return false;
                }
                extractedFromCore.put(entry.getKey(), extracted);
            }
            Map<ItemKey, Long> insertedOutputs = new LinkedHashMap<>();
            for (Map.Entry<ItemKey, Long> entry : delivery.coreOutputs().entrySet()) {
                long inserted = insertCoreOutput(core, entry.getKey(), entry.getValue());
                if (inserted != entry.getValue()) {
                    if (inserted > 0) insertedOutputs.put(entry.getKey(), inserted);
                    rollbackCoreOutputs(core, insertedOutputs);
                    rollbackCoreExtractions(core, extractedFromCore);
                    return false;
                }
                insertedOutputs.put(entry.getKey(), inserted);
            }
            if (energyCost != null && !core.consumeEnergy(energyCost, crafts)) {
                rollbackCoreOutputs(core, insertedOutputs);
                rollbackCoreExtractions(core, extractedFromCore);
                return false;
            }
            if (toolUse != null && !core.consumeAxeEnergy(toolUse.amount())) {
                rollbackCoreOutputs(core, insertedOutputs);
                rollbackCoreExtractions(core, extractedFromCore);
                return false;
            }
            for (int slot = 0; slot < delivery.playerInventory().size(); slot++) {
                player.getInventory().setItem(slot, delivery.playerInventory().get(slot).copy());
            }
            player.getInventory().setChanged();
            setCarried(delivery.carried().copy());
            return true;
        } finally {
            core.endMutationBatch();
        }
    }

    private static boolean canExtractCoreReservation(
            StorageCoreBlockEntity core,
            ItemKey key,
            long amount
    ) {
        if (amount <= 0 || core.getItemCount(key) < amount) return false;
        return core.extractItemCount(key, amount, Action.SIMULATE, Actor.magicCrafting()) == amount;
    }

    private void rollbackCoreExtractions(StorageCoreBlockEntity core, Map<ItemKey, Long> extractedStacks) {
        for (Map.Entry<ItemKey, Long> extracted : extractedStacks.entrySet()) {
            long restored = core.insertItemCount(
                    extracted.getKey(), extracted.getValue(), Action.EXECUTE, Actor.magicCrafting());
            if (restored != extracted.getValue()) {
                throw new IllegalStateException("Failed to roll back crafting ingredient extraction");
            }
        }
    }

    private long insertCoreOutput(StorageCoreBlockEntity core, ItemKey key, long amount) {
        return core.insertItemCount(key, amount, Action.EXECUTE, Actor.magicCrafting());
    }

    private void rollbackCoreOutputs(StorageCoreBlockEntity core, Map<ItemKey, Long> insertedOutputs) {
        for (Map.Entry<ItemKey, Long> entry : insertedOutputs.entrySet()) {
            long extracted = core.extractItemCount(
                    entry.getKey(), entry.getValue(), Action.EXECUTE, Actor.magicCrafting());
            if (extracted != entry.getValue()) {
                throw new IllegalStateException("Failed to roll back crafted output insertion");
            }
        }
    }

    private static void addFlowEdge(List<List<FlowEdge>> graph, int from, int to, long capacity) {
        FlowEdge forward = new FlowEdge(to, graph.get(to).size(), capacity);
        FlowEdge reverse = new FlowEdge(from, graph.get(from).size(), 0);
        graph.get(from).add(forward);
        graph.get(to).add(reverse);
    }

    private static long maximumFlow(List<List<FlowEdge>> graph, int source, int sink) {
        long flow = 0;
        int[] levels = new int[graph.size()];
        while (buildFlowLevels(graph, source, sink, levels)) {
            int[] nextEdges = new int[graph.size()];
            long pushed;
            while ((pushed = pushFlow(graph, source, sink, Long.MAX_VALUE, levels, nextEdges)) > 0) {
                flow += pushed;
            }
        }
        return flow;
    }

    private static boolean buildFlowLevels(List<List<FlowEdge>> graph, int source, int sink, int[] levels) {
        Arrays.fill(levels, -1);
        Queue<Integer> queue = new ArrayDeque<>();
        levels[source] = 0;
        queue.add(source);
        while (!queue.isEmpty()) {
            int node = queue.remove();
            for (FlowEdge edge : graph.get(node)) {
                if (edge.capacity > 0 && levels[edge.to] < 0) {
                    levels[edge.to] = levels[node] + 1;
                    queue.add(edge.to);
                }
            }
        }
        return levels[sink] >= 0;
    }

    private static long pushFlow(
            List<List<FlowEdge>> graph,
            int node,
            int sink,
            long available,
            int[] levels,
            int[] nextEdges
    ) {
        if (node == sink) return available;
        List<FlowEdge> edges = graph.get(node);
        while (nextEdges[node] < edges.size()) {
            FlowEdge edge = edges.get(nextEdges[node]);
            if (edge.capacity > 0 && levels[edge.to] == levels[node] + 1) {
                long pushed = pushFlow(graph, edge.to, sink, Math.min(available, edge.capacity), levels, nextEdges);
                if (pushed > 0) {
                    edge.capacity -= pushed;
                    graph.get(edge.to).get(edge.reverseIndex).capacity += pushed;
                    return pushed;
                }
            }
            nextEdges[node]++;
        }
        return 0;
    }

    public static boolean supportsRecipeContract(Recipe<?> recipe) {
        if (recipe instanceof AbstractCookingRecipe cookingRecipe && cookingRecipe.getCookingTime() <= 0) {
            return false;
        }
        if (recipe instanceof ShapedRecipe shapedRecipe
                && (shapedRecipe.getWidth() > 3 || shapedRecipe.getHeight() > 3)) {
            return false;
        }
        RecipeType<?> type = recipe.getType();
        Class<?> recipeClass = recipe.getClass();
        boolean staticContract = recipeClass == AxeTransformationRecipe.class
                || (type == RecipeType.CRAFTING
                ? recipeClass == ShapedRecipe.class || recipeClass == ShapelessRecipe.class
                : type == RecipeType.SMELTING
                        ? recipeClass == SmeltingRecipe.class
                        : type == RecipeType.BLASTING
                                ? recipeClass == BlastingRecipe.class
                                : type == RecipeType.SMOKING
                                        ? recipeClass == SmokingRecipe.class
                                        : type == RecipeType.CAMPFIRE_COOKING
                                                ? recipeClass == CampfireCookingRecipe.class
                                                : type == RecipeType.STONECUTTING
                                                        ? recipeClass == StonecutterRecipe.class
                                                        : type == RecipeType.SMITHING
                                                                && recipeClass == SmithingTransformRecipe.class);
        if (!staticContract) return false;
        if (recipe.isSpecial() || recipe.isIncomplete()) return false;
        for (RecipeIngredient ingredient : recipeIngredients(recipe)) {
            if (!ingredient.isEmpty()) return true;
        }
        return false;
    }

    static List<RecipeIngredient> recipeIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof SmithingTransformRecipe smithing)) {
            return recipe.getIngredients().stream().map(RecipeIngredient::of).toList();
        }
        synchronized (SMITHING_INGREDIENT_CACHE) {
            return SMITHING_INGREDIENT_CACHE.computeIfAbsent(
                    recipe, ignored -> resolveSmithingIngredients(smithing));
        }
    }

    private static List<RecipeIngredient> resolveSmithingIngredients(SmithingRecipe smithing) {
        List<ItemStack> templates = new ArrayList<>();
        List<ItemStack> bases = new ArrayList<>();
        List<ItemStack> additions = new ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (stack.isEmpty()) continue;
            if (smithing.isTemplateIngredient(stack)) templates.add(stack.copyWithCount(1));
            if (smithing.isBaseIngredient(stack)) bases.add(stack.copyWithCount(1));
            if (smithing.isAdditionIngredient(stack)) additions.add(stack.copyWithCount(1));
        }
        Recipe<?> recipe = (Recipe<?>) smithing;
        return List.of(
                RecipeIngredient.smithing(
                        new SmithingIngredientIdentity(recipe, 0), smithing::isTemplateIngredient, templates),
                RecipeIngredient.smithing(
                        new SmithingIngredientIdentity(recipe, 1), smithing::isBaseIngredient, bases),
                RecipeIngredient.smithing(
                        new SmithingIngredientIdentity(recipe, 2), smithing::isAdditionIngredient, additions)
        );
    }

    private List<IngredientNeed> aggregateIngredients(List<RecipeIngredient> ingredients, long crafts) {
        List<IngredientNeed> needs = new ArrayList<>();
        for (RecipeIngredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            needs.add(new IngredientNeed(ingredient, crafts));
        }
        return needs;
    }

    private List<IngredientNeed> summarizeIngredients(List<RecipeIngredient> ingredients) {
        Map<RecipeIngredient, Integer> counts = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : ingredients) {
            if (!ingredient.isEmpty()) counts.merge(ingredient, 1, Integer::sum);
        }
        List<IngredientNeed> needs = new ArrayList<>(counts.size());
        for (Map.Entry<RecipeIngredient, Integer> entry : counts.entrySet()) {
            needs.add(new IngredientNeed(entry.getKey(), entry.getValue()));
        }
        return needs;
    }

    private void updatePreview(StorageCoreBlockEntity core, Player player) {
        if (core == null || core.getLevel() == null) {
            clearRecipePresentation();
            return;
        }
        RecipeHolder<?> holder = resolveCurrentRecipe(core.getLevel());
        if (holder == null) {
            clearRecipePresentation();
            return;
        }
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        CraftPreview preview = computeCraftPreviewFor(holder.value(), core, sources);
        applyPreviewData(preview);
        syncRecipePresentation(holder, preview, sources, core, true);
        refreshEnergyAmounts(core);
    }

    private void applyPreviewData(CraftPreview preview) {
        craftableCount = preview.craftable();
        Arrays.fill(ingredientAvailable, 0);
        processRequired = 0;
        fuelRequired = 0;
        for (EnergyPreview energy : preview.energies()) {
            if (energy.type() == EnergyType.FURNACE_FUEL) {
                fuelRequired = energy.required();
            } else {
                processRequired = energy.required();
            }
        }
    }

    @Override
    protected void onObservedStorageChanged(StorageCoreBlockEntity core) {
        updatePreview(core, playerInventory.player);
    }

    @Override
    protected void onObservedEnergyChanged(StorageCoreBlockEntity core) {
        refreshEnergyAmounts(core);
        if (page == CraftingTerminalPage.CRAFTABLE) refreshDisplayItems(core);
        updatePreview(core, playerInventory.player);
    }

    @Override
    public void broadcastChanges() {
        if (playerInventory != null && !playerInventory.player.level().isClientSide()) {
            int fingerprint = playerInventoryFingerprint();
            boolean playerInventoryChanged = fingerprint != lastPlayerInventoryFingerprint;
            lastPlayerInventoryFingerprint = fingerprint;

            StorageCoreBlockEntity core = getCore(playerInventory.player.level());
            boolean topologyChanged = core != null && core.getTopologyRevision() != lastTopologyRevision;
            if (topologyChanged) lastTopologyRevision = core.getTopologyRevision();
            boolean machinesChanged = core != null && core.getMachineRevision() != lastMachineRevision;
            if (machinesChanged) lastMachineRevision = core.getMachineRevision();

            if (core != null && (topologyChanged || machinesChanged
                    || playerInventoryChanged && usePlayerInventory)) {
                if (machinesChanged) refreshEnergyAmounts(core);
                if (topologyChanged || machinesChanged || page == CraftingTerminalPage.CRAFTABLE) {
                    refreshDisplayItems(core);
                }
                updatePreview(core, playerInventory.player);
            }
        }
        super.broadcastChanges();
    }

    private int playerInventoryFingerprint() {
        if (playerInventory == null) return 0;
        int fingerprint = 1;
        for (int slot = 0; slot < PLAYER_INVENTORY_SLOTS; slot++) {
            ItemStack stack = playerInventory.getItem(slot);
            fingerprint = 31 * fingerprint + ItemStack.hashItemAndComponents(stack);
            fingerprint = 31 * fingerprint + stack.getCount();
        }
        return fingerprint;
    }

    private void refreshEnergyAmounts(StorageCoreBlockEntity core) {
        for (EnergyType type : EnergyType.values()) {
            energyAmounts[type.ordinal()] = core.getEnergy(type);
        }
        axeEnergyAmount = core.getAxeEnergy();
        infiniteAxeEnergy = core.hasInfiniteAxeEnergy();
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!player.level().isClientSide()) {
            if (buttonId == STORAGE_PAGE_BUTTON) return switchPage(player, CraftingTerminalPage.STORAGE);
            if (buttonId == CRAFTABLE_PAGE_BUTTON) return switchPage(player, CraftingTerminalPage.CRAFTABLE);
            if (buttonId == FUEL_PAGE_BUTTON) return switchPage(player, CraftingTerminalPage.FUEL);
            if (buttonId == AUTO_FUEL_TARGET_BUTTON) {
                if (page != CraftingTerminalPage.FUEL) return false;
                selectedFuelTarget = null;
                return true;
            }
            if (buttonId >= FUEL_TARGET_BUTTON_BASE
                    && buttonId < FUEL_TARGET_BUTTON_BASE + FUEL_TARGET_BUTTON_COUNT) {
                if (page != CraftingTerminalPage.FUEL) return false;
                selectedFuelTarget = FUEL_TARGETS.get(buttonId - FUEL_TARGET_BUTTON_BASE);
                return true;
            }
            if (buttonId == OUTPUT_DESTINATION_BUTTON) {
                if (!page.isItemPage()) return false;
                outputDestination = outputDestination.next();
                return true;
            }
            if (page == CraftingTerminalPage.FUEL) return false;
            StorageCoreBlockEntity core = getCore(player.level());
            switch (buttonId) {
                case 0, 1,
                     SORT_ORDER_BUTTON,
                     NEXT_SORT_MODE_BUTTON,
                     NEXT_SEARCH_MODE_BUTTON,
                     PREVIOUS_SORT_MODE_BUTTON,
                     PREVIOUS_SEARCH_MODE_BUTTON -> {
                    if (core != null) {
                        return super.clickMenuButton(player, buttonId);
                    }
                    return true;
                }
                case 2 -> craftItem(1, player);
                case 3 -> craftItem(8, player);
                case 4 -> craftItem(64, player);
                case MAX_CRAFT_BUTTON -> craftMaximum(player);
                case 7 -> toggleUsePlayerInventory();
                case 8 -> prevRecipe();
                case 9 -> nextRecipe();
                default -> { return false; }
            }
            if (core != null && buttonId >= 7 && buttonId <= 9) {
                refreshDisplayItems(core);
                updatePreview(core, player);
            }
        }
        return true;
    }

    private boolean switchPage(Player player, CraftingTerminalPage nextPage) {
        if (page == nextPage) return true;
        if (page == CraftingTerminalPage.FUEL) returnTransientInputs(player);
        page = nextPage;
        scrollOffset = 0;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core != null && page.isItemPage()) {
            refreshDisplayItems(core);
            updatePreview(core, player);
        }
        return true;
    }

    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide()) returnTransientInputs(player);
        super.removed(player);
    }

    private void returnTransientInputs(Player player) {
        returnTransientInput(player, fuelContainer);
        returnTransientInput(player, axeInputContainer);
    }

    private static void returnTransientInput(Player player, SimpleContainer container) {
        if (container == null) return;
        ItemStack leftover = container.removeItemNoUpdate(0);
        if (leftover.isEmpty()) return;
        if (!player.isAlive() || player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && serverPlayer.hasDisconnected()) {
            player.drop(leftover, false);
        } else {
            player.getInventory().placeItemBackInInventory(leftover);
        }
    }

    @Override
    public void refreshDisplayItems(StorageCoreBlockEntity core) {
        refreshDisplayItemsFiltered(core, currentFilter);
    }

    @Override
    public void refreshDisplayItemsFiltered(StorageCoreBlockEntity core, String filter) {
        this.currentFilter = filter != null ? filter : "";
        displayInventory.clearContent();
        if (core == null) {
            totalItemTypes = 0;
            return;
        }
        List<ItemStack> displayStacks;
        if (page == CraftingTerminalPage.CRAFTABLE) {
            Player player = playerInventory != null ? playerInventory.player : null;
            displayStacks = buildCraftableDisplayStacks(core, player);
            sortCraftableDisplayStacks(displayStacks);
        } else {
            displayStacks = new ArrayList<>(core.getDisplayStacks(
                    currentFilter, getSortMode(), getSortOrder()));
        }

        totalItemTypes = displayStacks.size();
        displayTypeCount = core.getTypeCount();
        displayMaxTypes = core.getTotalTypeSlots();
        int vRows = getVisibleRows();
        int maxOffset = Math.max(0, totalItemTypes - vRows * DISPLAY_COLS);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            int idx = scrollOffset + i;
            if (idx < displayStacks.size() && i < vRows * DISPLAY_COLS) {
                displayInventory.setItem(i, displayStacks.get(idx).copy());
            }
        }

        if (selectedKey != null) {
            if (selectedRecipeId != null) {
                if (!isSelectedRecipeCurrent(core)) clearSelection();
            } else if (page == CraftingTerminalPage.CRAFTABLE) {
                Player player = playerInventory != null ? playerInventory.player : null;
                if (!isCraftableOutput(core, selectedKey.toStack(1), player)) clearSelection();
            } else if (core.getItemCount(selectedKey) <= 0) {
                clearSelection();
            }
        }
    }

    private boolean isSelectedRecipeCurrent(StorageCoreBlockEntity core) {
        if (selectedRecipeId == null || selectedKey == null) return false;
        Level level = core.getLevel();
        if (level == null) return false;
        RecipeHolder<?> holder = resolveRecipeById(level, core, selectedRecipeId);
        if (holder == null || !supportsRecipeContract(holder.value())
                || !CraftingStationTable.isAvailable(core, holder.value())) return false;
        ItemStack result = holder.value().getResultItem(level.registryAccess());
        return !result.isEmpty()
                && ItemStack.isSameItemSameComponents(result, selectedKey.toStack(1));
    }

    private List<ItemStack> buildCraftableDisplayStacks(StorageCoreBlockEntity core, Player player) {
        Level level = core.getLevel();
        if (level == null) return new ArrayList<>();
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        List<ItemStack> availableStacks = sources.stream().map(IngredientSource::stack).toList();
        Map<ItemKey, Long> craftableAmounts = new HashMap<>();
        for (ResourceLocation recipeId : craftableRecipeCatalog.getCandidateRecipeIds(
                level.getRecipeManager(), availableStacks)) {
            RecipeHolder<?> holder = level.getRecipeManager().byKey(recipeId).orElse(null);
            if (holder == null || !supportsRecipeContract(holder.value())
                    || !CraftingStationTable.isAvailable(core, holder.value())) continue;
            CraftPreview preview = computeCraftPreviewFor(holder.value(), core, sources);
            if (preview.craftable() <= 0) continue;
            ItemStack output = holder.value().getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            ItemKey key = ItemKey.of(output);
            if (!StorageCoreBlockEntity.matchesFilter(key, currentFilter, level)) continue;
            craftableAmounts.put(key, core.getItemCount(key));
        }
        for (RecipeHolder<?> holder : axeTransformationCatalog.recipes(level, core)) {
            CraftPreview preview = computeCraftPreviewFor(holder.value(), core, sources);
            if (preview.craftable() <= 0) continue;
            ItemStack output = holder.value().getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            ItemKey key = ItemKey.of(output);
            if (!StorageCoreBlockEntity.matchesFilter(key, currentFilter, level)) continue;
            craftableAmounts.put(key, core.getItemCount(key));
        }

        List<ItemStack> result = new ArrayList<>(craftableAmounts.size());
        for (Map.Entry<ItemKey, Long> entry : craftableAmounts.entrySet()) {
            result.add(TerminalDisplayStack.create(entry.getKey().toStack(1), entry.getValue()));
        }
        return result;
    }

    private void sortCraftableDisplayStacks(List<ItemStack> stacks) {
        stacks.sort(TerminalEntryComparator.forMode(getSortMode(), getSortOrder()));
    }

    private boolean isCraftableOutput(StorageCoreBlockEntity core, ItemStack output, Player player) {
        Level level = core.getLevel();
        if (level == null) return false;
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        for (RecipeHolder<?> holder : findRecipes(level, output)) {
            if (computeCraftPreviewFor(holder.value(), core, sources).craftable() > 0) return true;
        }
        return false;
    }

    private List<RecipeHolder<?>> findRecipes(Level level, ItemStack output) {
        output = TerminalDisplayStack.strip(output);
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        RecipeManager manager = level.getRecipeManager();
        StorageCoreBlockEntity core = getCore(level);
        if (core == null) return recipes;
        for (RecipeType<?> type : SUPPORTED_RECIPE_TYPES) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) manager.getAllRecipesFor((RecipeType) type);
            for (RecipeHolder<?> holder : holders) {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (supportsRecipeContract(recipe)
                        && CraftingStationTable.isAvailable(core, recipe)
                        && ItemStack.isSameItemSameComponents(result, output)) {
                    recipes.add(holder);
                }
            }
        }
        for (RecipeHolder<?> holder : axeTransformationCatalog.recipes(level, core)) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (ItemStack.isSameItemSameComponents(result, output)) recipes.add(holder);
        }

        recipes.sort(Comparator.comparingInt(h -> getRecipeSortOrder(h.value())));
        return recipes;
    }
}
