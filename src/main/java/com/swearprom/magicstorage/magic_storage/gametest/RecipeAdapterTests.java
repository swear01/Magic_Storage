package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.stream.Stream;

@GameTestHolder(MagicStorage.MODID)
@PrefixGameTestTemplate(false)
public final class RecipeAdapterTests {
    private RecipeAdapterTests() {
    }

    @GameTest(template = "craftingtests.platform")
    public static void non_exhaustive_candidates_are_not_omitted_from_craftable_catalog(
            GameTestHelper helper
    ) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos.south(), MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(),
                Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            core.rebuildNetwork(level);
            core.getMachineContainer().setItem(
                    MachineEnergyTable.CRAFTING_TABLE_SLOT, new ItemStack(Items.CRAFTING_TABLE));
            core.insertItem(new ItemStack(Items.STONE));

            Ingredient ingredient = new Ingredient(new NonExhaustiveStoneIngredient());
            RecipeHolder<ShapelessRecipe> holder = new RecipeHolder<>(
                    ResourceLocation.fromNamespaceAndPath("test_mod", "non_exhaustive_catalog"),
                    new ShapelessRecipe("", CraftingBookCategory.MISC,
                            new ItemStack(Items.EMERALD),
                            NonNullList.of(Ingredient.EMPTY, ingredient)));
            var manager = level.getRecipeManager();
            var original = java.util.List.copyOf(manager.getRecipes());
            var registered = new java.util.ArrayList<>(original);
            registered.add(holder);
            try {
                manager.replaceRecipes(registered);
                var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
                var menu = new CraftingTerminalMenu(170, player.getInventory(), core);
                menu.clickMenuButton(player, CraftingTerminalMenu.CRAFTABLE_PAGE_BUTTON);
                menu.refreshDisplayItems(core);
                boolean found = false;
                for (int slot = 0; slot < StorageTerminalMenu.DISPLAY_SLOTS; slot++) {
                    if (menu.getSlot(slot).getItem().is(Items.EMERALD)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    helper.fail("A non-exhaustive displayed-item list omitted a matching recipe");
                    return;
                }
            } finally {
                manager.replaceRecipes(original);
            }
            helper.succeed();
        });
    }

    private static final class NonExhaustiveStoneIngredient implements ICustomIngredient {
        @Override
        public boolean test(ItemStack stack) {
            return stack.is(Items.STONE);
        }

        @Override
        public Stream<ItemStack> getItems() {
            return Stream.of(new ItemStack(Items.DIRT));
        }

        @Override
        public boolean isSimple() {
            return false;
        }

        @Override
        public IngredientType<?> getType() {
            throw new UnsupportedOperationException("Test ingredient is never serialized");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NonExhaustiveStoneIngredient;
        }

        @Override
        public int hashCode() {
            return NonExhaustiveStoneIngredient.class.hashCode();
        }
    }
}
