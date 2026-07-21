package com.swearprom.magicstorage.fixture.recipe;

import com.swearprom.magicstorage.magic_storage.MachineEnergyTable;
import com.swearprom.magicstorage.magic_storage.RecipeFamily;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyApi;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyCost;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyFactories;
import com.swearprom.magicstorage.magic_storage.RecipePresentationKind;
import com.swearprom.magicstorage.magic_storage.StorageResourceKind;
import com.swearprom.magicstorage.magic_storage.StorageResourceKindApi;
import com.swearprom.magicstorage.magic_storage.StorageResourceKey;
import com.swearprom.magicstorage.magic_storage.TypedRecipeInput;
import com.swearprom.magicstorage.magic_storage.TypedRecipeOutput;
import com.swearprom.magicstorage.magic_storage.TypedRecipePlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(FixtureMod.MODID)
public final class FixtureMod {
    public static final String MODID = "magic_storage_recipe_fixture";

    private static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, MODID);
    static final DeferredHolder<RecipeType<?>, RecipeType<FixtureGrindingRecipe>> GRINDING_TYPE =
            RECIPE_TYPES.register("grinding", () -> new RecipeType<>() {
                @Override
                public String toString() {
                    return MODID + ":grinding";
                }
            });
    static final DeferredHolder<RecipeType<?>, RecipeType<FixtureInfusionRecipe>> INFUSION_TYPE =
            RECIPE_TYPES.register("infusion", () -> new RecipeType<>() {
                @Override
                public String toString() {
                    return MODID + ":infusion";
                }
            });
    private static final DeferredRegister<RecipeFamily> RECIPE_FAMILIES =
            RecipeFamilyApi.createDeferredRegister(MODID);
    private static final DeferredRegister<StorageResourceKind> RESOURCE_KINDS =
            StorageResourceKindApi.createDeferredRegister(MODID);

    static {
        RECIPE_FAMILIES.register("grinding", () -> RecipeFamilyFactories.singleItemToItem(
                FixtureGrindingRecipe.class,
                GRINDING_TYPE,
                MachineEnergyTable.STONECUTTER_ID,
                recipe -> recipe.getIngredients().getFirst(),
                (recipe, registries) -> recipe.getResultItem(registries),
                recipe -> RecipeFamilyCost.free(),
                RecipePresentationKind.STONECUTTING));
        RECIPE_FAMILIES.register("infusion", () -> RecipeFamilyFactories.deterministicResources(
                FixtureInfusionRecipe.class,
                INFUSION_TYPE,
                MachineEnergyTable.STONECUTTER_ID,
                (recipe, registries) -> TypedRecipePlan.builder()
                        .input(TypedRecipeInput.consume(item("cobblestone", registries), 2))
                        .input(TypedRecipeInput.consume(mana("blue"), 100))
                        .input(TypedRecipeInput.catalyst(mana("red"), 5))
                        .input(TypedRecipeInput.tool(item("iron_pickaxe", registries), 1))
                        .output(TypedRecipeOutput.primary(item("gravel", registries), 3))
                        .output(TypedRecipeOutput.primary(StorageResourceKey.fluid(
                                new FluidStack(Fluids.WATER, 1), registries), 250))
                        .output(TypedRecipeOutput.remainder(mana("blue"), 25))
                        .presentationOutput(new net.minecraft.world.item.ItemStack(
                                net.minecraft.world.item.Items.GRAVEL, 3))
                        .layout(2, 2, true)
                        .build(),
                recipe -> RecipeFamilyCost.free(),
                RecipePresentationKind.STONECUTTING));
        RESOURCE_KINDS.register("mana", () -> StorageResourceKind.variantAware(
                () -> new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.AMETHYST_SHARD)));
    }

    public FixtureMod(IEventBus modEventBus) {
        RECIPE_TYPES.register(modEventBus);
        RECIPE_FAMILIES.register(modEventBus);
        RESOURCE_KINDS.register(modEventBus);
    }

    private static StorageResourceKey item(
            String path,
            net.minecraft.core.HolderLookup.Provider registries
    ) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                ResourceLocation.withDefaultNamespace(path));
        return StorageResourceKey.item(new net.minecraft.world.item.ItemStack(item), registries);
    }

    private static StorageResourceKey mana(String path) {
        return resource(ResourceLocation.fromNamespaceAndPath(MODID, "mana"), MODID, path);
    }

    private static StorageResourceKey resource(
            ResourceLocation kind,
            String namespace,
            String path
    ) {
        return StorageResourceKey.of(
                kind, ResourceLocation.fromNamespaceAndPath(namespace, path), new CompoundTag());
    }
}
