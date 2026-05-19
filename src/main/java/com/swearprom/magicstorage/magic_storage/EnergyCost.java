package com.swearprom.magicstorage.magic_storage;

public record EnergyCost(EnergyType processType, long processAmount,
                         EnergyType fuelType, long fuelAmount) {}
