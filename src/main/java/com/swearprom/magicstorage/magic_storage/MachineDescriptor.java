package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
    private final Supplier<List<MachineVariant>> variantSource;
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
            @Nullable Supplier<List<MachineVariant>> variantSource,
            @Nullable ConsumableValue consumableValue
    ) {
        this.id = Objects.requireNonNull(id);
        this.representative = Objects.requireNonNull(representative).copyWithCount(1);
        this.acceptedItems = Objects.requireNonNull(acceptedItems);
        this.category = Objects.requireNonNull(category);
        this.maxInstalledCount = maxInstalledCount;
        this.energyType = energyType;
        this.energyPerTick = energyPerTick;
        this.variantSource = variantSource;
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
                null,
                null);
    }

    public static MachineDescriptor installableVariants(
            ResourceLocation id,
            Supplier<List<MachineVariant>> variants,
            MachineEnergyTable.Category category,
            int maxInstalledCount,
            @Nullable EnergyType energyType
    ) {
        Objects.requireNonNull(variants, "variants");
        List<MachineVariant> initial = checkedVariantStacks(id, variants.get());
        return new MachineDescriptor(
                id,
                initial.getFirst().stack(),
                Ingredient.of(initial.stream().map(MachineVariant::stack)),
                category,
                maxInstalledCount,
                energyType,
                0,
                variants,
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
                null,
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
                null,
                clientOnly);
    }

    static MachineDescriptor clientSyncedVariants(
            ResourceLocation id,
            List<MachineVariant> variants,
            MachineEnergyTable.Category category,
            int maxInstalledCount,
            @Nullable EnergyType energyType
    ) {
        List<MachineVariant> snapshot = List.copyOf(variants);
        return installableVariants(id, () -> snapshot, category, maxInstalledCount, energyType);
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
        if (variantSource == null && ((energyType == null) != (energyPerTick == 0) || energyPerTick < 0)) {
            throw new IllegalArgumentException("Descriptor energy type/rate must be declared together: " + id);
        }
    }

    public ResourceLocation id() {
        return id;
    }

    public ItemStack representativeStack() {
        return variantSource == null ? representative.copy() : variants().getFirst().stack();
    }

    public Ingredient acceptedItems() {
        if (variantSource == null) return acceptedItems;
        return Ingredient.of(variants().stream().map(MachineVariant::stack));
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

    public boolean isPolymorphic() {
        return variantSource != null;
    }

    public boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return variantSource == null
                ? acceptedItems.test(stack)
                : variants().stream().anyMatch(variant -> variant.matches(stack));
    }

    public boolean generatesEnergy() {
        return category == MachineEnergyTable.Category.PROCESS
                && variants().stream().anyMatch(variant -> !variant.rate().isZero());
    }

    public List<MachineVariant> variants() {
        if (category == MachineEnergyTable.Category.CONSUMABLE) return List.of();
        if (variantSource != null) return checkedVariants(id, category, variantSource.get());
        MachineWorkRate rate = category == MachineEnergyTable.Category.PROCESS
                ? MachineWorkRate.of(energyPerTick, 1) : MachineWorkRate.ZERO;
        List<MachineVariant> variants = new ArrayList<>();
        for (ItemStack stack : acceptedItems.getItems()) {
            if (!stack.isEmpty() && variants.stream().noneMatch(variant -> variant.matches(stack))) {
                variants.add(MachineVariant.of(stack, rate));
            }
        }
        if (variants.isEmpty()) variants.add(MachineVariant.of(representative, rate));
        return List.copyOf(variants);
    }

    public Optional<MachineWorkRate> rateFor(ItemStack stack) {
        if (category == MachineEnergyTable.Category.CONSUMABLE || !accepts(stack)) {
            return Optional.empty();
        }
        if (variantSource == null) {
            return Optional.of(category == MachineEnergyTable.Category.PROCESS
                    ? MachineWorkRate.of(energyPerTick, 1) : MachineWorkRate.ZERO);
        }
        return variants().stream()
                .filter(variant -> variant.matches(stack))
                .map(MachineVariant::rate)
                .findFirst();
    }

    private static List<MachineVariant> checkedVariants(
            ResourceLocation id,
            MachineEnergyTable.Category category,
            List<MachineVariant> variants
    ) {
        List<MachineVariant> snapshot = checkedVariantStacks(id, variants);
        for (MachineVariant variant : snapshot) {
            MachineWorkRate rate = variant.rate();
            if (category == MachineEnergyTable.Category.PROCESS && rate.isZero()) {
                throw new IllegalArgumentException("Process machine variants require positive work rates: " + id);
            }
            if (category == MachineEnergyTable.Category.INSTANT && !rate.isZero()) {
                throw new IllegalArgumentException("Instant machine variants cannot generate work: " + id);
            }
            if (category == MachineEnergyTable.Category.CONSUMABLE) {
                throw new IllegalArgumentException("Consumable descriptors cannot expose installable variants: " + id);
            }
        }
        return snapshot;
    }

    private static List<MachineVariant> checkedVariantStacks(
            ResourceLocation id,
            List<MachineVariant> variants
    ) {
        Objects.requireNonNull(variants, "variants");
        if (variants.isEmpty() || variants.size() > 64) {
            throw new IllegalArgumentException("Machine descriptor requires one to 64 variants: " + id);
        }
        List<MachineVariant> snapshot = List.copyOf(variants);
        Set<net.minecraft.world.item.Item> items = new HashSet<>();
        for (MachineVariant variant : snapshot) {
            Objects.requireNonNull(variant, "variant");
            if (!items.add(variant.stack().getItem())) {
                throw new IllegalArgumentException("Duplicate machine variant item for " + id);
            }
        }
        return snapshot;
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
