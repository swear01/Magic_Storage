package com.swearprom.magicstorage.magic_storage;

public enum SortMode {
    NAME,
    QUANTITY,
    ID;

    public SortMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
