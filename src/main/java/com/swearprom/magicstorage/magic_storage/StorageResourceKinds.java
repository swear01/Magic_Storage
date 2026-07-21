package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredRegister;

final class StorageResourceKinds {
    private StorageResourceKinds() {
    }

    static void registerBuiltIns(DeferredRegister<StorageResourceKind> kinds) {
        kinds.register(StorageResourceKindApi.ITEM_KIND.getPath(), () ->
                StorageResourceKind.variantAware(() -> new ItemStack(Items.CHEST)));
        kinds.register(StorageResourceKindApi.FLUID_KIND.getPath(), () ->
                StorageResourceKind.variantAware(() -> new ItemStack(Items.BUCKET)));
        kinds.register(StorageResourceKindApi.ENERGY_KIND.getPath(), () ->
                StorageResourceKind.variantless(() -> new ItemStack(Items.REDSTONE)));
        kinds.register(StorageResourceKindApi.CHEMICAL_KIND.getPath(), () ->
                StorageResourceKind.variantless(() -> new ItemStack(Items.BREWING_STAND)));
        kinds.addAlias(
                StorageResourceKindApi.CHEMICAL_KIND,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        MagicStorage.MODID, StorageResourceKindApi.CHEMICAL_KIND.getPath()));
    }

    static boolean accepts(StorageResourceKey key) {
        StorageResourceKind kind = MagicStorage.RESOURCE_KIND_REGISTRY.get(key.kindId());
        return kind != null && kind.accepts(key);
    }

    static boolean isRegistered(StorageResourceKey key) {
        return MagicStorage.RESOURCE_KIND_REGISTRY.get(key.kindId()) != null;
    }

    static ItemStack representative(StorageResourceKey key, net.minecraft.core.HolderLookup.Provider registries) {
        if (key.kindId().equals(StorageResourceKindApi.ITEM_KIND)) {
            var item = StorageResourceBridge.itemKey(key, registries);
            if (item.isPresent()) return item.get().toStack(1);
        }
        if (key.kindId().equals(StorageResourceKindApi.FLUID_KIND)) {
            var fluid = StorageResourceBridge.fluidStack(key, 1, registries);
            if (fluid.isPresent()) {
                ItemStack bucket = new ItemStack(fluid.get().getFluid().getBucket());
                if (!bucket.isEmpty()) return bucket;
            }
        }
        StorageResourceKind kind = MagicStorage.RESOURCE_KIND_REGISTRY.get(key.kindId());
        if (kind == null) throw new IllegalArgumentException("Unknown storage resource kind " + key.kindId());
        return kind.representative();
    }
}
