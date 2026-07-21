package com.swearprom.magicstorage.fixture.recipe;

import com.swearprom.magicstorage.magic_storage.MachineEnergyTable;
import com.swearprom.magicstorage.magic_storage.RecipeFamily;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyApi;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyCost;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyFactories;
import com.swearprom.magicstorage.magic_storage.RecipePresentationKind;
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
    private static final DeferredRegister<RecipeFamily> RECIPE_FAMILIES =
            RecipeFamilyApi.createDeferredRegister(MODID);

    static {
        RECIPE_FAMILIES.register("grinding", () -> RecipeFamilyFactories.singleItemToItem(
                FixtureGrindingRecipe.class,
                GRINDING_TYPE,
                MachineEnergyTable.STONECUTTER_ID,
                recipe -> recipe.getIngredients().getFirst(),
                (recipe, registries) -> recipe.getResultItem(registries),
                recipe -> RecipeFamilyCost.free(),
                RecipePresentationKind.STONECUTTING));
    }

    public FixtureMod(IEventBus modEventBus) {
        RECIPE_TYPES.register(modEventBus);
        RECIPE_FAMILIES.register(modEventBus);
    }
}
