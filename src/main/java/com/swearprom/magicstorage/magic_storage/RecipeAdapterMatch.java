package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Objects;

record RecipeAdapterMatch(
        RecipeAdapter adapter,
        RecipeHolder<?> holder,
        RecipeCandidateIndex candidateIndex
) {
    RecipeAdapterMatch {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(candidateIndex, "candidateIndex");
    }

    ResourceLocation adapterId() {
        return adapter.id();
    }

    boolean isCurrentHolder(RecipeHolder<?> currentHolder) {
        return holder == currentHolder;
    }
}
