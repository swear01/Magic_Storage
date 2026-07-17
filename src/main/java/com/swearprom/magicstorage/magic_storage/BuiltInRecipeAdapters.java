package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
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

    private static final ResourceLocation COMPATIBILITY_PROBE_ID = id("compatibility_probe");
    private static final List<RecipeType<?>> DISCOVERY_TYPES = List.of(
            RecipeType.CRAFTING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
    );
    private static final Map<Recipe<?>, List<RecipeAdapterMatch.Input>> SMITHING_INPUT_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final RecipeAdapterRegistry REGISTRY = new RecipeAdapterRegistry(List.of(
            exact(SHAPED_ID, 0, ShapedRecipe.class, RecipeType.CRAFTING,
                    recipe -> recipe.getWidth() <= 3
                            && recipe.getHeight() <= 3
                            && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    BuiltInRecipeAdapters::shapedContract),
            exact(SHAPELESS_ID, 0, ShapelessRecipe.class, RecipeType.CRAFTING,
                    recipe -> hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    BuiltInRecipeAdapters::shapelessContract),
            exact(SMELTING_ID, 1, SmeltingRecipe.class, RecipeType.SMELTING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    (recipe, validator) -> cookingContract(
                            recipe,
                            MachineEnergyTable.FURNACE_ID,
                            EnergyType.SMELTING_ENERGY,
                            validator)),
            exact(BLASTING_ID, 2, BlastingRecipe.class, RecipeType.BLASTING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    (recipe, validator) -> cookingContract(
                            recipe,
                            MachineEnergyTable.BLAST_FURNACE_ID,
                            EnergyType.BLASTING_ENERGY,
                            validator)),
            exact(SMOKING_ID, 3, SmokingRecipe.class, RecipeType.SMOKING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    (recipe, validator) -> cookingContract(
                            recipe,
                            MachineEnergyTable.SMOKER_ID,
                            EnergyType.SMOKING_ENERGY,
                            validator)),
            exact(CAMPFIRE_COOKING_ID, 4, CampfireCookingRecipe.class,
                    RecipeType.CAMPFIRE_COOKING,
                    recipe -> recipe.getCookingTime() > 0 && hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    (recipe, validator) -> cookingContract(
                            recipe,
                            MachineEnergyTable.CAMPFIRE_ID,
                            EnergyType.CAMPFIRE_ENERGY,
                            validator)),
            exact(STONECUTTING_ID, 5, StonecutterRecipe.class, RecipeType.STONECUTTING,
                    recipe -> hasInput(recipe.getIngredients()),
                    recipe -> candidateIndex(recipe.getIngredients()),
                    BuiltInRecipeAdapters::stonecuttingContract),
            exact(SMITHING_TRANSFORM_ID, 6, SmithingTransformRecipe.class, RecipeType.SMITHING,
                    recipe -> true,
                    BuiltInRecipeAdapters::smithingCandidateIndex,
                    BuiltInRecipeAdapters::smithingContract),
            exact(AXE_TRANSFORMATION_ID, 7, AxeTransformationRecipe.class,
                    AxeTransformationRecipe.TYPE,
                    recipe -> !recipe.input().isEmpty(),
                    recipe -> candidateIndex(List.of(recipe.input())),
                    BuiltInRecipeAdapters::axeContract)
    ));

    private BuiltInRecipeAdapters() {
    }

    static RecipeAdapterRegistry registry() {
        return REGISTRY;
    }

    static List<RecipeType<?>> discoveryTypes() {
        return DISCOVERY_TYPES;
    }

    static boolean supportsRecipe(Recipe<?> recipe) {
        return REGISTRY.classify(new RecipeHolder<>(COMPATIBILITY_PROBE_ID, recipe)).isPresent();
    }

    static EnergyCost energyCost(Recipe<?> recipe) {
        return REGISTRY.classify(new RecipeHolder<>(COMPATIBILITY_PROBE_ID, recipe))
                .flatMap(match -> match.cost().energyCost())
                .orElse(null);
    }

    private static RecipeAdapterMatch.Contract shapedContract(
            ShapedRecipe recipe,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        return contract(
                ingredientInputs(recipe.getIngredients()),
                MachineEnergyTable.CRAFTING_TABLE_ID,
                RecipeAdapterMatch.Cost.free(),
                fixedOutput(recipe),
                new RecipeAdapterMatch.Presentation(
                        RecipePresentationKind.CRAFTING,
                        recipe.getWidth(),
                        recipe.getHeight(),
                        false,
                        fixedPresentationOutput(recipe)),
                validator);
    }

    private static RecipeAdapterMatch.Contract shapelessContract(
            ShapelessRecipe recipe,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        return contract(
                ingredientInputs(recipe.getIngredients()),
                MachineEnergyTable.CRAFTING_TABLE_ID,
                RecipeAdapterMatch.Cost.free(),
                fixedOutput(recipe),
                new RecipeAdapterMatch.Presentation(
                        RecipePresentationKind.CRAFTING,
                        3,
                        3,
                        true,
                        fixedPresentationOutput(recipe)),
                validator);
    }

    private static RecipeAdapterMatch.Contract cookingContract(
            AbstractCookingRecipe recipe,
            ResourceLocation stationDescriptorId,
            EnergyType processType,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        long cookingTime = recipe.getCookingTime();
        EnergyCost energyCost = new EnergyCost(
                processType,
                cookingTime,
                EnergyType.FURNACE_FUEL,
                cookingTime);
        return contract(
                ingredientInputs(recipe.getIngredients()),
                stationDescriptorId,
                RecipeAdapterMatch.Cost.energy(energyCost),
                fixedOutput(recipe),
                new RecipeAdapterMatch.Presentation(
                        RecipePresentationKind.COOKING,
                        1,
                        1,
                        false,
                        fixedPresentationOutput(recipe)),
                validator);
    }

    private static RecipeAdapterMatch.Contract stonecuttingContract(
            StonecutterRecipe recipe,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        return contract(
                ingredientInputs(recipe.getIngredients()),
                MachineEnergyTable.STONECUTTER_ID,
                RecipeAdapterMatch.Cost.free(),
                fixedOutput(recipe),
                new RecipeAdapterMatch.Presentation(
                        RecipePresentationKind.STONECUTTING,
                        1,
                        1,
                        false,
                        fixedPresentationOutput(recipe)),
                validator);
    }

    private static RecipeAdapterMatch.Contract smithingContract(
            SmithingTransformRecipe recipe,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        return contract(
                smithingInputs(recipe),
                MachineEnergyTable.SMITHING_TABLE_ID,
                RecipeAdapterMatch.Cost.free(),
                (allocations, crafts, level) -> smithingOutput(recipe, allocations, level),
                new RecipeAdapterMatch.Presentation(
                        RecipePresentationKind.SMITHING,
                        3,
                        1,
                        false,
                        (inputs, level) -> smithingPresentationOutput(recipe, inputs, level)),
                validator);
    }

    private static RecipeAdapterMatch.Contract axeContract(
            AxeTransformationRecipe recipe,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        return contract(
                ingredientInputs(List.of(recipe.input())),
                MachineEnergyTable.AXE_ID,
                RecipeAdapterMatch.Cost.tool(
                        new RecipeAdapterMatch.ToolCost(MachineEnergyTable.AXE_ID, 1)),
                fixedOutput(recipe),
                new RecipeAdapterMatch.Presentation(
                        RecipePresentationKind.AXE,
                        1,
                        1,
                        false,
                        fixedPresentationOutput(recipe)),
                validator);
    }

    private static RecipeAdapterMatch.Contract contract(
            List<RecipeAdapterMatch.Input> orderedInputs,
            ResourceLocation stationDescriptorId,
            RecipeAdapterMatch.Cost cost,
            RecipeAdapterMatch.OutputResolver outputResolver,
            RecipeAdapterMatch.Presentation presentation,
            RecipeAdapterMatch.HolderValidator validator
    ) {
        return new RecipeAdapterMatch.Contract(
                orderedInputs,
                stationDescriptorId,
                cost,
                outputResolver,
                presentation,
                validator,
                validator);
    }

    private static RecipeAdapterMatch.OutputResolver fixedOutput(Recipe<?> recipe) {
        return (allocations, crafts, level) -> {
            if (level == null) return Optional.empty();
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) return Optional.empty();
            try {
                long amount = Math.multiplyExact((long) result.getCount(), crafts);
                return checkedOutput(Map.of(ItemKey.of(result), amount), allocations);
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
        };
    }

    private static RecipeAdapterMatch.PresentationOutputResolver fixedPresentationOutput(
            Recipe<?> recipe
    ) {
        return (inputs, level) -> level == null
                ? ItemStack.EMPTY
                : recipe.getResultItem(level.registryAccess()).copy();
    }

    private static Optional<RecipeAdapterMatch.CheckedOutput> smithingOutput(
            SmithingTransformRecipe recipe,
            List<Map<ItemKey, Long>> allocations,
            Level level
    ) {
        if (level == null || allocations.size() != 3) return Optional.empty();
        ItemStack template = firstAllocatedStack(allocations.get(0));
        ItemStack addition = firstAllocatedStack(allocations.get(2));
        if (template.isEmpty() || addition.isEmpty()) return Optional.empty();
        Map<ItemKey, Long> outputs = new LinkedHashMap<>();
        try {
            for (Map.Entry<ItemKey, Long> base : allocations.get(1).entrySet()) {
                SmithingRecipeInput input = new SmithingRecipeInput(
                        template, base.getKey().toStack(1), addition);
                if (!recipe.matches(input, level)) return Optional.empty();
                ItemStack result = recipe.assemble(input, level.registryAccess());
                if (result.isEmpty()) return Optional.empty();
                long amount = Math.multiplyExact(base.getValue(), (long) result.getCount());
                outputs.merge(ItemKey.of(result), amount, Math::addExact);
            }
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        return checkedOutput(outputs, allocations);
    }

    private static ItemStack smithingPresentationOutput(
            SmithingTransformRecipe recipe,
            List<ItemStack> inputs,
            Level level
    ) {
        if (level == null) return ItemStack.EMPTY;
        if (inputs.size() >= 3
                && !inputs.get(0).isEmpty()
                && !inputs.get(1).isEmpty()
                && !inputs.get(2).isEmpty()) {
            SmithingRecipeInput input = new SmithingRecipeInput(
                    inputs.get(0), inputs.get(1), inputs.get(2));
            if (recipe.matches(input, level)) {
                ItemStack result = recipe.assemble(input, level.registryAccess());
                if (!result.isEmpty()) return result;
            }
        }
        return recipe.getResultItem(level.registryAccess()).copy();
    }

    private static Optional<RecipeAdapterMatch.CheckedOutput> checkedOutput(
            Map<ItemKey, Long> primaryOutputs,
            List<Map<ItemKey, Long>> allocations
    ) {
        Map<ItemKey, Long> remainders = new LinkedHashMap<>();
        try {
            for (Map<ItemKey, Long> allocation : allocations) {
                for (Map.Entry<ItemKey, Long> entry : allocation.entrySet()) {
                    ItemStack consumed = entry.getKey().toStack(1);
                    if (!consumed.hasCraftingRemainingItem()) continue;
                    ItemStack remainder = consumed.getCraftingRemainingItem();
                    if (remainder.isEmpty()) continue;
                    long amount = Math.multiplyExact(entry.getValue(), (long) remainder.getCount());
                    remainders.merge(ItemKey.of(remainder), amount, Math::addExact);
                }
            }
            return Optional.of(new RecipeAdapterMatch.CheckedOutput(primaryOutputs, remainders));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static ItemStack firstAllocatedStack(Map<ItemKey, Long> allocation) {
        for (Map.Entry<ItemKey, Long> entry : allocation.entrySet()) {
            if (entry.getValue() > 0) return entry.getKey().toStack(1);
        }
        return ItemStack.EMPTY;
    }

    private static boolean hasInput(List<Ingredient> ingredients) {
        return ingredients.stream().anyMatch(ingredient -> !ingredient.isEmpty());
    }

    private static List<RecipeAdapterMatch.Input> ingredientInputs(List<Ingredient> ingredients) {
        List<RecipeAdapterMatch.Input> inputs = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            inputs.add(ingredient.isEmpty()
                    ? RecipeAdapterMatch.Input.empty(ingredient)
                    : RecipeAdapterMatch.Input.of(
                            ingredient,
                            ingredient,
                            Arrays.asList(ingredient.getItems()),
                            1));
        }
        return List.copyOf(inputs);
    }

    private static RecipeCandidateIndex candidateIndex(List<Ingredient> ingredients) {
        boolean exhaustive = true;
        List<ItemStack> representatives = new ArrayList<>();
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

    private static List<RecipeAdapterMatch.Input> smithingInputs(SmithingTransformRecipe recipe) {
        synchronized (SMITHING_INPUT_CACHE) {
            return SMITHING_INPUT_CACHE.computeIfAbsent(recipe, ignored -> {
                List<ItemStack> templates = new ArrayList<>();
                List<ItemStack> bases = new ArrayList<>();
                List<ItemStack> additions = new ArrayList<>();
                for (var item : BuiltInRegistries.ITEM) {
                    ItemStack stack = item.getDefaultInstance();
                    if (stack.isEmpty()) continue;
                    if (recipe.isTemplateIngredient(stack)) templates.add(stack.copyWithCount(1));
                    if (recipe.isBaseIngredient(stack)) bases.add(stack.copyWithCount(1));
                    if (recipe.isAdditionIngredient(stack)) additions.add(stack.copyWithCount(1));
                }
                return List.of(
                        RecipeAdapterMatch.Input.of(
                                new SmithingInputIdentity(recipe, 0),
                                recipe::isTemplateIngredient,
                                templates,
                                1),
                        RecipeAdapterMatch.Input.of(
                                new SmithingInputIdentity(recipe, 1),
                                recipe::isBaseIngredient,
                                bases,
                                1),
                        RecipeAdapterMatch.Input.of(
                                new SmithingInputIdentity(recipe, 2),
                                recipe::isAdditionIngredient,
                                additions,
                                1));
            });
        }
    }

    private static RecipeCandidateIndex smithingCandidateIndex(SmithingTransformRecipe recipe) {
        List<ItemStack> representatives = smithingInputs(recipe).stream()
                .flatMap(input -> input.representatives().stream())
                .toList();
        return RecipeCandidateIndex.nonExhaustive(representatives);
    }

    private static <R extends Recipe<?>> RecipeAdapter exact(
            ResourceLocation id,
            int priority,
            Class<R> recipeClass,
            RecipeType<?> recipeType,
            Predicate<R> additionalContract,
            Function<R, RecipeCandidateIndex> candidateIndexFactory,
            BiFunction<R, RecipeAdapterMatch.HolderValidator, RecipeAdapterMatch.Contract>
                    contractFactory
    ) {
        return new ExactRecipeAdapter<>(
                id,
                priority,
                recipeClass,
                recipeType,
                additionalContract,
                candidateIndexFactory,
                contractFactory);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, path);
    }

    private record SmithingInputIdentity(Recipe<?> recipe, int role) {
    }

    private record ExactRecipeAdapter<R extends Recipe<?>>(
            ResourceLocation id,
            int priority,
            Class<R> recipeClass,
            RecipeType<?> recipeType,
            Predicate<R> additionalContract,
            Function<R, RecipeCandidateIndex> candidateIndexFactory,
            BiFunction<R, RecipeAdapterMatch.HolderValidator, RecipeAdapterMatch.Contract>
                    contractFactory
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

        @Override
        public RecipeAdapterMatch.Contract contract(RecipeHolder<?> holder) {
            if (!supports(holder)) {
                throw new IllegalArgumentException("Recipe holder is not supported by adapter " + id);
            }
            return contractFactory.apply(recipeClass.cast(holder.value()), this::supports);
        }
    }
}

final class RecipeEnergyTable {
    private RecipeEnergyTable() {
    }

    static EnergyCost getCost(Recipe<?> recipe) {
        return BuiltInRecipeAdapters.energyCost(recipe);
    }
}
