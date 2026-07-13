package com.swearprom.magicstorage.magic_storage;

@FunctionalInterface
public interface StorageListener {
    void onChanged(ItemKey key, long delta, long newAmount, Actor actor);

    default void onEnergyChanged(EnergyType type, long newAmount) {
    }
}
