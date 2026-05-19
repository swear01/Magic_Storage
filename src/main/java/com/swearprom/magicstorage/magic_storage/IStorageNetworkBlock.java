package com.swearprom.magicstorage.magic_storage;

public interface IStorageNetworkBlock {
    default boolean isStorageCore() {
        return false;
    }
}
