package com.swearprom.magicstorage.magic_storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

final class StorageResourceKey implements Comparable<StorageResourceKey> {
    private final ResourceLocation kindId;
    private final ResourceLocation resourceId;
    private final CompoundTag variantData;

    private StorageResourceKey(
            ResourceLocation kindId,
            ResourceLocation resourceId,
            CompoundTag variantData
    ) {
        this.kindId = Objects.requireNonNull(kindId, "kindId");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.variantData = Objects.requireNonNull(variantData, "variantData").copy();
    }

    static StorageResourceKey of(
            ResourceLocation kindId,
            ResourceLocation resourceId,
            CompoundTag variantData
    ) {
        return new StorageResourceKey(kindId, resourceId, variantData);
    }

    ResourceLocation kindId() {
        return kindId;
    }

    ResourceLocation resourceId() {
        return resourceId;
    }

    CompoundTag variantData() {
        return variantData.copy();
    }

    @Override
    public int compareTo(StorageResourceKey other) {
        int kindOrder = kindId.toString().compareTo(other.kindId.toString());
        if (kindOrder != 0) return kindOrder;
        int resourceOrder = resourceId.toString().compareTo(other.resourceId.toString());
        if (resourceOrder != 0) return resourceOrder;
        return variantData.toString().compareTo(other.variantData.toString());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StorageResourceKey key
                && kindId.equals(key.kindId)
                && resourceId.equals(key.resourceId)
                && variantData.equals(key.variantData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kindId, resourceId, variantData);
    }

    @Override
    public String toString() {
        return kindId + "/" + resourceId + variantData;
    }
}
