package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

final class BuiltInRecipeAdapters {
    static final ResourceLocation SHAPED_ID = id("shaped_crafting");
    static final ResourceLocation SHAPELESS_ID = id("shapeless_crafting");
    static final ResourceLocation SMELTING_ID = id("smelting");
    static final ResourceLocation BLASTING_ID = id("blasting");
    static final ResourceLocation SMOKING_ID = id("smoking");
    static final ResourceLocation CAMPFIRE_COOKING_ID = id("campfire_cooking");
    static final ResourceLocation STONECUTTING_ID = id("stonecutting");
    static final ResourceLocation SMITHING_TRANSFORM_ID = id("smithing_transform");
    static final ResourceLocation AXE_TRANSFORMATION_ID = id("axe_transformation");

    private static final List<RecipeType<?>> DISCOVERY_TYPES = List.of(
            RecipeType.CRAFTING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
    );
    private static final RecipeAdapterRegistry REGISTRY = new RecipeAdapterRegistry(List.of(
            exact(SHAPED_ID, 0, ShapedRecipe.class, RecipeType.CRAFTING,
                    recipe -> recipe.getWidth() <= 3
                            && recipe.getHeight() <= 3
                            && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(SHAPELESS_ID, 1, ShapelessRecipe.class, RecipeType.CRAFTING,
                    recipe -> hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(SMELTING_ID, 2, SmeltingRecipe.class, RecipeType.SMELTING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(BLASTING_ID, 3, BlastingRecipe.class, RecipeType.BLASTING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(SMOKING_ID, 4, SmokingRecipe.class, RecipeType.SMOKING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(CAMPFIRE_COOKING_ID, 5, CampfireCookingRecipe.class,
                    RecipeType.CAMPFIRE_COOKING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(STONECUTTING_ID, 6, StonecutterRecipe.class, RecipeType.STONECUTTING,
                    recipe -> hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients())),
            exact(SMITHING_TRANSFORM_ID, 7, SmithingTransformRecipe.class, RecipeType.SMITHING,
                    recipe -> true, BuiltInRecipeAdapters::smithingCandidateIndex),
            exact(AXE_TRANSFORMATION_ID, 8, AxeTransformationRecipe.class,
                    AxeTransformationRecipe.TYPE, recipe -> !recipe.input().isEmpty(),
                    recipe -> candidateIndex(List.of(recipe.input())))
    ));

    private BuiltInRecipeAdapters() {
    }

    static RecipeAdapterRegistry registry() {
        return REGISTRY;
    }

    static List<RecipeType<?>> discoveryTypes() {
        return DISCOVERY_TYPES;
    }

    private static boolean hasInput(List<Ingredient> ingredients) {
        return ingredients.stream().anyMatch(ingredient -> !ingredient.isEmpty());
    }

    private static RecipeCandidateIndex candidateIndex(List<Ingredient> ingredients) {
        boolean exhaustive = true;
        List<ItemStack> representatives = new java.util.ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            exhaustive &= ingredient.isSimple();
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty()) representatives.add(stack.copyWithCount(1));
            }
        }
        return exhaustive
                ? RecipeCandidateIndex.exhaustive(representatives)
                : RecipeCandidateIndex.nonExhaustive(representatives);
    }

    private static RecipeCandidateIndex smithingCandidateIndex(SmithingTransformRecipe recipe) {
        List<ItemStack> representatives = new java.util.ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (stack.isEmpty()) continue;
            if (recipe.isTemplateIngredient(stack)
                    || recipe.isBaseIngredient(stack)
                    || recipe.isAdditionIngredient(stack)) {
                representatives.add(stack.copyWithCount(1));
            }
        }
        return RecipeCandidateIndex.nonExhaustive(representatives);
    }

    private static <R extends Recipe<?>> RecipeAdapter exact(
            ResourceLocation id,
            int priority,
            Class<R> recipeClass,
            RecipeType<?> recipeType,
            Predicate<R> additionalContract,
            Function<R, RecipeCandidateIndex> candidateIndexFactory
    ) {
        return new ExactRecipeAdapter<>(
                id, priority, recipeClass, recipeType, additionalContract, candidateIndexFactory);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, path);
    }

    private record ExactRecipeAdapter<R extends Recipe<?>>(
            ResourceLocation id,
            int priority,
            Class<R> recipeClass,
            RecipeType<?> recipeType,
            Predicate<R> additionalContract,
            Function<R, RecipeCandidateIndex> candidateIndexFactory
    ) implements RecipeAdapter {
        @Override
        public boolean supports(RecipeHolder<?> holder) {
            Recipe<?> candidate = holder.value();
            if (candidate.getClass() != recipeClass) return false;
            R recipe = recipeClass.cast(candidate);
            return recipe.getType() == recipeType
                    && !recipe.isSpecial()
                    && !recipe.isIncomplete()
                    && additionalContract.test(recipe);
        }

        @Override
        public RecipeCandidateIndex candidateIndex(RecipeHolder<?> holder) {
            if (!supports(holder)) {
                throw new IllegalArgumentException("Recipe holder is not supported by adapter " + id);
            }
            return candidateIndexFactory.apply(recipeClass.cast(holder.value()));
        }
    }
}
