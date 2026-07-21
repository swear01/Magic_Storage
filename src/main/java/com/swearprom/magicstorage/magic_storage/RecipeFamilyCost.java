package com.swearprom.magicstorage.magic_storage;

import java.util.Objects;

public final class RecipeFamilyCost {
    private static final RecipeFamilyCost FREE = new RecipeFamilyCost(null);

    private final EnergyCost energyCost;

    private RecipeFamilyCost(EnergyCost energyCost) {
        this.energyCost = energyCost;
    }

    public static RecipeFamilyCost free() {
        return FREE;
    }

    public static RecipeFamilyCost energy(EnergyCost energyCost) {
        Objects.requireNonNull(energyCost, "energyCost");
        EnergyType processType = Objects.requireNonNull(energyCost.processType(), "processType");
        EnergyType fuelType = Objects.requireNonNull(energyCost.fuelType(), "fuelType");
        if (energyCost.processAmount() < 0 || energyCost.fuelAmount() < 0) {
            throw new IllegalArgumentException("Recipe family energy cost cannot be negative");
        }
        if (energyCost.processAmount() == 0 && energyCost.fuelAmount() == 0) {
            throw new IllegalArgumentException("Use RecipeFamilyCost.free() for a zero energy cost");
        }
        if (processType == fuelType
                && energyCost.processAmount() > 0
                && energyCost.fuelAmount() > 0) {
            throw new IllegalArgumentException("Recipe family energy pools must not overlap");
        }
        return new RecipeFamilyCost(energyCost);
    }

    RecipeAdapterMatch.Cost toInternal() {
        return energyCost == null
                ? RecipeAdapterMatch.Cost.free()
                : RecipeAdapterMatch.Cost.energy(energyCost);
    }
}
