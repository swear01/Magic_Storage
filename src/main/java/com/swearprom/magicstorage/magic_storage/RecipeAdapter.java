package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

interface RecipeAdapter {
    ResourceLocation id();

    int priority();

    boolean supports(RecipeHolder<?> holder);

    RecipeCandidateIndex candidateIndex(RecipeHolder<?> holder);
}
