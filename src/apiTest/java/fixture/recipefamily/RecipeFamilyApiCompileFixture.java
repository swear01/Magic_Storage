package fixture.recipefamily;

import com.swearprom.magicstorage.magic_storage.MachineEnergyTable;
import com.swearprom.magicstorage.magic_storage.RecipeFamily;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyApi;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyCost;
import com.swearprom.magicstorage.magic_storage.RecipeFamilyFactories;
import com.swearprom.magicstorage.magic_storage.RecipePresentationKind;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.StonecutterRecipe;

public final class RecipeFamilyApiCompileFixture {
    private RecipeFamilyApiCompileFixture() {
    }

    public static RecipeFamily create() {
        return RecipeFamilyFactories.singleItemToItem(
                StonecutterRecipe.class,
                () -> RecipeType.STONECUTTING,
                MachineEnergyTable.STONECUTTER_ID,
                recipe -> recipe.getIngredients().getFirst(),
                (recipe, registries) -> recipe.getResultItem(registries),
                recipe -> RecipeFamilyCost.free(),
                RecipePresentationKind.STONECUTTING);
    }

    public static DeferredRegister<RecipeFamily> register() {
        DeferredRegister<RecipeFamily> families = RecipeFamilyApi.createDeferredRegister("fixture_mod");
        families.register("stonecutting", RecipeFamilyApiCompileFixture::create);
        return families;
    }
}
