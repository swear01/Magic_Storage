package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public final class MachineDescriptor {
    private final ResourceLocation id;
    private final ItemStack representative;
    private final Ingredient acceptedItems;
    private final MachineEnergyTable.Category category;
    private final int maxInstalledCount;
    @Nullable
    private final EnergyType energyType;
    private final int energyPerTick;
    @Nullable
    private final ConsumableValue consumableValue;

    private MachineDescriptor(
            ResourceLocation id,
            ItemStack representative,
            Ingredient acceptedItems,
            MachineEnergyTable.Category category,
            int maxInstalledCount,
            @Nullable EnergyType energyType,
            int energyPerTick,
            @Nullable ConsumableValue consumableValue
    ) {
        this.id = Objects.requireNonNull(id);
        this.representative = Objects.requireNonNull(representative).copyWithCount(1);
        this.acceptedItems = Objects.requireNonNull(acceptedItems);
        this.category = Objects.requireNonNull(category);
        this.maxInstalledCount = maxInstalledCount;
        this.energyType = energyType;
        this.energyPerTick = energyPerTick;
        this.consumableValue = consumableValue;
        validate();
    }

    public static MachineDescriptor installable(
            ResourceLocation id,
            ItemStack representative,
            Ingredient acceptedItems,
            MachineEnergyTable.Category category,
            int maxInstalledCount,
            @Nullable EnergyType energyType,
            int energyPerTick
    ) {
        return new MachineDescriptor(
                id,
                representative,
                acceptedItems,
                category,
                maxInstalledCount,
                energyType,
                energyPerTick,
                null);
    }

    public static MachineDescriptor consumable(
            ResourceLocation id,
            ItemStack representative,
            Ingredient acceptedItems,
            ConsumableValue consumableValue
    ) {
        return new MachineDescriptor(
                id,
                representative,
                acceptedItems,
                MachineEnergyTable.Category.CONSUMABLE,
                0,
                null,
                0,
                Objects.requireNonNull(consumableValue));
    }

    static MachineDescriptor clientSynced(
            ResourceLocation id,
            ItemStack representative,
            Ingredient acceptedItems,
            MachineEnergyTable.Category category,
            int maxInstalledCount,
            @Nullable EnergyType energyType,
            int energyPerTick
    ) {
        ConsumableValue clientOnly = category == MachineEnergyTable.Category.CONSUMABLE
                ? stack -> {
                    throw new IllegalStateException("Consumable descriptor values are server-owned");
                }
                : null;
        return new MachineDescriptor(
                id,
                representative,
                acceptedItems,
                category,
                maxInstalledCount,
                energyType,
                energyPerTick,
                clientOnly);
    }

    private void validate() {
        if (representative.isEmpty()) {
            throw new IllegalArgumentException("Descriptor representative cannot be empty: " + id);
        }
        if (acceptedItems.isEmpty()) {
            throw new IllegalArgumentException("Descriptor ingredient cannot be explicitly empty: " + id);
        }
        if (category == MachineEnergyTable.Category.CONSUMABLE) {
            if (maxInstalledCount != 0 || energyType != null || energyPerTick != 0
                    || consumableValue == null) {
                throw new IllegalArgumentException("Invalid consumable descriptor: " + id);
            }
            return;
        }
        if (maxInstalledCount < 1 || maxInstalledCount > 64 || consumableValue != null) {
            throw new IllegalArgumentException("Invalid installable descriptor count: " + id);
        }
        if ((energyType == null) != (energyPerTick == 0) || energyPerTick < 0) {
            throw new IllegalArgumentException("Descriptor energy type/rate must be declared together: " + id);
        }
    }

    public ResourceLocation id() {
        return id;
    }

    public ItemStack representativeStack() {
        return representative.copy();
    }

    public Ingredient acceptedItems() {
        return acceptedItems;
    }

    public MachineEnergyTable.Category category() {
        return category;
    }

    public int maxInstalledCount() {
        return maxInstalledCount;
    }

    @Nullable
    public EnergyType energyType() {
        return energyType;
    }

    public int energyPerTick() {
        return energyPerTick;
    }

    public boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && acceptedItems.test(stack);
    }

    public boolean generatesEnergy() {
        return energyType != null && energyPerTick > 0;
    }

    public ConsumableAmount valueOf(ItemStack stack) {
        if (category != MachineEnergyTable.Category.CONSUMABLE || consumableValue == null) {
            throw new IllegalStateException("Descriptor is not consumable: " + id);
        }
        if (!accepts(stack)) return ConsumableAmount.EMPTY;
        ConsumableAmount value = Objects.requireNonNull(consumableValue.value(stack.copy()));
        if (value.amount() < 0 || value.infinite() && value.amount() != 0) {
            throw new IllegalStateException("Invalid consumable value from descriptor: " + id);
        }
        return value;
    }

    @FunctionalInterface
    public interface ConsumableValue {
        ConsumableAmount value(ItemStack stack);
    }

    public record ConsumableAmount(long amount, boolean infinite) {
        public static final ConsumableAmount EMPTY = new ConsumableAmount(0, false);

        public ConsumableAmount {
            if (amount < 0 || infinite && amount != 0) {
                throw new IllegalArgumentException("Invalid consumable amount");
            }
        }
    }
}
