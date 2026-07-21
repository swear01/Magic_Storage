package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class RecipeFamily {
    private final Class<? extends Recipe<?>> exactRecipeClass;
    private final Supplier<? extends RecipeType<?>> recipeType;
    private final ResourceLocation stationDescriptorId;
    private final Function<Recipe<?>, Ingredient> input;
    private final BiFunction<Recipe<?>, HolderLookup.Provider, ItemStack> output;
    private final BiFunction<Recipe<?>, HolderLookup.Provider, TypedRecipePlan> typedPlan;
    private final Function<Recipe<?>, RecipeFamilyCost> cost;
    private final RecipePresentationKind presentationKind;

    RecipeFamily(
            Class<? extends Recipe<?>> exactRecipeClass,
            Supplier<? extends RecipeType<?>> recipeType,
            ResourceLocation stationDescriptorId,
            Function<Recipe<?>, Ingredient> input,
            BiFunction<Recipe<?>, HolderLookup.Provider, ItemStack> output,
            Function<Recipe<?>, RecipeFamilyCost> cost,
            RecipePresentationKind presentationKind
    ) {
        this.exactRecipeClass = Objects.requireNonNull(exactRecipeClass, "exactRecipeClass");
        this.recipeType = Objects.requireNonNull(recipeType, "recipeType");
        this.stationDescriptorId = Objects.requireNonNull(stationDescriptorId, "stationDescriptorId");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.typedPlan = null;
        this.cost = Objects.requireNonNull(cost, "cost");
        this.presentationKind = Objects.requireNonNull(presentationKind, "presentationKind");
    }

    RecipeFamily(
            Class<? extends Recipe<?>> exactRecipeClass,
            Supplier<? extends RecipeType<?>> recipeType,
            ResourceLocation stationDescriptorId,
            BiFunction<Recipe<?>, HolderLookup.Provider, TypedRecipePlan> typedPlan,
            Function<Recipe<?>, RecipeFamilyCost> cost,
            RecipePresentationKind presentationKind
    ) {
        this.exactRecipeClass = Objects.requireNonNull(exactRecipeClass, "exactRecipeClass");
        this.recipeType = Objects.requireNonNull(recipeType, "recipeType");
        this.stationDescriptorId = Objects.requireNonNull(stationDescriptorId, "stationDescriptorId");
        this.input = null;
        this.output = null;
        this.typedPlan = Objects.requireNonNull(typedPlan, "typedPlan");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.presentationKind = Objects.requireNonNull(presentationKind, "presentationKind");
    }

    RecipeType<?> recipeType() {
        return Objects.requireNonNull(recipeType.get(), "recipeType supplier result");
    }

    ResourceLocation stationDescriptorId() {
        return stationDescriptorId;
    }

    RecipeFamilyKey key() {
        return new RecipeFamilyKey(exactRecipeClass, recipeType());
    }

    RecipeAdapter adapter(ResourceLocation id, int priority) {
        return new FamilyAdapter(Objects.requireNonNull(id, "id"), priority);
    }

    private Ingredient inputFor(Recipe<?> recipe) {
        if (input == null) throw new IllegalStateException("Typed recipe family has no legacy input");
        Ingredient ingredient = Objects.requireNonNull(input.apply(recipe), "recipe family input");
        if (ingredient.isEmpty()) {
            throw new IllegalArgumentException("Recipe family input cannot be empty");
        }
        return ingredient;
    }

    private ItemStack outputFor(Recipe<?> recipe, HolderLookup.Provider registries) {
        if (output == null) throw new IllegalStateException("Typed recipe family has no legacy output");
        ItemStack stack = Objects.requireNonNull(
                output.apply(recipe, registries), "recipe family output");
        return stack.copy();
    }

    private TypedRecipePlan typedPlanFor(
            Recipe<?> recipe,
            HolderLookup.Provider registries
    ) {
        if (typedPlan == null) throw new IllegalStateException("Legacy recipe family has no typed plan");
        return Objects.requireNonNull(typedPlan.apply(recipe, registries), "typed recipe plan");
    }

    private RecipeFamilyCost costFor(Recipe<?> recipe) {
        return Objects.requireNonNull(cost.apply(recipe), "recipe family cost");
    }

    private final class FamilyAdapter implements RecipeAdapter {
        private final ResourceLocation id;
        private final int priority;

        private FamilyAdapter(ResourceLocation id, int priority) {
            this.id = id;
            this.priority = priority;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean supports(RecipeHolder<?> holder) {
            Recipe<?> recipe = holder.value();
            return recipe.getClass() == exactRecipeClass
                    && recipe.getType() == recipeType()
                    && !recipe.isSpecial()
                    && !recipe.isIncomplete();
        }

        @Override
        public RecipeCandidateIndex candidateIndex(RecipeHolder<?> holder) {
            Recipe<?> recipe = checkedRecipe(holder);
            if (typedPlan != null) return RecipeCandidateIndex.nonExhaustive(List.of());
            Ingredient ingredient = inputFor(recipe);
            List<ItemStack> representatives = Arrays.stream(ingredient.getItems())
                    .filter(stack -> !stack.isEmpty())
                    .map(stack -> stack.copyWithCount(1))
                    .toList();
            return ingredient.isSimple()
                    ? RecipeCandidateIndex.exhaustive(representatives)
                    : RecipeCandidateIndex.nonExhaustive(representatives);
        }

        @Override
        public RecipeAdapterMatch.Contract contract(RecipeHolder<?> holder) {
            Recipe<?> recipe = checkedRecipe(holder);
            if (typedPlan != null) {
                RecipeAdapterMatch.Presentation presentation = new RecipeAdapterMatch.Presentation(
                        presentationKind,
                        1,
                        1,
                        false,
                        (inputs, level) -> ItemStack.EMPTY);
                RecipeAdapterMatch.HolderValidator validator = this::supports;
                return RecipeAdapterMatch.Contract.pendingTyped(
                        stationDescriptorId, costFor(recipe).toInternal(), presentation, validator);
            }
            Ingredient ingredient = inputFor(recipe);
            RecipeAdapterMatch.Input adapterInput = RecipeAdapterMatch.Input.of(
                    ingredient,
                    ingredient,
                    Arrays.asList(ingredient.getItems()),
                    1);
            RecipeAdapterMatch.OutputResolver outputResolver = (allocations, crafts, level) -> {
                if (level == null) return Optional.empty();
                ItemStack result = outputFor(recipe, level.registryAccess());
                if (result.isEmpty()) return Optional.empty();
                try {
                    long amount = Math.multiplyExact((long) result.getCount(), crafts);
                    return Optional.of(new RecipeAdapterMatch.CheckedOutput(
                            Map.of(ItemKey.of(result), amount), Map.of()));
                } catch (ArithmeticException exception) {
                    return Optional.empty();
                }
            };
            RecipeAdapterMatch.Presentation presentation = new RecipeAdapterMatch.Presentation(
                    presentationKind,
                    1,
                    1,
                    false,
                    (inputs, level) -> level == null
                            ? ItemStack.EMPTY
                            : outputFor(recipe, level.registryAccess()));
            RecipeAdapterMatch.HolderValidator validator = this::supports;
            return new RecipeAdapterMatch.Contract(
                    List.of(adapterInput),
                    stationDescriptorId,
                    costFor(recipe).toInternal(),
                    outputResolver,
                    presentation,
                    validator,
                    validator);
        }

        @Override
        public List<RecipeAdapterMatch.Contract> resolveVariants(
                RecipeHolder<?> holder,
                List<ItemStack> availableStacks,
                Level level
        ) {
            Recipe<?> recipe = checkedRecipe(holder);
            if (typedPlan != null) {
                if (level == null) return List.of();
                TypedRecipePlan plan = typedPlanFor(recipe, level.registryAccess());
                RecipeAdapterMatch.OutputResolver outputResolver = (allocations, crafts, currentLevel) ->
                        checkedTypedOutput(plan, allocations, crafts, currentLevel);
                RecipeAdapterMatch.Presentation presentation = new RecipeAdapterMatch.Presentation(
                        presentationKind,
                        plan.width(),
                        plan.height(),
                        plan.shapeless(),
                        (inputs, currentLevel) -> plan.presentationOutput());
                RecipeAdapterMatch.HolderValidator validator = this::supports;
                return List.of(new RecipeAdapterMatch.Contract(
                        List.of(),
                        stationDescriptorId,
                        costFor(recipe).toInternal(),
                        outputResolver,
                        presentation,
                        validator,
                        validator,
                        plan,
                        false));
            }
            if (level == null || outputFor(recipe, level.registryAccess()).isEmpty()) return List.of();
            return List.of(contract(holder));
        }

        @Override
        public boolean matchesLookupOutput(
                RecipeHolder<?> holder,
                RecipeAdapterMatch.Contract variantContract,
                ItemStack requestedOutput,
                Level level
        ) {
            Recipe<?> recipe = checkedRecipe(holder);
            if (level == null || requestedOutput.isEmpty()) return false;
            if (typedPlan != null) {
                TypedRecipePlan plan = variantContract.typedRecipePlan();
                return plan != null && ItemStack.isSameItemSameComponents(
                        plan.presentationOutput(), requestedOutput);
            }
            ItemStack result = outputFor(recipe, level.registryAccess());
            return !result.isEmpty() && ItemStack.isSameItemSameComponents(result, requestedOutput);
        }

        private Optional<RecipeAdapterMatch.CheckedOutput> checkedTypedOutput(
                TypedRecipePlan plan,
                List<Map<ItemKey, Long>> allocations,
                long crafts,
                Level level
        ) {
            if (level == null || crafts <= 0 || !allocations.isEmpty()) return Optional.empty();
            Map<ItemKey, Long> primary = new java.util.LinkedHashMap<>();
            Map<ItemKey, Long> remainders = new java.util.LinkedHashMap<>();
            Map<StorageResourceKey, Long> resources = new java.util.LinkedHashMap<>();
            try {
                for (TypedRecipeOutput output : plan.outputs()) {
                    long amount = Math.multiplyExact(output.amount(), crafts);
                    if (output.key().kindId().equals(StorageResourceKindApi.ITEM_KIND)) {
                        ItemKey key = StorageResourceBridge.itemKey(
                                output.key(), level.registryAccess()).orElse(null);
                        if (key == null) return Optional.empty();
                        Map<ItemKey, Long> target = output.role() == TypedRecipeOutput.Role.PRIMARY
                                ? primary : remainders;
                        target.merge(key, amount, Math::addExact);
                    } else {
                        resources.merge(output.key(), amount, Math::addExact);
                    }
                }
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
            if (primary.isEmpty()) return Optional.empty();
            return Optional.of(new RecipeAdapterMatch.CheckedOutput(
                    primary, remainders, resources));
        }

        @Override
        public Optional<RecipeFamilyKey> exactFamilyKey() {
            return Optional.of(key());
        }

        private Recipe<?> checkedRecipe(RecipeHolder<?> holder) {
            if (!supports(holder)) {
                throw new IllegalArgumentException("Recipe holder is not supported by family " + id);
            }
            return holder.value();
        }
    }
}

record RecipeFamilyKey(Class<? extends Recipe<?>> recipeClass, RecipeType<?> recipeType) {
    RecipeFamilyKey {
        Objects.requireNonNull(recipeClass, "recipeClass");
        Objects.requireNonNull(recipeType, "recipeType");
    }
}
