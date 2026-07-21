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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

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

public class CraftingTerminalMenu extends StorageTerminalMenu {

    public record IngredientPreview(ItemStack stack, long available, long required) {}
    public record EnergyPreview(EnergyType type, long available, long required) {}
    public record CraftPreview(
            int craftable,
            List<ItemStack> missing,
            List<IngredientPreview> ingredients,
            List<EnergyPreview> energies
    ) {}
    private record IngredientNeed(RecipeAdapterMatch.Input ingredient, long count) {}
    private record IngredientSource(ItemKey key, int playerSlot, ItemStack stack, long amount) {}
    private record PlayerReservation(ItemKey key, int count) {}
    private record IngredientPlan(
            Map<ItemKey, Long> coreReservations,
            Map<Integer, PlayerReservation> playerReservations,
            List<Map<ItemKey, Long>> allocations
    ) {}
    private record ToolUsePlan(ResourceLocation descriptorId, long amount) {}
    private record DeliveryPlan(
            List<ItemStack> playerInventory,
            ItemStack carried,
            Map<StorageResourceKey, Long> coreDeltas,
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
    public static final int MACHINE_SLOT_COUNT = MachineDescriptorApi.MAX_DESCRIPTORS;
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
    static final int RESET_OUTPUT_DESTINATION_BUTTON = 24;
    static final int RESET_PLAYER_INVENTORY_BUTTON = 25;
    private static final List<EnergyType> FUEL_TARGETS = List.of(
            EnergyType.FURNACE_FUEL,
            EnergyType.BLAZE_FUEL);
    private static final int FUEL_TARGET_BUTTON_COUNT = FUEL_TARGETS.size();
    private static final int ENERGY_DATA_START = 8;
    private static final int LIVE_ENERGY_DATA_SLOTS = EnergyType.values().length * 4;
    private static final int RETIRED_ENERGY_DATA_SLOTS = 4;
    private static final int ENERGY_DATA_SLOTS = LIVE_ENERGY_DATA_SLOTS + RETIRED_ENERGY_DATA_SLOTS;
    private static final int INGREDIENT_AVAILABLE_DATA_START = ENERGY_DATA_START + ENERGY_DATA_SLOTS;
    private static final int PROCESS_REQUIRED_DATA_START = INGREDIENT_AVAILABLE_DATA_START + MAX_INGREDIENTS * 4;
    private static final int FUEL_REQUIRED_DATA_START = PROCESS_REQUIRED_DATA_START + 4;
    private static final int AXE_ENERGY_DATA_START = FUEL_REQUIRED_DATA_START + 4;
    private static final int AXE_INFINITE_DATA_SLOT = AXE_ENERGY_DATA_START + 4;
    private static final int CRAFTING_DATA_SLOTS = AXE_INFINITE_DATA_SLOT + 1;
    private static final EnergyType[] ENERGY_SYNC_ORDER = EnergyType.values();

    private ItemKey selectedKey = null;
    private ResourceLocation selectedRecipeId;
    private SimpleContainer selectionContainer;
    private SimpleContainer fuelContainer;
    private SimpleContainer consumableInputContainer;
    private Container machineContainer;
    private final List<MachineDescriptor> descriptorSnapshot;
    private final Map<ResourceLocation, MachineDescriptorStatePacket.State> descriptorStates = new HashMap<>();
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
    private boolean processingConsumableInput;
    private int lastPlayerInventoryFingerprint;
    private long lastTopologyRevision;
    private long lastMachineRevision;

    public CraftingTerminalMenu(int containerId, Inventory playerInv, StorageCoreBlockEntity core) {
        this(containerId, playerInv, core, core.getBlockPos(), false);
    }

    public CraftingTerminalMenu(int containerId, Inventory playerInv, StorageCoreBlockEntity core, BlockPos accessPos, boolean remoteAccess) {
        super(MagicStorage.CRAFTING_TERMINAL_MENU.get(), containerId, playerInv, core, accessPos, remoteAccess, true);
        this.playerInventory = playerInv;
        this.descriptorSnapshot = MachineEnergyTable.entries();
        initializeStorageMenu(playerInv, core);
        this.lastPlayerInventoryFingerprint = playerInventoryFingerprint();
        this.lastTopologyRevision = core.getTopologyRevision();
        this.lastMachineRevision = core.getMachineRevision();
        refreshEnergyAmounts(core);
        addContainerData();
        refreshDisplayItems(core);
    }

    public CraftingTerminalMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        super(MagicStorage.CRAFTING_TERMINAL_MENU.get(), containerId, playerInv, buf, true);
        this.playerInventory = playerInv;
        this.descriptorSnapshot = MachineEnergyTable.readSnapshot(buf);
        initializeStorageMenu(playerInv, null);
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
            if (relative >= LIVE_ENERGY_DATA_SLOTS) return 0;
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
            if (relative >= LIVE_ENERGY_DATA_SLOTS) return;
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
        consumableInputContainer = new SimpleContainer(MACHINE_SLOT_COUNT) {
            @Override
            public void setChanged() {
                super.setChanged();
                onConsumableInputsChanged();
            }
        };
        for (int machineSlot = 0; machineSlot < MACHINE_SLOT_COUNT; machineSlot++) {
            int mappedSlot = machineSlot;
            MachineDescriptor descriptor = descriptorAt(mappedSlot);
            boolean consumable = descriptor != null
                    && descriptor.category() == MachineEnergyTable.Category.CONSUMABLE;
            Container slotContainer = consumable ? consumableInputContainer : machineContainer;
            int containerSlot = mappedSlot;
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
                    return descriptorAt(mappedSlot) != null && page == CraftingTerminalPage.FUEL;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    if (page != CraftingTerminalPage.FUEL) return false;
                    StorageCoreBlockEntity currentCore = getCore(playerInv.player.level());
                    MachineDescriptor entry = descriptorAt(mappedSlot);
                    if (currentCore == null || currentCore.isConflicted()
                            || entry == null || !entry.accepts(stack)) return false;
                    if (hasRetainedLegacyInput()) return false;
                    return entry.category() == MachineEnergyTable.Category.CONSUMABLE
                            ? currentCore.canAddDescriptorConsumable(entry.id(), stack)
                            : entry.maxInstalledCount() > 0;
                }

                @Override
                public int getMaxStackSize(ItemStack stack) {
                    MachineDescriptor entry = descriptorAt(mappedSlot);
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
                missing.add(TerminalDisplayStack.create(
                        ingredient.stack(), ingredient.required()));
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
            long required = TerminalDisplayStack.amount(stack);
            result.add(new IngredientPreview(
                    TerminalDisplayStack.strip(stack).copyWithCount(1),
                    ingredientAvailable[ingredient],
                    required));
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
                return RecipePresentation.empty();
            }
            resources.add(RecipePresentation.Resource.item(
                    TerminalDisplayStack.strip(stack).copyWithCount(1),
                    ingredientAvailable[item],
                    TerminalDisplayStack.amount(stack)));
        }
        for (EnergyPreview energy : getEnergyPreview()) {
            resources.add(RecipePresentation.Resource.energy(
                    energy.type(), energy.available(), energy.required()));
        }
        if (metadata.toolRequired() > 0) {
            ItemStack tool = selectionContainer.getItem(PRESENTATION_TOOL_SLOT);
            if (tool.isEmpty()) {
                return RecipePresentation.empty();
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
        return getDescriptorAmount(MachineEnergyTable.AXE_ID);
    }

    public boolean hasInfiniteAxeEnergy() {
        return hasInfiniteDescriptor(MachineEnergyTable.AXE_ID);
    }

    public List<MachineDescriptor> getMachineDescriptors() {
        return descriptorSnapshot;
    }

    private MachineDescriptor descriptorAt(int slot) {
        return slot >= 0 && slot < descriptorSnapshot.size() ? descriptorSnapshot.get(slot) : null;
    }

    private int findDescriptorSlot(ItemStack stack) {
        for (int slot = 0; slot < descriptorSnapshot.size(); slot++) {
            if (descriptorSnapshot.get(slot).accepts(stack)) return slot;
        }
        return -1;
    }

    public long getDescriptorAmount(ResourceLocation descriptorId) {
        MachineDescriptorStatePacket.State state = descriptorStates.get(descriptorId);
        if (state != null) return state.amount();
        return descriptorId.equals(MachineEnergyTable.AXE_ID) ? axeEnergyAmount : 0;
    }

    public boolean hasInfiniteDescriptor(ResourceLocation descriptorId) {
        MachineDescriptorStatePacket.State state = descriptorStates.get(descriptorId);
        if (state != null) return state.infinite();
        return descriptorId.equals(MachineEnergyTable.AXE_ID) && infiniteAxeEnergy;
    }

    void applyDescriptorStates(List<MachineDescriptorStatePacket.State> states) {
        descriptorStates.clear();
        for (MachineDescriptorStatePacket.State state : states) {
            if (descriptorSnapshot.stream().anyMatch(descriptor -> descriptor.id().equals(state.descriptorId()))) {
                descriptorStates.put(state.descriptorId(), state);
            }
        }
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

    private void onConsumableInputsChanged() {
        if (processingConsumableInput || consumableInputContainer == null || playerInventory == null
                || page != CraftingTerminalPage.FUEL) return;
        Player player = playerInventory.player;
        if (player.level().isClientSide()) return;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null) return;
        boolean changed = false;
        processingConsumableInput = true;
        try {
            for (int slot = 0; slot < descriptorSnapshot.size(); slot++) {
                MachineDescriptor descriptor = descriptorAt(slot);
                if (descriptor == null || descriptor.category() != MachineEnergyTable.Category.CONSUMABLE) continue;
                ItemStack stack = consumableInputContainer.getItem(slot);
                if (stack.isEmpty() || !core.addDescriptorConsumable(descriptor.id(), stack)) continue;
                consumableInputContainer.removeItemNoUpdate(slot);
                changed = true;
            }
        } finally {
            processingConsumableInput = false;
        }
        if (changed) refreshEnergyAmounts(core);
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
                int machineSlot = findDescriptorSlot(slot.getItem());
                if (machineSlot >= 0) {
                    if (core == null || core.isConflicted()) return ItemStack.EMPTY;
                    ItemStack stack = slot.getItem();
                    MachineDescriptor entry = descriptorAt(machineSlot);
                    if (entry != null && entry.category() == MachineEnergyTable.Category.CONSUMABLE) {
                        if (!core.addDescriptorConsumable(entry.id(), stack)) return ItemStack.EMPTY;
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

        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        ItemStack requestedOutput = TerminalDisplayStack.strip(displayStack);
        RecipeAdapterMatch match = resolveAvailableRecipeVariantById(
                level, core, recipeId, requestedOutput, sources);
        if (match == null) return false;
        ItemStack result = match.presentationOutput(List.of(), level);
        if (!ItemStack.isSameItemSameComponents(result, requestedOutput)) return false;
        return selectRecipeById(level, recipeId, requestedOutput, player, core);
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
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        ItemStack requestedOutput = selectedRecipeId != null
                && selectedRecipeId.equals(recipeId)
                && selectedKey != null
                ? selectedKey.toStack(1)
                : ItemStack.EMPTY;
        RecipeAdapterMatch match = resolveAvailableRecipeVariantById(
                level, core, recipeId, requestedOutput, sources);
        if (match == null) return false;
        ItemStack result = match.presentationOutput(List.of(), level);
        if (result.isEmpty()) return false;
        if (destination == CraftingDestination.NONE) {
            return selectRecipeById(level, recipeId, result, player, core);
        }

        CraftPreview preview = computeCraftPreviewFor(match, core, player);
        int maximumCrafts = Math.min(amount, preview.craftable());
        DeliveryTarget deliveryTarget = DeliveryTarget.from(destination);
        int crafts = 0;
        IngredientPlan ingredientPlan = null;
        DeliveryPlan deliveryPlan = null;
        for (int candidate = maximumCrafts; candidate > 0; candidate--) {
            CraftPlan candidatePlan = planCraft(
                    core, match, candidate, deliveryTarget, player, sources);
            if (candidatePlan != null) {
                crafts = candidate;
                ingredientPlan = candidatePlan.ingredients();
                deliveryPlan = candidatePlan.delivery();
                break;
            }
        }
        if (crafts <= 0 || ingredientPlan == null || deliveryPlan == null) return false;
        EnergyCost energyCost = match.cost().energyCost().orElse(null);
        if (!commitCraft(
                core, ingredientPlan, deliveryPlan, player, match, energyCost, crafts)) return false;
        selectRecipeById(level, recipeId, result, player, core);
        refreshDisplayItems(core);
        updatePreview(core, player);
        broadcastChanges();
        return true;
    }

    private boolean selectRecipeById(
            Level level,
            ResourceLocation recipeId,
            ItemStack requestedOutput,
            Player player,
            StorageCoreBlockEntity core
    ) {
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        RecipeAdapterMatch match = resolveAvailableRecipeVariantById(
                level, core, recipeId, requestedOutput, sources);
        if (match == null) return false;
        ItemStack result = match.presentationOutput(List.of(), level);
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
        List<IngredientSource> sources = snapshotIngredientSources(
                core, playerInventory == null ? null : playerInventory.player);

        for (RecipeType<?> type : BuiltInRecipeAdapters.discoveryTypes()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) manager.getAllRecipesFor((RecipeType) type);
            for (RecipeHolder<?> holder : holders) {
                RecipeAdapterMatch match = resolveAvailableRecipeLookupVariant(
                        holder, core, output, sources, level);
                if (match != null) {
                    currentRecipes.add(holder);
                }
            }
        }
        for (RecipeHolder<?> holder : axeTransformationCatalog.recipes(level, core)) {
            RecipeAdapterMatch match = classifyAvailable(holder, core);
            if (match == null) continue;
            ItemStack result = match.presentationOutput(List.of(), level);
            if (!result.isEmpty() && ItemStack.isSameItemSameComponents(result, output)) {
                currentRecipes.add(holder);
            }
        }

        currentRecipes.sort(Comparator
                .comparingInt(CraftingTerminalMenu::getRecipeSortOrder)
                .thenComparing(holder -> holder.id().toString()));
        syncRecipeMetadata();
    }

    private static int getRecipeSortOrder(RecipeHolder<?> holder) {
        return BuiltInRecipeAdapters.registry().classify(holder)
                .map(match -> match.adapter().priority())
                .orElse(99);
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
            RecipeHolder<?> cachedHolder = currentRecipes.get(currentRecipeIndex);
            if (selectedRecipeId != null) selectedRecipeId = cachedHolder.id();
            if (!playerInventory.player.level().isClientSide()) {
                StorageCoreBlockEntity core = getCore(playerInventory.player.level());
                List<IngredientSource> sources = core == null
                        ? List.of()
                        : snapshotIngredientSources(core, playerInventory.player);
                ItemStack requestedOutput = selectedKey == null
                        ? ItemStack.EMPTY : selectedKey.toStack(1);
                RecipeAdapterMatch match = core == null ? null : resolveAvailableRecipeVariantById(
                        playerInventory.player.level(), core, cachedHolder.id(),
                        requestedOutput, sources);
                if (match == null) {
                    clearRecipePresentation();
                } else {
                    currentRecipes.set(currentRecipeIndex, match.holder());
                    currentRecipeTypeOrder = match.adapter().priority();
                    applyPreviewData(emptyCraftPreview());
                    syncRecipePresentation(
                            match,
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

        RecipeAdapterMatch match = resolveCurrentRecipeMatch(player.level());
        if (match == null) return false;
        EnergyCost energyCost = match.cost().energyCost().orElse(null);
        if (!hasEnergyForCrafts(core, energyCost, count)) return false;
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        CraftPlan plan = planCraft(
                core, match, count, DeliveryTarget.from(outputDestination), player, sources);
        if (plan == null || !commitCraft(
                core,
                plan.ingredients(),
                plan.delivery(),
                player,
                match,
                energyCost,
                plan.crafts())) return false;

        refreshDisplayItems(core);
        updatePreview(core, player);
        broadcastChanges();
        return true;
    }

    private void craftMaximum(Player player) {
        if (!page.isItemPage() || player.level().isClientSide()) return;
        StorageCoreBlockEntity core = getCore(player.level());
        if (core == null || core.isConflicted()) return;
        RecipeAdapterMatch match = resolveCurrentRecipeMatch(player.level());
        if (match == null) return;
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        long resourceMaximum = maximumResourceCrafts(match, core, sources);
        CraftPlan plan = largestDeliverablePlan(
                core, match, resourceMaximum, DeliveryTarget.from(outputDestination), player, sources);
        EnergyCost energyCost = match.cost().energyCost().orElse(null);
        if (plan == null || !commitCraft(
                core,
                plan.ingredients(),
                plan.delivery(),
                player,
                match,
                energyCost,
                plan.crafts())) return;
        refreshDisplayItems(core);
        updatePreview(core, player);
        broadcastChanges();
    }

    private CraftPlan largestDeliverablePlan(
            StorageCoreBlockEntity core,
            RecipeAdapterMatch match,
            long maximum,
            DeliveryTarget destination,
            Player player,
            List<IngredientSource> sources
    ) {
        if (maximum <= 0) return null;
        CraftPlan maximumPlan = planCraft(core, match, maximum, destination, player, sources);
        if (maximumPlan != null) return maximumPlan;

        long low = 0;
        long high = maximum - 1;
        CraftPlan best = null;
        while (low < high) {
            long distance = high - low;
            long candidate = low + distance / 2 + distance % 2;
            CraftPlan candidatePlan = planCraft(core, match, candidate, destination, player, sources);
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
                : planCraft(core, match, low, destination, player, sources);
    }

    private CraftPlan planCraft(
            StorageCoreBlockEntity core,
            RecipeAdapterMatch match,
            long crafts,
            DeliveryTarget destination,
            Player player,
            List<IngredientSource> sources
    ) {
        EnergyCost energyCost = match.cost().energyCost().orElse(null);
        if (crafts <= 0 || !hasEnergyForCrafts(core, energyCost, crafts)
                || !hasToolForCrafts(core, match.cost().toolCost().orElse(null), crafts)) return null;
        TypedRecipePlan typedPlan = match.typedRecipePlan().orElse(null);
        if (typedPlan != null && !hasTypedInputs(core, typedPlan, crafts)) return null;
        IngredientPlan ingredients = typedPlan == null
                ? planIngredients(match.orderedInputs(), crafts, sources)
                : new IngredientPlan(Map.of(), Map.of(), List.of());
        if (ingredients == null) return null;
        DeliveryPlan delivery = planDelivery(core, ingredients, match, crafts, destination, player);
        return delivery == null ? null : new CraftPlan(crafts, ingredients, delivery);
    }

    private long maximumResourceCrafts(
            RecipeAdapterMatch match,
            StorageCoreBlockEntity core,
            List<IngredientSource> sources
    ) {
        TypedRecipePlan typedPlan = match.typedRecipePlan().orElse(null);
        if (typedPlan != null) {
            long high = maximumTypedCrafts(core, typedPlan);
            RecipeAdapterMatch.ToolCost toolCost = match.cost().toolCost().orElse(null);
            if (toolCost != null) {
                long available = core.hasInfiniteDescriptor(toolCost.descriptorId())
                        ? Long.MAX_VALUE
                        : core.getDescriptorAmount(toolCost.descriptorId());
                high = Math.min(high, available / toolCost.amountPerCraft());
            }
            EnergyCost cost = match.cost().energyCost().orElse(null);
            if (cost != null) {
                if (cost.processAmount() > 0) {
                    high = Math.min(high, core.getEnergy(cost.processType()) / cost.processAmount());
                }
                if (cost.fuelAmount() > 0) {
                    high = Math.min(high, core.getEnergy(cost.fuelType()) / cost.fuelAmount());
                }
            }
            return high;
        }
        List<RecipeAdapterMatch.Input> ingredients = match.orderedInputs();
        long totalAvailable = 0;
        for (IngredientSource source : sources) {
            totalAvailable = saturatingAdd(totalAvailable, source.amount());
        }
        long ingredientsPerCraft = ingredients.stream()
                .filter(ingredient -> !ingredient.isEmpty())
                .mapToLong(RecipeAdapterMatch.Input::multiplicity)
                .sum();
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
        RecipeAdapterMatch.ToolCost toolCost = match.cost().toolCost().orElse(null);
        if (toolCost != null) {
            long available = core.hasInfiniteDescriptor(toolCost.descriptorId())
                    ? Long.MAX_VALUE
                    : core.getDescriptorAmount(toolCost.descriptorId());
            high = Math.min(high, available / toolCost.amountPerCraft());
        }
        EnergyCost cost = match.cost().energyCost().orElse(null);
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

    private static long maximumTypedCrafts(
            StorageCoreBlockEntity core,
            TypedRecipePlan plan
    ) {
        long maximum = Long.MAX_VALUE;
        for (TypedRecipeInput input : plan.inputs()) {
            long available = core.getResourceAmount(input.key());
            if (input.role() == TypedRecipeInput.Role.CONSUME) {
                maximum = Math.min(maximum, available / input.amount());
            } else if (available < input.amount()) {
                return 0;
            }
        }
        return maximum;
    }

    private static boolean hasTypedInputs(
            StorageCoreBlockEntity core,
            TypedRecipePlan plan,
            long crafts
    ) {
        if (crafts <= 0) return false;
        for (TypedRecipeInput input : plan.inputs()) {
            long required;
            try {
                required = input.role() == TypedRecipeInput.Role.CONSUME
                        ? Math.multiplyExact(input.amount(), crafts)
                        : input.amount();
            } catch (ArithmeticException exception) {
                return false;
            }
            if (core.getResourceAmount(input.key()) < required) return false;
        }
        return true;
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

    private RecipeAdapterMatch resolveCurrentRecipeMatch(Level level) {
        if (currentRecipes.isEmpty() || currentRecipeIndex >= currentRecipes.size()) return null;
        RecipeHolder<?> cachedHolder = currentRecipes.get(currentRecipeIndex);
        StorageCoreBlockEntity core = getCore(level);
        if (core == null) return null;
        List<IngredientSource> sources = snapshotIngredientSources(
                core, playerInventory == null ? null : playerInventory.player);
        ItemStack requestedOutput = selectedKey == null
                ? ItemStack.EMPTY : selectedKey.toStack(1);
        RecipeAdapterMatch match = resolveAvailableRecipeVariantById(
                level, core, cachedHolder.id(), requestedOutput, sources);
        if (match == null) return null;

        ItemStack result = match.presentationOutput(List.of(), level);
        if (result.isEmpty()) return null;
        ItemStack selectedOutput = selectedKey != null
                ? selectedKey.toStack(1)
                : result;
        if (!ItemStack.isSameItemSameComponents(result, selectedOutput)) return null;

        currentRecipes.set(currentRecipeIndex, match.holder());
        return match;
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
        RecipeAdapterMatch match = resolveCurrentRecipeMatch(level);
        if (match == null) return emptyCraftPreview();
        return computeCraftPreviewFor(match, core, player);
    }

    private static CraftPreview emptyCraftPreview() {
        return new CraftPreview(0, List.of(), List.of(), List.of());
    }

    private CraftPreview computeCraftPreviewFor(
            RecipeAdapterMatch match,
            StorageCoreBlockEntity core,
            Player player
    ) {
        return computeCraftPreviewFor(match, core, snapshotIngredientSources(core, player));
    }

    private CraftPreview computeCraftPreviewFor(
            RecipeAdapterMatch match,
            StorageCoreBlockEntity core,
            List<IngredientSource> sources
    ) {
        return computeCraftPreviewFor(match, core, sources, PREVIEW_CAP);
    }

    private CraftPreview computeCraftPreviewFor(
            RecipeAdapterMatch match,
            StorageCoreBlockEntity core,
            List<IngredientSource> sources,
            int craftLimit
    ) {
        if (core.isConflicted() || !match.validatesSimulation(match.holder())
                || !isStationAvailable(core, match)) {
            return emptyCraftPreview();
        }
        long max = craftLimit;
        List<ItemStack> missing = new ArrayList<>();
        List<IngredientPreview> ingredientPreviews = new ArrayList<>();
        List<RecipeAdapterMatch.Input> ingredients = match.orderedInputs();
        TypedRecipePlan typedPlan = match.typedRecipePlan().orElse(null);
        if (typedPlan != null) {
            max = Math.min(max, maximumTypedCrafts(core, typedPlan));
            for (TypedRecipeInput input : typedPlan.inputs()) {
                long available = core.getResourceAmount(input.key());
                ItemStack representative = StorageResourceKinds.representative(
                        input.key(), core.getLevel().registryAccess());
                ingredientPreviews.add(new IngredientPreview(
                        representative, available, input.amount()));
                if (available < input.amount() && missing.size() < MAX_INGREDIENTS) {
                    missing.add(representative.copy());
                }
            }
        } else {
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
                            representative.copyWithCount(1), avail, need.count()));
                }
                if (avail < need.count()) {
                    List<ItemStack> items = need.ingredient().representatives();
                    if (!items.isEmpty() && missing.size() < MAX_INGREDIENTS) {
                        missing.add(items.getFirst().copy());
                    }
                }
            }
        }
        RecipeAdapterMatch.ToolCost toolCost = match.cost().toolCost().orElse(null);
        if (toolCost != null) {
            long available = core.hasInfiniteDescriptor(toolCost.descriptorId())
                    ? Long.MAX_VALUE
                    : core.getDescriptorAmount(toolCost.descriptorId());
            max = Math.min(max, available / toolCost.amountPerCraft());
        }

        EnergyCost cost = match.cost().energyCost().orElse(null);
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
        int craftable;
        if (typedPlan != null) {
            craftable = upperBound;
        } else {
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
            craftable = low;
        }
        if (craftable == 0 && upperBound > 0 && missing.isEmpty()) {
            for (RecipeAdapterMatch.Input ingredient : ingredients) {
                if (!ingredient.isEmpty() && !ingredient.representatives().isEmpty()) {
                    missing.add(ingredient.representatives().getFirst().copy());
                    break;
                }
            }
        }
        if (craftable > 0) missing.clear();
        return new CraftPreview(craftable, missing, ingredientPreviews, energyPreviews);
    }

    private static ItemStack ingredientRepresentative(
            RecipeAdapterMatch.Input ingredient,
            List<IngredientSource> sources
    ) {
        for (IngredientSource source : sources) {
            if (ingredient.test(source.stack())) return source.stack().copyWithCount(1);
        }
        List<ItemStack> displayItems = ingredient.representatives();
        if (!displayItems.isEmpty()) return displayItems.getFirst().copyWithCount(1);
        return ItemStack.EMPTY;
    }

    private void syncRecipePresentation(
            RecipeAdapterMatch match,
            CraftPreview preview,
            List<IngredientSource> sources,
            StorageCoreBlockEntity core,
            boolean includeResources
    ) {
        RecipeAdapterMatch.Presentation semantics = match.presentation();
        List<ItemStack> inputs = presentationInputs(match, sources, core.getLevel());
        ItemStack output = match.presentationOutput(inputs, core.getLevel());
        ItemStack station = presentationStation(match, core);
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
            TypedRecipePlan typedPlan = match.typedRecipePlan().orElse(null);
            List<IngredientNeed> needs = typedPlan == null
                    ? summarizeIngredients(match.orderedInputs()) : List.of();
            itemResourceCount = typedPlan == null ? needs.size() : preview.ingredients().size();
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
                        TerminalDisplayStack.create(resource.stack(), resource.required()));
                ingredientAvailable[item] = resource.available();
            }
        }

        long toolAvailable = 0;
        long toolRequired = 0;
        boolean toolInfinite = false;
        RecipeAdapterMatch.ToolCost toolCost = match.cost().toolCost().orElse(null);
        if (toolCost != null) {
            toolInfinite = core.hasInfiniteDescriptor(toolCost.descriptorId());
            toolAvailable = toolInfinite
                    ? Long.MAX_VALUE
                    : core.getDescriptorAmount(toolCost.descriptorId());
            if (toolAvailable <= 0) throw new IllegalStateException("Selected recipe has no tool resource");
            toolRequired = toolCost.amountPerCraft();
            MachineDescriptor toolDescriptor = MachineEnergyTable.get(toolCost.descriptorId());
            if (toolDescriptor == null) throw new IllegalStateException("Selected recipe tool is unavailable");
            selectionContainer.setItem(
                    PRESENTATION_TOOL_SLOT, toolDescriptor.representativeStack());
        }

        RecipePresentation.Metadata metadata = new RecipePresentation.Metadata(
                match.holder().id(),
                semantics.kind(),
                semantics.width(),
                semantics.height(),
                semantics.shapeless(),
                itemResourceCount,
                toolAvailable,
                toolRequired,
                toolInfinite);
        selectionContainer.setItem(
                PRESENTATION_METADATA_SLOT, RecipePresentation.metadataCarrier(metadata));
    }

    private void clearRecipePresentation() {
        if (selectionContainer != null) selectionContainer.clearContent();
        if (selectionContainer != null && selectedKey != null) {
            selectionContainer.setItem(PRESENTATION_OUTPUT_SLOT, selectedKey.toStack(1));
        }
        Arrays.fill(ingredientAvailable, 0);
        processRequired = 0;
        fuelRequired = 0;
        craftableCount = 0;
    }

    private static List<ItemStack> presentationInputs(
            RecipeAdapterMatch match,
            List<IngredientSource> sources,
            Level level
    ) {
        TypedRecipePlan typedPlan = match.typedRecipePlan().orElse(null);
        if (typedPlan != null) {
            List<ItemStack> inputs = new ArrayList<>(
                    Collections.nCopies(RecipePresentation.MAX_INPUTS, ItemStack.EMPTY));
            for (int input = 0; input < typedPlan.inputs().size(); input++) {
                inputs.set(input, StorageResourceKinds.representative(
                        typedPlan.inputs().get(input).key(), level.registryAccess()));
            }
            return List.copyOf(inputs);
        }
        List<RecipeAdapterMatch.Input> ingredients = match.orderedInputs();
        if (ingredients.size() > RecipePresentation.MAX_INPUTS) {
            throw new IllegalArgumentException("Recipe presentation has more than nine inputs");
        }
        List<ItemStack> inputs = new ArrayList<>(
                Collections.nCopies(RecipePresentation.MAX_INPUTS, ItemStack.EMPTY));
        for (int input = 0; input < ingredients.size(); input++) {
            RecipeAdapterMatch.Input ingredient = ingredients.get(input);
            if (!ingredient.isEmpty()) {
                inputs.set(input, ingredientRepresentative(ingredient, sources));
            }
        }
        return List.copyOf(inputs);
    }

    private static ItemStack presentationStation(
            RecipeAdapterMatch match,
            StorageCoreBlockEntity core
    ) {
        MachineDescriptor station = MachineEnergyTable.get(match.stationDescriptorId());
        if (station == null) throw new IllegalStateException("Recipe presentation has no station descriptor");
        if (station.category() == MachineEnergyTable.Category.CONSUMABLE) {
            if (!isStationAvailable(core, match)) {
                throw new IllegalStateException("Recipe presentation tool station is unavailable");
            }
            return station.representativeStack();
        }
        int stationSlot = MachineEnergyTable.findSlot(match.stationDescriptorId());
        if (stationSlot < 0) throw new IllegalStateException("Recipe presentation station has no slot");
        ItemStack installed = core.getMachineContainer().getItem(stationSlot);
        if (!station.accepts(installed)) {
            throw new IllegalStateException("Recipe presentation station is not installed");
        }
        return installed.copyWithCount(1);
    }

    private IngredientPlan planIngredients(
            StorageCoreBlockEntity core,
            List<RecipeAdapterMatch.Input> ingredients,
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
            List<RecipeAdapterMatch.Input> ingredients,
            long crafts,
            List<IngredientSource> sources
    ) {
        if (crafts <= 0) return null;
        List<IngredientNeed> needs = aggregateIngredients(ingredients, crafts);
        if (needs == null || needs.isEmpty()) return null;

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
                allocations.stream().map(Map::copyOf).toList()
        );
    }

    private DeliveryPlan planDelivery(
            StorageCoreBlockEntity core,
            IngredientPlan ingredientPlan,
            RecipeAdapterMatch match,
            long crafts,
            DeliveryTarget destination,
            Player player
    ) {
        if (crafts <= 0) return null;
        RecipeAdapterMatch.ToolCost toolCost = match.cost().toolCost().orElse(null);
        ToolUsePlan toolUse = toolCost == null ? null : planToolUse(core, toolCost, crafts);
        if (toolCost != null && toolUse == null) return null;
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
        Level level = core.getLevel();
        if (level == null) return null;
        RecipeAdapterMatch.CheckedOutput checkedOutput = match.checkedOutput(
                ingredientPlan.allocations(), crafts, level).orElse(null);
        if (checkedOutput == null) return null;
        Map<ItemKey, Long> primaryOutputs = checkedOutput.primaryOutputs();
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

        for (Map.Entry<ItemKey, Long> entry : checkedOutput.remainders().entrySet()) {
            if (destination == DeliveryTarget.STORAGE) {
                if (!addCoreOutput(coreOutputs, entry.getKey(), entry.getValue())) return null;
            } else if (!addOutputToInventoryThenCore(
                    inventory, entry.getKey().toStack(1), entry.getValue(), coreOutputs)) {
                return null;
            }
        }
        Map<StorageResourceKey, Long> coreDeltas = coreDeltas(
                core,
                ingredientPlan,
                coreOutputs,
                checkedOutput.resourceOutputs(),
                match.typedRecipePlan().orElse(null),
                crafts);
        if (coreDeltas == null || !coreDeltas.isEmpty()
                && !applyCoreResourceDeltas(
                core, coreDeltas, Action.SIMULATE)) return null;
        return new DeliveryPlan(
                List.copyOf(inventory), carried, Map.copyOf(coreDeltas), toolUse);
    }

    private static Map<StorageResourceKey, Long> coreDeltas(
            StorageCoreBlockEntity core,
            IngredientPlan ingredientPlan,
            Map<ItemKey, Long> coreOutputs,
            Map<StorageResourceKey, Long> resourceOutputs,
            TypedRecipePlan typedPlan,
            long crafts
    ) {
        Level level = core.getLevel();
        if (level == null || crafts <= 0) return null;
        Map<StorageResourceKey, Long> deltas = new LinkedHashMap<>();
        try {
            for (Map.Entry<ItemKey, Long> entry : ingredientPlan.coreReservations().entrySet()) {
                mergeResourceDelta(
                        deltas,
                        StorageResourceBridge.itemKey(entry.getKey(), level.registryAccess()),
                        Math.negateExact(entry.getValue()));
            }
            for (Map.Entry<ItemKey, Long> entry : coreOutputs.entrySet()) {
                mergeResourceDelta(
                        deltas,
                        StorageResourceBridge.itemKey(entry.getKey(), level.registryAccess()),
                        entry.getValue());
            }
            for (Map.Entry<StorageResourceKey, Long> entry : resourceOutputs.entrySet()) {
                mergeResourceDelta(deltas, entry.getKey(), entry.getValue());
            }
            if (typedPlan != null) {
                for (TypedRecipeInput input : typedPlan.inputs()) {
                    if (input.role() != TypedRecipeInput.Role.CONSUME) continue;
                    mergeResourceDelta(
                            deltas,
                            input.key(),
                            Math.negateExact(Math.multiplyExact(input.amount(), crafts)));
                }
            }
        } catch (ArithmeticException exception) {
            return null;
        }
        return Map.copyOf(deltas);
    }

    private static void mergeResourceDelta(
            Map<StorageResourceKey, Long> deltas,
            StorageResourceKey key,
            long delta
    ) {
        if (delta == 0) return;
        long merged = Math.addExact(deltas.getOrDefault(key, 0L), delta);
        if (merged == 0) deltas.remove(key);
        else deltas.put(key, merged);
    }

    private static boolean applyCoreResourceDeltas(
            StorageCoreBlockEntity core,
            Map<StorageResourceKey, Long> deltas,
            Action action
    ) {
        if (deltas.isEmpty()) return true;
        StorageResourceTransaction.Builder transaction = StorageResourceTransaction.builder();
        deltas.forEach(transaction::add);
        return core.applyResourceTransaction(
                transaction.build(), action, Actor.magicCrafting());
    }

    private static ToolUsePlan planToolUse(
            StorageCoreBlockEntity core,
            RecipeAdapterMatch.ToolCost toolCost,
            long crafts
    ) {
        long amount;
        try {
            amount = Math.multiplyExact(toolCost.amountPerCraft(), crafts);
        } catch (ArithmeticException exception) {
            return null;
        }
        return core.hasDescriptorAmount(toolCost.descriptorId(), amount)
                ? new ToolUsePlan(toolCost.descriptorId(), amount)
                : null;
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

    private boolean commitCraft(
            StorageCoreBlockEntity core,
            IngredientPlan plan,
            DeliveryPlan delivery,
            Player player,
            RecipeAdapterMatch plannedMatch,
            EnergyCost energyCost,
            long crafts
    ) {
        Level level = core.getLevel();
        if (level == null) return false;
        ItemStack plannedOutput = plannedMatch.presentationOutput(List.of(), level);
        if (plannedOutput.isEmpty()) return false;
        List<IngredientSource> currentSources = snapshotIngredientSources(core, player);
        RecipeAdapterMatch currentMatch = resolveAvailableRecipeVariantById(
                level, core, plannedMatch.holder().id(), plannedOutput, currentSources);
        if (currentMatch == null
                || !plannedMatch.validatesCommit(currentMatch.holder())
                || !currentMatch.validatesCommit(currentMatch.holder())
                || !plannedMatch.typedRecipePlan().equals(currentMatch.typedRecipePlan())
                || !hasEnergyForCrafts(core, energyCost, crafts)
                || !hasToolForCrafts(
                core, currentMatch.cost().toolCost().orElse(null), crafts)
                || currentMatch.typedRecipePlan().isPresent()
                && !hasTypedInputs(
                core, currentMatch.typedRecipePlan().orElseThrow(), crafts)) return false;
        core.beginMutationBatch();
        try {
            ToolUsePlan toolUse = delivery.toolUse();
            if (toolUse != null
                    && !core.hasDescriptorAmount(toolUse.descriptorId(), toolUse.amount())) return false;
            for (Map.Entry<Integer, PlayerReservation> entry : plan.playerReservations().entrySet()) {
                ItemStack stack = player.getInventory().getItem(entry.getKey());
                PlayerReservation reservation = entry.getValue();
                if (stack.isEmpty() || !ItemKey.of(stack).equals(reservation.key()) || stack.getCount() < reservation.count()) {
                    return false;
                }
            }
            if (!delivery.coreDeltas().isEmpty()
                    && (!applyCoreResourceDeltas(
                    core, delivery.coreDeltas(), Action.SIMULATE)
                    || !applyCoreResourceDeltas(
                    core, delivery.coreDeltas(), Action.EXECUTE))) return false;
            if (energyCost != null && !core.consumeEnergy(energyCost, crafts)) {
                rollbackResourceTransaction(core, delivery.coreDeltas());
                return false;
            }
            if (toolUse != null
                    && !core.consumeDescriptor(toolUse.descriptorId(), toolUse.amount())) {
                rollbackResourceTransaction(core, delivery.coreDeltas());
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

    private static void rollbackResourceTransaction(
            StorageCoreBlockEntity core,
            Map<StorageResourceKey, Long> committed
    ) {
        if (committed.isEmpty()) return;
        Map<StorageResourceKey, Long> inverse = new LinkedHashMap<>();
        try {
            for (Map.Entry<StorageResourceKey, Long> entry : committed.entrySet()) {
                inverse.put(entry.getKey(), Math.negateExact(entry.getValue()));
            }
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Crafting transaction cannot be inverted", exception);
        }
        if (!applyCoreResourceDeltas(core, inverse, Action.EXECUTE)) {
            throw new IllegalStateException("Failed to roll back crafting resource transaction");
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
        RecipeHolder<?> holder = new RecipeHolder<>(
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "compatibility_probe"),
                recipe);
        return BuiltInRecipeAdapters.registry().classify(holder).isPresent();
    }

    public static boolean supportsRecipeHolder(RecipeHolder<?> holder) {
        if (holder == null) return false;
        RecipeAdapterMatch match = BuiltInRecipeAdapters.registry().classify(holder).orElse(null);
        return match != null && match.validatesSimulation(holder);
    }

    private RecipeAdapterMatch resolveAvailableRecipeVariantById(
            Level level,
            StorageCoreBlockEntity core,
            ResourceLocation recipeId,
            ItemStack requestedOutput,
            List<IngredientSource> sources
    ) {
        RecipeHolder<?> holder = resolveRecipeById(level, core, recipeId);
        return holder == null ? null : resolveAvailableRecipeVariant(
                holder, core, requestedOutput, sources, level);
    }

    private static RecipeAdapterMatch resolveAvailableRecipeVariant(
            RecipeHolder<?> holder,
            StorageCoreBlockEntity core,
            ItemStack requestedOutput,
            List<IngredientSource> sources,
            Level level
    ) {
        RecipeAdapterMatch baseMatch = classifyAvailable(holder, core);
        if (baseMatch == null) return null;
        List<ItemStack> availableStacks = sources.stream()
                .map(IngredientSource::stack)
                .toList();
        List<RecipeAdapterMatch> variants = baseMatch.resolveVariants(availableStacks, level);
        if (requestedOutput == null || requestedOutput.isEmpty()) {
            return variants.size() == 1 ? variants.getFirst() : null;
        }
        ItemStack exactOutput = TerminalDisplayStack.strip(requestedOutput);
        for (RecipeAdapterMatch variant : variants) {
            ItemStack output = variant.presentationOutput(List.of(), level);
            if (ItemStack.isSameItemSameComponents(output, exactOutput)) return variant;
        }
        return null;
    }

    private static RecipeAdapterMatch resolveAvailableRecipeLookupVariant(
            RecipeHolder<?> holder,
            StorageCoreBlockEntity core,
            ItemStack requestedOutput,
            List<IngredientSource> sources,
            Level level
    ) {
        RecipeAdapterMatch baseMatch = classifyAvailable(holder, core);
        if (baseMatch == null || requestedOutput == null || requestedOutput.isEmpty()) return null;
        ItemStack requested = TerminalDisplayStack.strip(requestedOutput);
        List<ItemStack> availableStacks = sources.stream().map(IngredientSource::stack).toList();
        List<RecipeAdapterMatch> variants = baseMatch.resolveVariants(availableStacks, level);
        for (RecipeAdapterMatch variant : variants) {
            if (variant.matchesLookupOutput(requested, level)) return variant;
        }
        return null;
    }

    private static RecipeAdapterMatch classifyAvailable(
            RecipeHolder<?> holder,
            StorageCoreBlockEntity core
    ) {
        RecipeAdapterMatch match = BuiltInRecipeAdapters.registry().classify(holder).orElse(null);
        return match != null
                && match.validatesSimulation(holder)
                && isStationAvailable(core, match)
                ? match
                : null;
    }

    private static boolean isStationAvailable(
            StorageCoreBlockEntity core,
            RecipeAdapterMatch match
    ) {
        if (core == null) return false;
        MachineDescriptor station = MachineEnergyTable.get(match.stationDescriptorId());
        if (station == null) return false;
        RecipeAdapterMatch.ToolCost toolCost = match.cost().toolCost().orElse(null);
        if (station.category() == MachineEnergyTable.Category.CONSUMABLE) {
            return toolCost != null
                    && toolCost.descriptorId().equals(station.id())
                    && core.hasDescriptorAmount(toolCost.descriptorId(), toolCost.amountPerCraft());
        }
        int stationSlot = MachineEnergyTable.findSlot(station.id());
        return stationSlot >= 0
                && MachineEnergyTable.isInstalled(core, stationSlot)
                && hasToolForCrafts(core, toolCost, 1);
    }

    private static boolean hasToolForCrafts(
            StorageCoreBlockEntity core,
            RecipeAdapterMatch.ToolCost toolCost,
            long crafts
    ) {
        if (crafts <= 0) return false;
        if (toolCost == null) return true;
        try {
            return core.hasDescriptorAmount(
                    toolCost.descriptorId(),
                    Math.multiplyExact(toolCost.amountPerCraft(), crafts));
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private List<IngredientNeed> aggregateIngredients(
            List<RecipeAdapterMatch.Input> ingredients,
            long crafts
    ) {
        List<IngredientNeed> needs = new ArrayList<>();
        for (RecipeAdapterMatch.Input ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            try {
                needs.add(new IngredientNeed(
                        ingredient,
                        Math.multiplyExact(crafts, ingredient.multiplicity())));
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return needs;
    }

    private List<IngredientNeed> summarizeIngredients(List<RecipeAdapterMatch.Input> ingredients) {
        Map<RecipeAdapterMatch.Input, Integer> counts = new LinkedHashMap<>();
        for (RecipeAdapterMatch.Input ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                counts.merge(ingredient, ingredient.multiplicity(), Math::addExact);
            }
        }
        List<IngredientNeed> needs = new ArrayList<>(counts.size());
        for (Map.Entry<RecipeAdapterMatch.Input, Integer> entry : counts.entrySet()) {
            needs.add(new IngredientNeed(entry.getKey(), entry.getValue()));
        }
        return needs;
    }

    private void updatePreview(StorageCoreBlockEntity core, Player player) {
        if (core == null || core.getLevel() == null) {
            clearRecipePresentation();
            return;
        }
        RecipeAdapterMatch match = resolveCurrentRecipeMatch(core.getLevel());
        if (match == null) {
            clearRecipePresentation();
            return;
        }
        List<IngredientSource> sources = snapshotIngredientSources(core, player);
        CraftPreview preview = computeCraftPreviewFor(match, core, sources);
        applyPreviewData(preview);
        syncRecipePresentation(match, preview, sources, core, true);
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
        boolean sendDescriptorState = false;
        if (playerInventory != null && !playerInventory.player.level().isClientSide()) {
            int fingerprint = playerInventoryFingerprint();
            boolean playerInventoryChanged = fingerprint != lastPlayerInventoryFingerprint;
            lastPlayerInventoryFingerprint = fingerprint;

            StorageCoreBlockEntity core = getCore(playerInventory.player.level());
            boolean topologyChanged = core != null && core.getTopologyRevision() != lastTopologyRevision;
            if (topologyChanged) lastTopologyRevision = core.getTopologyRevision();
            boolean machinesChanged = core != null && core.getMachineRevision() != lastMachineRevision;
            if (machinesChanged) lastMachineRevision = core.getMachineRevision();
            sendDescriptorState = machinesChanged;

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
        if (sendDescriptorState) sendDescriptorStates();
    }

    @Override
    public void sendAllDataToRemote() {
        super.sendAllDataToRemote();
        sendDescriptorStates();
    }

    private void sendDescriptorStates() {
        if (!(playerInventory.player instanceof ServerPlayer serverPlayer)) return;
        StorageCoreBlockEntity core = getCore(serverPlayer.level());
        if (core == null) return;
        List<MachineDescriptorStatePacket.State> states = new ArrayList<>();
        for (MachineDescriptor descriptor : descriptorSnapshot) {
            if (descriptor.category() != MachineEnergyTable.Category.CONSUMABLE) continue;
            states.add(new MachineDescriptorStatePacket.State(
                    descriptor.id(),
                    core.getDescriptorAmount(descriptor.id()),
                    core.hasInfiniteDescriptor(descriptor.id())));
        }
        PacketDistributor.sendToPlayer(serverPlayer, new MachineDescriptorStatePacket(containerId, states));
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
        descriptorStates.clear();
        for (MachineDescriptor descriptor : descriptorSnapshot) {
            if (descriptor.category() != MachineEnergyTable.Category.CONSUMABLE) continue;
            descriptorStates.put(descriptor.id(), new MachineDescriptorStatePacket.State(
                    descriptor.id(),
                    core.getDescriptorAmount(descriptor.id()),
                    core.hasInfiniteDescriptor(descriptor.id())));
        }
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
            if (buttonId == RESET_OUTPUT_DESTINATION_BUTTON) {
                if (!page.isItemPage()) return false;
                outputDestination = TerminalOutputDestination.PLAYER;
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
                     PREVIOUS_SEARCH_MODE_BUTTON,
                     RESET_SORT_ORDER_BUTTON,
                     RESET_SORT_MODE_BUTTON,
                     RESET_SEARCH_MODE_BUTTON -> {
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
                case RESET_PLAYER_INVENTORY_BUTTON -> usePlayerInventory = false;
                case 8 -> prevRecipe();
                case 9 -> nextRecipe();
                default -> { return false; }
            }
            if (core != null && (buttonId >= 7 && buttonId <= 9
                    || buttonId == RESET_PLAYER_INVENTORY_BUTTON)) {
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
        returnTransientInput(player, consumableInputContainer);
    }

    private static void returnTransientInput(Player player, SimpleContainer container) {
        if (container == null) return;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack leftover = container.removeItemNoUpdate(slot);
            if (leftover.isEmpty()) continue;
            if (!player.isAlive() || player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && serverPlayer.hasDisconnected()) {
                player.drop(leftover, false);
            } else {
                player.getInventory().placeItemBackInInventory(leftover);
            }
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
        displayUnlimitedTypeCapacity = core.getTypeCapacity().unlimited();
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
        List<IngredientSource> sources = snapshotIngredientSources(
                core, playerInventory == null ? null : playerInventory.player);
        RecipeAdapterMatch match = resolveAvailableRecipeVariantById(
                level, core, selectedRecipeId, selectedKey.toStack(1), sources);
        if (match == null) return false;
        ItemStack result = match.presentationOutput(List.of(), level);
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
            RecipeAdapterMatch baseMatch = holder == null ? null : classifyAvailable(holder, core);
            if (baseMatch == null) continue;
            for (RecipeAdapterMatch match : baseMatch.resolveVariants(availableStacks, level)) {
                CraftPreview preview = computeCraftPreviewFor(match, core, sources);
                if (preview.craftable() <= 0) continue;
                ItemStack output = match.presentationOutput(List.of(), level);
                if (output.isEmpty()) continue;
                ItemKey key = ItemKey.of(output);
                if (!StorageCoreBlockEntity.matchesFilter(key, currentFilter, level)) continue;
                craftableAmounts.put(key, core.getItemCount(key));
            }
        }
        for (RecipeHolder<?> holder : axeTransformationCatalog.recipes(level, core)) {
            RecipeAdapterMatch match = classifyAvailable(holder, core);
            if (match == null) continue;
            CraftPreview preview = computeCraftPreviewFor(match, core, sources);
            if (preview.craftable() <= 0) continue;
            ItemStack output = match.presentationOutput(List.of(), level);
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
            RecipeAdapterMatch match = resolveAvailableRecipeVariant(
                    holder, core, output, sources, level);
            if (match != null && computeCraftPreviewFor(match, core, sources).craftable() > 0) {
                return true;
            }
        }
        return false;
    }

    private List<RecipeHolder<?>> findRecipes(Level level, ItemStack output) {
        output = TerminalDisplayStack.strip(output);
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        RecipeManager manager = level.getRecipeManager();
        StorageCoreBlockEntity core = getCore(level);
        if (core == null) return recipes;
        List<IngredientSource> sources = snapshotIngredientSources(
                core, playerInventory == null ? null : playerInventory.player);
        for (RecipeType<?> type : BuiltInRecipeAdapters.discoveryTypes()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) manager.getAllRecipesFor((RecipeType) type);
            for (RecipeHolder<?> holder : holders) {
                RecipeAdapterMatch match = resolveAvailableRecipeVariant(
                        holder, core, output, sources, level);
                if (match != null) {
                    recipes.add(holder);
                }
            }
        }
        for (RecipeHolder<?> holder : axeTransformationCatalog.recipes(level, core)) {
            RecipeAdapterMatch match = classifyAvailable(holder, core);
            if (match == null) continue;
            ItemStack result = match.presentationOutput(List.of(), level);
            if (!result.isEmpty() && ItemStack.isSameItemSameComponents(result, output)) {
                recipes.add(holder);
            }
        }

        recipes.sort(Comparator.comparingInt(CraftingTerminalMenu::getRecipeSortOrder));
        return recipes;
    }
}
