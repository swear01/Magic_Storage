package com.swearprom.magicstorage.magic_storage;

public enum CraftingTerminalPage {
    STORAGE,
    CRAFTABLE,
    FUEL;

    public boolean isItemPage() {
        return this != FUEL;
    }

    static CraftingTerminalPage fromOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : STORAGE;
    }
}
