package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CraftableRecipeCatalog {
    private final RecipeAdapterRegistry adapterRegistry = BuiltInRecipeAdapters.registry();
    private Collection<RecipeHolder<?>> recipeSnapshot;
    private Map<Item, List<ResourceLocation>> recipeIdsByIngredient = Map.of();
    private Map<ResourceLocation, Integer> recipeOrder = Map.of();
    private List<ResourceLocation> unindexedRecipeIds = List.of();

    List<ResourceLocation> getCandidateRecipeIds(RecipeManager manager, Collection<ItemStack> availableStacks) {
        ensureCurrent(manager);
        Set<ResourceLocation> candidates = new LinkedHashSet<>();
        candidates.addAll(unindexedRecipeIds);
        for (ItemStack stack : availableStacks) {
            if (stack.isEmpty()) continue;
            candidates.addAll(recipeIdsByIngredient.getOrDefault(stack.getItem(), List.of()));
        }
        List<ResourceLocation> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingInt(id -> recipeOrder.getOrDefault(id, Integer.MAX_VALUE)));
        return result;
    }

    private void ensureCurrent(RecipeManager manager) {
        Collection<RecipeHolder<?>> currentSnapshot = manager.getRecipes();
        if (currentSnapshot == recipeSnapshot) return;

        List<RecipeAdapterMatch> supported = new ArrayList<>();
        for (RecipeType<?> type : BuiltInRecipeAdapters.discoveryTypes()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<RecipeHolder<?>> holders = (List) manager.getAllRecipesFor((RecipeType) type);
            holders.stream()
                    .sorted(Comparator.comparing(holder -> holder.id().toString()))
                    .map(adapterRegistry::classify)
                    .flatMap(java.util.Optional::stream)
                    .forEach(supported::add);
        }

        Map<Item, List<ResourceLocation>> byIngredient = new HashMap<>();
        Map<ResourceLocation, Integer> order = new HashMap<>();
        List<ResourceLocation> unindexed = new ArrayList<>();
        for (int index = 0; index < supported.size(); index++) {
            RecipeAdapterMatch match = supported.get(index);
            RecipeHolder<?> holder = match.holder();
            order.put(holder.id(), index);
            Set<Item> indexedItems = new LinkedHashSet<>();
            if (!match.candidateIndex().isExhaustive()) unindexed.add(holder.id());
            for (ItemStack candidate : match.candidateIndex().representatives()) {
                if (!candidate.isEmpty()) indexedItems.add(candidate.getItem());
            }
            for (Item item : indexedItems) {
                byIngredient.computeIfAbsent(item, ignored -> new ArrayList<>()).add(holder.id());
            }
        }

        Map<Item, List<ResourceLocation>> immutableIndex = new HashMap<>();
        byIngredient.forEach((item, ids) -> immutableIndex.put(item, List.copyOf(ids)));
        recipeIdsByIngredient = Map.copyOf(immutableIndex);
        recipeOrder = Map.copyOf(order);
        unindexedRecipeIds = List.copyOf(unindexed);
        recipeSnapshot = currentSnapshot;
    }
}
