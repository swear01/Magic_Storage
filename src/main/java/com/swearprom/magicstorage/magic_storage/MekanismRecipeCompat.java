package com.swearprom.magicstorage.magic_storage;

import mekanism.api.chemical.ChemicalStack;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.MekanismRecipeTypes;
import mekanism.api.recipes.PressurizedReactionRecipe.PressurizedReactionRecipeOutput;
import mekanism.api.recipes.basic.BasicCombinerRecipe;
import mekanism.api.recipes.basic.BasicCrushingRecipe;
import mekanism.api.recipes.basic.BasicEnrichingRecipe;
import mekanism.api.recipes.basic.BasicPressurizedReactionRecipe;
import mekanism.api.recipes.basic.BasicSmeltingRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient;
import mekanism.api.recipes.ingredients.FluidStackIngredient;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class MekanismRecipeCompat {
    private static final String MOD_ID = "mekanism";
    private static final long BASIC_MACHINE_WORK = 10L * SharedConstants.TICKS_PER_SECOND;

    private MekanismRecipeCompat() {
    }

    public static void register(
            DeferredRegister<MachineDescriptor> machineDescriptors,
            DeferredRegister<RecipeFamily> recipeFamilies
    ) {
        Objects.requireNonNull(machineDescriptors, "machineDescriptors");
        Objects.requireNonNull(recipeFamilies, "recipeFamilies");
        if (!machineDescriptors.getRegistryKey().equals(MachineDescriptorApi.REGISTRY_KEY)) {
            throw new IllegalArgumentException("Mekanism descriptor register targets the wrong registry");
        }
        if (!recipeFamilies.getRegistryKey().equals(RecipeFamilyApi.REGISTRY_KEY)) {
            throw new IllegalArgumentException("Mekanism family register targets the wrong registry");
        }
        if (!machineDescriptors.getNamespace().equals(recipeFamilies.getNamespace())) {
            throw new IllegalArgumentException(
                    "Mekanism descriptor and recipe family must share one namespace");
        }

        String namespace = machineDescriptors.getNamespace();
        registerSingleInput(
                machineDescriptors,
                recipeFamilies,
                namespace,
                "crusher",
                BasicCrushingRecipe.class,
                MekanismRecipeTypes.TYPE_CRUSHING,
                BasicCrushingRecipe::getInput,
                BasicCrushingRecipe::getOutputDefinition);
        registerSingleInput(
                machineDescriptors,
                recipeFamilies,
                namespace,
                "enrichment_chamber",
                BasicEnrichingRecipe.class,
                MekanismRecipeTypes.TYPE_ENRICHING,
                BasicEnrichingRecipe::getInput,
                BasicEnrichingRecipe::getOutputDefinition);
        registerSingleInput(
                machineDescriptors,
                recipeFamilies,
                namespace,
                "energized_smelter",
                BasicSmeltingRecipe.class,
                MekanismRecipeTypes.TYPE_SMELTING,
                BasicSmeltingRecipe::getInput,
                BasicSmeltingRecipe::getOutputDefinition);

        String combinerPath = registryPath("combiner");
        ResourceLocation combinerDescriptorId = descriptorId(namespace, "combiner");
        registerStation(machineDescriptors, combinerPath, combinerDescriptorId, "combiner");
        recipeFamilies.register(combinerPath, () -> RecipeFamilyFactories.deterministicResources(
                BasicCombinerRecipe.class,
                MekanismRecipeTypes.TYPE_COMBINING,
                combinerDescriptorId,
                recipe -> true,
                MekanismRecipeCompat::combiningPlan,
                recipe -> RecipeFamilyCost.stationWork(BASIC_MACHINE_WORK),
                RecipePresentationKind.CRAFTING));

        String reactionPath = registryPath("pressurized_reaction_chamber");
        ResourceLocation reactionDescriptorId = descriptorId(
                namespace, "pressurized_reaction_chamber");
        registerStation(
                machineDescriptors,
                reactionPath,
                reactionDescriptorId,
                "pressurized_reaction_chamber");
        recipeFamilies.register(reactionPath, () -> RecipeFamilyFactories.deterministicResources(
                BasicPressurizedReactionRecipe.class,
                MekanismRecipeTypes.TYPE_REACTION,
                reactionDescriptorId,
                MekanismRecipeCompat::hasSupportedItemOutput,
                MekanismRecipeCompat::reactionPlan,
                recipe -> RecipeFamilyCost.stationWork(recipe.getDuration()),
                RecipePresentationKind.CRAFTING));
    }

    public static ResourceLocation descriptorId(String namespace, String stationPath) {
        return ResourceLocation.fromNamespaceAndPath(
                Objects.requireNonNull(namespace, "namespace"), registryPath(stationPath));
    }

    private static <R extends ItemStackToItemStackRecipe> void registerSingleInput(
            DeferredRegister<MachineDescriptor> machineDescriptors,
            DeferredRegister<RecipeFamily> recipeFamilies,
            String namespace,
            String stationPath,
            Class<R> recipeClass,
            Supplier<? extends net.minecraft.world.item.crafting.RecipeType<?>> recipeType,
            java.util.function.Function<R, ItemStackIngredient> input,
            java.util.function.Function<R, List<ItemStack>> output
    ) {
        String path = registryPath(stationPath);
        ResourceLocation descriptorId = descriptorId(namespace, stationPath);
        registerStation(machineDescriptors, path, descriptorId, stationPath);
        recipeFamilies.register(path, () -> RecipeFamilyFactories.deterministicResources(
                recipeClass,
                recipeType,
                descriptorId,
                recipe -> true,
                (recipe, registries) -> singleInputPlan(
                        input.apply(recipe), output.apply(recipe), registries),
                recipe -> RecipeFamilyCost.stationWork(BASIC_MACHINE_WORK),
                RecipePresentationKind.CRAFTING));
    }

    private static void registerStation(
            DeferredRegister<MachineDescriptor> machineDescriptors,
            String registryPath,
            ResourceLocation descriptorId,
            String stationPath
    ) {
        machineDescriptors.register(registryPath, () -> MachineDescriptor.installableVariants(
                descriptorId,
                () -> List.of(MachineVariant.of(
                        stationStack(stationPath), MachineWorkRate.ONE)),
                MachineEnergyTable.Category.PROCESS,
                64,
                null));
    }

    private static TypedRecipePlan singleInputPlan(
            ItemStackIngredient input,
            List<ItemStack> outputDefinition,
            HolderLookup.Provider registries
    ) {
        ItemStack output = fixedItemOutput(outputDefinition);
        return TypedRecipePlan.builder()
                .input(itemInput(input, registries))
                .output(TypedRecipeOutput.primary(
                        StorageResourceKey.item(output, registries), output.getCount()))
                .presentationOutput(output)
                .layout(1, 1, false)
                .build();
    }

    private static TypedRecipePlan combiningPlan(
            BasicCombinerRecipe recipe,
            HolderLookup.Provider registries
    ) {
        ItemStack output = fixedItemOutput(recipe.getOutputDefinition());
        return TypedRecipePlan.builder()
                .input(itemInput(recipe.getMainInput(), registries))
                .input(itemInput(recipe.getExtraInput(), registries))
                .output(TypedRecipeOutput.primary(
                        StorageResourceKey.item(output, registries), output.getCount()))
                .presentationOutput(output)
                .layout(2, 1, false)
                .build();
    }

    private static boolean hasSupportedItemOutput(BasicPressurizedReactionRecipe recipe) {
        List<PressurizedReactionRecipeOutput> outputs = recipe.getOutputDefinition();
        if (outputs.size() != 1 || outputs.getFirst().item().isEmpty()) return false;
        try {
            Math.multiplyExact(recipe.getEnergyRequired(), (long) recipe.getDuration());
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static TypedRecipePlan reactionPlan(
            BasicPressurizedReactionRecipe recipe,
            HolderLookup.Provider registries
    ) {
        List<PressurizedReactionRecipeOutput> definitions = recipe.getOutputDefinition();
        if (definitions.size() != 1 || definitions.getFirst().item().isEmpty()) {
            throw new IllegalArgumentException(
                    "Pressurized Reaction compatibility requires one deterministic item output");
        }
        PressurizedReactionRecipeOutput definition = definitions.getFirst();
        ItemStack itemOutput = definition.item().copy();
        TypedRecipePlan.Builder builder = TypedRecipePlan.builder()
                .input(itemInput(recipe.getInputSolid(), registries))
                .input(fluidInput(recipe.getInputFluid(), registries))
                .input(chemicalInput(recipe.getInputChemical()))
                .output(TypedRecipeOutput.primary(
                        StorageResourceKey.item(itemOutput, registries), itemOutput.getCount()));
        if (recipe.getEnergyRequired() > 0) {
            builder.input(TypedRecipeInput.consume(
                    StorageResourceKey.neoforgeEnergy(),
                    Math.multiplyExact(
                            recipe.getEnergyRequired(), (long) recipe.getDuration())));
        }
        ChemicalStack chemicalOutput = definition.chemical();
        if (!chemicalOutput.isEmpty()) {
            builder.output(TypedRecipeOutput.primary(
                    MekanismChemicalCompat.key(chemicalOutput), chemicalOutput.getAmount()));
        }
        return builder
                .presentationOutput(itemOutput)
                .layout(2, 2, false)
                .build();
    }

    private static TypedRecipeInput itemInput(
            ItemStackIngredient ingredient,
            HolderLookup.Provider registries
    ) {
        LinkedHashMap<StorageResourceKey, Long> alternatives = new LinkedHashMap<>();
        for (ItemStack representation : ingredient.getRepresentations()) {
            if (representation.isEmpty()) continue;
            long amount = ingredient.getNeededAmount(representation);
            if (amount <= 0) continue;
            StorageResourceKey key = StorageResourceKey.item(
                    representation.copyWithCount(1), registries);
            alternatives.merge(key, amount, MekanismRecipeCompat::requireSameAmount);
        }
        return consumeAlternatives(alternatives, "item");
    }

    private static TypedRecipeInput fluidInput(
            FluidStackIngredient ingredient,
            HolderLookup.Provider registries
    ) {
        LinkedHashMap<StorageResourceKey, Long> alternatives = new LinkedHashMap<>();
        for (FluidStack representation : ingredient.getRepresentations()) {
            if (representation.isEmpty()) continue;
            long amount = ingredient.getNeededAmount(representation);
            if (amount <= 0) continue;
            StorageResourceKey key = StorageResourceKey.fluid(
                    representation.copyWithAmount(1), registries);
            alternatives.merge(key, amount, MekanismRecipeCompat::requireSameAmount);
        }
        return consumeAlternatives(alternatives, "fluid");
    }

    private static TypedRecipeInput chemicalInput(ChemicalStackIngredient ingredient) {
        LinkedHashMap<StorageResourceKey, Long> alternatives = new LinkedHashMap<>();
        for (ChemicalStack representation : ingredient.getRepresentations()) {
            if (representation.isEmpty()) continue;
            long amount = ingredient.getNeededAmount(representation);
            if (amount <= 0) continue;
            alternatives.merge(
                    MekanismChemicalCompat.key(representation),
                    amount,
                    MekanismRecipeCompat::requireSameAmount);
        }
        return consumeAlternatives(alternatives, "chemical");
    }

    private static TypedRecipeInput consumeAlternatives(
            LinkedHashMap<StorageResourceKey, Long> alternatives,
            String kind
    ) {
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("Mekanism " + kind + " ingredient has no representatives");
        }
        long amount = alternatives.values().iterator().next();
        if (alternatives.values().stream().anyMatch(value -> value != amount)) {
            throw new IllegalArgumentException(
                    "Mekanism " + kind + " ingredient alternatives require different amounts");
        }
        return TypedRecipeInput.consumeAny(List.copyOf(alternatives.keySet()), amount);
    }

    private static long requireSameAmount(long left, long right) {
        if (left != right) {
            throw new IllegalArgumentException(
                    "Duplicate Mekanism ingredient representation changed its required amount");
        }
        return left;
    }

    private static ItemStack fixedItemOutput(List<ItemStack> definitions) {
        if (definitions.size() != 1 || definitions.getFirst().isEmpty()) {
            throw new IllegalArgumentException(
                    "Mekanism recipe compatibility requires one deterministic item output");
        }
        return definitions.getFirst().copy();
    }

    private static ItemStack stationStack(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new IllegalStateException("Loaded Mekanism did not register station item " + id);
        }
        return new ItemStack(item);
    }

    private static String registryPath(String stationPath) {
        return "mekanism_" + Objects.requireNonNull(stationPath, "stationPath");
    }
}
