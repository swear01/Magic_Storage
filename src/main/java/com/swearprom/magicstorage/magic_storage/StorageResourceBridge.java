package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Optional;

final class StorageResourceBridge {
    static final ResourceLocation FLUID_KIND = ResourceLocation.fromNamespaceAndPath(
            MagicStorage.MODID, "fluid");
    static final ResourceLocation ENERGY_KIND = ResourceLocation.fromNamespaceAndPath(
            MagicStorage.MODID, "neoforge_energy");
    static final StorageResourceKey ENERGY_KEY = StorageResourceKey.of(
            ENERGY_KIND,
            ResourceLocation.fromNamespaceAndPath("neoforge", "energy"),
            new CompoundTag());

    private StorageResourceBridge() {
    }

    static StorageResourceKey fluidKey(
            FluidStack stack,
            HolderLookup.Provider registries
    ) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot key an empty fluid stack");
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        if (fluidId == null) throw new IllegalArgumentException("Fluid is not registered");
        return StorageResourceKey.of(
                FLUID_KIND,
                fluidId,
                encodeComponents(stack.getComponents(), registries));
    }

    static Optional<FluidStack> fluidStack(
            StorageResourceKey key,
            int amount,
            HolderLookup.Provider registries
    ) {
        if (!key.kindId().equals(FLUID_KIND) || amount <= 0) return Optional.empty();
        var fluid = BuiltInRegistries.FLUID.getOptional(key.resourceId()).orElse(null);
        if (fluid == null) return Optional.empty();
        Optional<DataComponentMap> components = DataComponentMap.CODEC.parse(
                RegistryOps.create(NbtOps.INSTANCE, registries), key.variantData()).result();
        if (components.isEmpty()) return Optional.empty();
        FluidStack stack = new FluidStack(fluid, amount);
        stack.applyComponents(components.get());
        return Optional.of(stack);
    }

    private static CompoundTag encodeComponents(
            DataComponentMap components,
            HolderLookup.Provider registries
    ) {
        Tag encoded = DataComponentMap.CODEC.encodeStart(
                RegistryOps.create(NbtOps.INSTANCE, registries), components).getOrThrow();
        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalArgumentException("Resource components did not encode as a compound");
        }
        return compound;
    }
}
