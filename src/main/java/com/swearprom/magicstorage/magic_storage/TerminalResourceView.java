package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;

public enum TerminalResourceView {
    ITEM,
    FLUID,
    ENERGY,
    GAS,
    OTHER;

    public TerminalResourceView next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public TerminalResourceView previous() {
        return values()[(ordinal() - 1 + values().length) % values().length];
    }

    public boolean matches(StorageResourceKey key) {
        ResourceLocation kind = key.kindId();
        return switch (this) {
            case ITEM -> kind.equals(StorageResourceKindApi.ITEM_KIND);
            case FLUID -> kind.equals(StorageResourceKindApi.FLUID_KIND);
            case ENERGY -> kind.equals(StorageResourceKindApi.ENERGY_KIND);
            case GAS -> kind.equals(StorageResourceKindApi.CHEMICAL_KIND);
            case OTHER -> !isBuiltIn(kind);
        };
    }

    public static TerminalResourceView byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : ITEM;
    }

    private static boolean isBuiltIn(ResourceLocation kind) {
        return kind.equals(StorageResourceKindApi.ITEM_KIND)
                || kind.equals(StorageResourceKindApi.FLUID_KIND)
                || kind.equals(StorageResourceKindApi.ENERGY_KIND)
                || kind.equals(StorageResourceKindApi.CHEMICAL_KIND);
    }
}
