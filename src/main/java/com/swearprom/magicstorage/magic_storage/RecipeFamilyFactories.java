package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class RecipeFamilyFactories {
    private RecipeFamilyFactories() {
    }

    public static <R extends Recipe<?>> RecipeFamily singleItemToItem(
            Class<R> exactRecipeClass,
            Supplier<? extends RecipeType<?>> recipeType,
            ResourceLocation stationDescriptorId,
            Function<? super R, Ingredient> input,
            BiFunction<? super R, HolderLookup.Provider, ItemStack> output,
            Function<? super R, RecipeFamilyCost> cost,
            RecipePresentationKind presentationKind
    ) {
        Objects.requireNonNull(exactRecipeClass, "exactRecipeClass");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(stationDescriptorId, "stationDescriptorId");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(presentationKind, "presentationKind");
        if (presentationKind == RecipePresentationKind.NONE) {
            throw new IllegalArgumentException("Recipe family presentation cannot be NONE");
        }
        return new RecipeFamily(
                exactRecipeClass,
                recipeType,
                stationDescriptorId,
                recipe -> input.apply(exactRecipeClass.cast(recipe)),
                (recipe, registries) -> output.apply(exactRecipeClass.cast(recipe), registries),
                recipe -> cost.apply(exactRecipeClass.cast(recipe)),
                presentationKind);
    }

    public static <R extends Recipe<?>> RecipeFamily deterministicResources(
            Class<R> exactRecipeClass,
            Supplier<? extends RecipeType<?>> recipeType,
            ResourceLocation stationDescriptorId,
            BiFunction<? super R, HolderLookup.Provider, TypedRecipePlan> plan,
            Function<? super R, RecipeFamilyCost> cost,
            RecipePresentationKind presentationKind
    ) {
        return deterministicResourcesInternal(
                exactRecipeClass, recipeType, stationDescriptorId, recipe -> true,
                plan, cost, presentationKind, false);
    }

    public static <R extends Recipe<?>> RecipeFamily deterministicResources(
            Class<R> exactRecipeClass,
            Supplier<? extends RecipeType<?>> recipeType,
            ResourceLocation stationDescriptorId,
            Predicate<? super R> eligibility,
            BiFunction<? super R, HolderLookup.Provider, TypedRecipePlan> plan,
            Function<? super R, RecipeFamilyCost> cost,
            RecipePresentationKind presentationKind
    ) {
        return deterministicResourcesInternal(
                exactRecipeClass, recipeType, stationDescriptorId, eligibility,
                plan, cost, presentationKind, true);
    }

    private static <R extends Recipe<?>> RecipeFamily deterministicResourcesInternal(
            Class<R> exactRecipeClass,
            Supplier<? extends RecipeType<?>> recipeType,
            ResourceLocation stationDescriptorId,
            Predicate<? super R> eligibility,
            BiFunction<? super R, HolderLookup.Provider, TypedRecipePlan> plan,
            Function<? super R, RecipeFamilyCost> cost,
            RecipePresentationKind presentationKind,
            boolean allowSpecial
    ) {
        Objects.requireNonNull(exactRecipeClass, "exactRecipeClass");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(stationDescriptorId, "stationDescriptorId");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(presentationKind, "presentationKind");
        if (presentationKind == RecipePresentationKind.NONE) {
            throw new IllegalArgumentException("Recipe family presentation cannot be NONE");
        }
        return new RecipeFamily(
                exactRecipeClass,
                recipeType,
                stationDescriptorId,
                recipe -> eligibility.test(exactRecipeClass.cast(recipe)),
                (recipe, registries) -> plan.apply(exactRecipeClass.cast(recipe), registries),
                recipe -> cost.apply(exactRecipeClass.cast(recipe)),
                presentationKind,
                allowSpecial);
    }
}
