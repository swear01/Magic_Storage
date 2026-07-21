package com.swearprom.magicstorage.fixture.recipe;

import com.swearprom.magicstorage.magic_storage.CraftingDestination;
import com.swearprom.magicstorage.magic_storage.CraftingTerminalMenu;
import com.swearprom.magicstorage.magic_storage.ItemKey;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.RecipePresentation;
import com.swearprom.magicstorage.magic_storage.StorageCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(FixtureMod.MODID)
@PrefixGameTestTemplate(false)
public final class RecipeFamilyIntegrationTests {
    private static final int STORAGE_PAGE_BUTTON = 14;
    private static final int FUEL_PAGE_BUTTON = 15;
    private static final ResourceLocation RECIPE_ID = ResourceLocation.fromNamespaceAndPath(
            FixtureMod.MODID, "grinding_cobblestone");

    private RecipeFamilyIntegrationTests() {
    }

    @GameTest(template = "craftingtests.platform")
    public static void registered_family_is_discovered_previewed_and_committed(
            GameTestHelper helper
    ) {
        withFixture(helper, context -> {
            if (context.menu().handleRecipeRequest(
                    context.level(), RECIPE_ID, 1, CraftingDestination.NONE, context.player())) {
                helper.fail("Recipe must require its registered station");
                return;
            }
            if (!installStonecutter(context)) {
                helper.fail("Could not install Stonecutter through the public terminal slots");
                return;
            }
            if (!context.menu().handleRecipeRequest(
                    context.level(), RECIPE_ID, 1, CraftingDestination.NONE, context.player())) {
                helper.fail("Registered custom RecipeType was not discovered");
                return;
            }

            var preview = context.menu().computeCraftPreview(context.core(), context.player());
            RecipePresentation presentation = context.menu().getRecipePresentation();
            if (preview.craftable() != 1
                    || !ItemStack.isSameItemSameComponents(presentation.output(), context.output())
                    || presentation.output().getCount() != 2
                    || !presentation.station().is(Items.STONECUTTER)) {
                helper.fail("Registered family preview did not preserve exact output and station");
                return;
            }
            if (!context.menu().handleRecipeRequest(
                    context.level(), RECIPE_ID, 1, CraftingDestination.INVENTORY, context.player())) {
                helper.fail("Registered family commit failed");
                return;
            }
            if (context.core().getItemCount(ItemKey.of(new ItemStack(Items.COBBLESTONE))) != 0
                    || inventoryCount(context.player().getInventory(), context.output()) != 2) {
                helper.fail("Registered family did not consume and deliver exact counts atomically");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void registered_family_rolls_back_when_inventory_is_full(
            GameTestHelper helper
    ) {
        withFixture(helper, context -> {
            if (!installStonecutter(context)) {
                helper.fail("Could not install Stonecutter through the public terminal slots");
                return;
            }
            if (context.core().insertItem(new ItemStack(Items.COBBLESTONE)) != 1) {
                helper.fail("Could not preserve the input type during rollback check");
                return;
            }
            for (var item : List.of(
                    Items.STONE,
                    Items.DIRT,
                    Items.SAND,
                    Items.OAK_LOG,
                    Items.IRON_INGOT,
                    Items.GOLD_INGOT,
                    Items.DIAMOND,
                    Items.EMERALD,
                    Items.COAL)) {
                if (context.core().insertItem(new ItemStack(item)) != 1) {
                    helper.fail("Could not fill storage type capacity for rollback check");
                    return;
                }
            }
            for (int slot = 0; slot < context.player().getInventory().getContainerSize(); slot++) {
                context.player().getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            if (!context.menu().handleRecipeRequest(
                    context.level(), RECIPE_ID, 1, CraftingDestination.NONE, context.player())) {
                helper.fail("Could not select registered family before rollback check");
                return;
            }
            if (context.menu().handleRecipeRequest(
                    context.level(), RECIPE_ID, 1, CraftingDestination.INVENTORY, context.player())) {
                helper.fail("Full inventory unexpectedly accepted registered-family output");
                return;
            }
            if (context.core().getItemCount(ItemKey.of(new ItemStack(Items.COBBLESTONE))) != 2
                    || inventoryCount(context.player().getInventory(), context.output()) != 0) {
                helper.fail("Failed registered-family delivery changed storage or inventory");
                return;
            }
            helper.succeed();
        });
    }

    private static void withFixture(GameTestHelper helper, FixtureAssertion assertion) {
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
            ItemStack output = new ItemStack(Items.GRAVEL, 2);
            output.set(DataComponents.CUSTOM_NAME, Component.literal("fixture-ground"));
            RecipeHolder<FixtureGrindingRecipe> holder = new RecipeHolder<>(
                    RECIPE_ID,
                    new FixtureGrindingRecipe(Ingredient.of(Items.COBBLESTONE), output));
            var manager = level.getRecipeManager();
            List<RecipeHolder<?>> original = List.copyOf(manager.getRecipes());
            List<RecipeHolder<?>> registered = new ArrayList<>(original);
            registered.removeIf(recipe -> recipe.id().equals(RECIPE_ID));
            registered.add(holder);
            manager.replaceRecipes(registered);
            try {
                core.rebuildNetwork(level);
                if (core.insertItem(new ItemStack(Items.COBBLESTONE)) != 1) {
                    helper.fail("Could not seed fixture input");
                    return;
                }
                var player = helper.makeMockPlayer(GameType.SURVIVAL);
                var menu = new CraftingTerminalMenu(400, player.getInventory(), core);
                assertion.run(new FixtureContext(level, core, player, menu, output));
            } finally {
                manager.replaceRecipes(original);
            }
        });
    }

    private static boolean installStonecutter(FixtureContext context) {
        context.menu().clickMenuButton(context.player(), FUEL_PAGE_BUTTON);
        ItemStack station = new ItemStack(Items.STONECUTTER);
        boolean installed = false;
        for (int index = CraftingTerminalMenu.MACHINE_SLOT_START;
             index < CraftingTerminalMenu.MACHINE_SLOT_START + CraftingTerminalMenu.MACHINE_SLOT_COUNT;
             index++) {
            var slot = context.menu().getSlot(index);
            if (!slot.isActive() || !slot.mayPlace(station)) continue;
            slot.set(station.copy());
            slot.setChanged();
            installed = true;
            break;
        }
        context.menu().clickMenuButton(context.player(), STORAGE_PAGE_BUTTON);
        return installed;
    }

    private static int inventoryCount(net.minecraft.world.entity.player.Inventory inventory, ItemStack expected) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, expected)) count += stack.getCount();
        }
        return count;
    }

    @FunctionalInterface
    private interface FixtureAssertion {
        void run(FixtureContext context);
    }

    private record FixtureContext(
            net.minecraft.server.level.ServerLevel level,
            StorageCoreBlockEntity core,
            net.minecraft.world.entity.player.Player player,
            CraftingTerminalMenu menu,
            ItemStack output
    ) {
    }
}
