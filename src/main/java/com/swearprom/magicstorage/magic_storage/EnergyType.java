package com.swearprom.magicstorage.magic_storage;

public enum EnergyType {
    SMELTING_ENERGY("smelting_energy", true, 1),
    BLASTING_ENERGY("blasting_energy", true, 1),
    SMOKING_ENERGY("smoking_energy", true, 1),
    CAMPFIRE_ENERGY("campfire_energy", true, 1),
    BREW_ENERGY("brew_energy", true, 1),
    FURNACE_FUEL("furnace_fuel", false, 0),
    BLAZE_FUEL("blaze_fuel", false, 0),
    BOTTLE_FUEL("bottle_fuel", false, 0);

    private final String id;
    private final boolean autoFill;
    private final int tickRate;

    EnergyType(String id, boolean autoFill, int tickRate) {
        this.id = id;
        this.autoFill = autoFill;
        this.tickRate = tickRate;
    }

    public String getId() { return id; }
    public boolean isAutoFill() { return autoFill; }
    public int getTickRate() { return tickRate; }
}
