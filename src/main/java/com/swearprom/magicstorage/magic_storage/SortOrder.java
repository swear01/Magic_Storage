package com.swearprom.magicstorage.magic_storage;

public enum SortOrder {
    ASCENDING,
    DESCENDING;

    public static SortOrder toggle(SortOrder order) {
        return order == ASCENDING ? DESCENDING : ASCENDING;
    }
}
