package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class RecipeAdapterRegistry {
    private final List<RecipeAdapter> adapters;
    private final Map<ResourceLocation, RecipeAdapter> adaptersById;

    RecipeAdapterRegistry(List<? extends RecipeAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        List<RecipeAdapter> ordered = new ArrayList<>(adapters.size());
        Map<ResourceLocation, RecipeAdapter> byId = new HashMap<>();
        for (RecipeAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(adapter.id(), "adapter.id");
            if (byId.putIfAbsent(adapter.id(), adapter) != null) {
                throw new IllegalArgumentException("Duplicate recipe adapter ID: " + adapter.id());
            }
            ordered.add(adapter);
        }
        ordered.sort(Comparator
                .comparingInt(RecipeAdapter::priority)
                .thenComparing(adapter -> adapter.id().toString()));
        this.adapters = List.copyOf(ordered);
        this.adaptersById = Map.copyOf(byId);
    }

    List<RecipeAdapter> adapters() {
        return adapters;
    }

    Optional<RecipeAdapter> get(ResourceLocation id) {
        return Optional.ofNullable(adaptersById.get(Objects.requireNonNull(id, "id")));
    }

    Optional<RecipeAdapterMatch> classify(RecipeHolder<?> holder) {
        for (RecipeAdapter adapter : adapters) {
            if (adapter.supports(holder)) {
                return Optional.of(new RecipeAdapterMatch(
                        adapter, holder, adapter.candidateIndex(holder)));
            }
        }
        return Optional.empty();
    }
}
