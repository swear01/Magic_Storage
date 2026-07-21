package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

final class RecipeAdapters {
    private static volatile RecipeAdapterSnapshot snapshot;

    private RecipeAdapters() {
    }

    static RecipeAdapterSnapshot snapshot() {
        RecipeAdapterSnapshot current = snapshot;
        if (current != null) return current;
        synchronized (RecipeAdapters.class) {
            current = snapshot;
            if (current != null) return current;
            Map<ResourceLocation, RecipeFamily> registered = new LinkedHashMap<>();
            MagicStorage.RECIPE_FAMILY_REGISTRY.entrySet().forEach(entry ->
                    registered.put(entry.getKey().location(), entry.getValue()));
            current = RecipeAdapterSnapshot.create(
                    BuiltInRecipeAdapters.builtInAdapters(),
                    BuiltInRecipeAdapters.builtInDiscoveryTypes(),
                    registered,
                    MagicStorage.MACHINE_DESCRIPTOR_REGISTRY::containsKey);
            snapshot = current;
            return current;
        }
    }
}
