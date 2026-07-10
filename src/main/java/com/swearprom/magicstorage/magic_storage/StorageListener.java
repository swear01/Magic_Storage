package com.swearprom.magicstorage.magic_storage;

@FunctionalInterface
public interface StorageListener {
    void onChanged(ItemKey key, long delta, long newAmount, Actor actor);
}
