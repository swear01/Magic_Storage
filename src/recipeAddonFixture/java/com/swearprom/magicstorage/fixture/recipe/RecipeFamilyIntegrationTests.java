package com.swearprom.magicstorage.fixture.recipe;

import com.swearprom.magicstorage.magic_storage.CraftingDestination;
import com.swearprom.magicstorage.magic_storage.CraftingTerminalMenu;
import com.swearprom.magicstorage.magic_storage.ItemKey;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.RecipePresentation;
import com.swearprom.magicstorage.magic_storage.StorageCoreBlockEntity;
import com.swearprom.magicstorage.magic_storage.Action;
import com.swearprom.magicstorage.magic_storage.Actor;
import com.swearprom.magicstorage.magic_storage.StorageResourceKey;
import com.swearprom.magicstorage.magic_storage.StorageResourceCapabilities;
import com.swearprom.magicstorage.magic_storage.StorageTerminalMenu;
import com.swearprom.magicstorage.magic_storage.TerminalDisplayStack;
import com.swearprom.magicstorage.magic_storage.TerminalResourceDisplay;
import com.swearprom.magicstorage.magic_storage.StorageResourceTransaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
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
    private static final ResourceLocation TYPED_RECIPE_ID = ResourceLocation.fromNamespaceAndPath(
            FixtureMod.MODID, "infuse_cobblestone");

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

    @GameTest(template = "craftingtests.platform")
    public static void registered_resource_kind_uses_the_public_atomic_core_api(
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
            CompoundTag blueVariant = new CompoundTag();
            blueVariant.putString("tone", "blue");
            StorageResourceKey blue = StorageResourceKey.of(
                    ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "mana"),
                    ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "blue"),
                    blueVariant);
            StorageResourceKey red = StorageResourceKey.of(
                    ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "mana"),
                    ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "red"),
                    new CompoundTag());
            StorageResourceKey unknown = StorageResourceKey.of(
                    ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "unknown"),
                    ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "value"),
                    new CompoundTag());

            if (core.insertResource(blue, 500, Action.EXECUTE) != 500
                    || core.insertResource(unknown, 1, Action.EXECUTE) != 0) {
                helper.fail("Public Core API did not accept registered and reject unknown kinds");
                return;
            }
            var resourceHandler = level.getCapability(
                    StorageResourceCapabilities.BLOCK, corePos, Direction.UP);
            if (resourceHandler == null
                    || resourceHandler.getAmount(blue) != 500
                    || !resourceHandler.getStoredResources().contains(blue)) {
                helper.fail("Registered addon kind was absent from the public Core capability");
                return;
            }
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            var terminal = new StorageTerminalMenu(402, player.getInventory(), core);
            boolean terminalEntry = false;
            for (int slot = 0; slot < StorageTerminalMenu.DISPLAY_SLOTS; slot++) {
                ItemStack display = terminal.getSlot(slot).getItem();
                if (TerminalResourceDisplay.key(display).orElse(null) != null
                        && TerminalResourceDisplay.key(display).orElseThrow().equals(blue)
                        && TerminalDisplayStack.amount(display) == 500) {
                    terminalEntry = true;
                    break;
                }
            }
            if (!terminalEntry) {
                helper.fail("Registered addon kind was absent from the terminal listing");
                return;
            }
            StorageResourceTransaction transaction = StorageResourceTransaction.builder()
                    .add(blue, -200)
                    .add(red, 125)
                    .build();
            if (!core.applyResourceTransaction(transaction, Action.SIMULATE, Actor.EMPTY)
                    || core.getResourceAmount(blue) != 500
                    || core.getResourceAmount(red) != 0) {
                helper.fail("Public resource transaction simulation mutated the Core");
                return;
            }
            if (!core.applyResourceTransaction(transaction, Action.EXECUTE, Actor.EMPTY)
                    || core.getResourceAmount(blue) != 300
                    || core.getResourceAmount(red) != 125) {
                helper.fail("Public resource transaction did not commit atomically");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void typed_family_commits_multiple_inputs_outputs_and_retained_roles_atomically(
            GameTestHelper helper
    ) {
        withTypedFixture(helper, context -> {
            if (!installStonecutter(context)) {
                helper.fail("Could not install typed-family station");
                return;
            }
            StorageResourceKey blue = mana("blue");
            StorageResourceKey red = mana("red");
            StorageResourceKey water = StorageResourceKey.fluid(
                    new net.neoforged.neoforge.fluids.FluidStack(
                            net.minecraft.world.level.material.Fluids.WATER, 1),
                    context.level().registryAccess());
            if (context.core().insertItem(new ItemStack(Items.COBBLESTONE, 4)) != 4
                    || context.core().insertItem(new ItemStack(Items.IRON_PICKAXE)) != 1
                    || context.core().insertResource(blue, 200, Action.EXECUTE) != 200
                    || context.core().insertResource(red, 5, Action.EXECUTE) != 5) {
                helper.fail("Could not seed typed-family resources");
                return;
            }
            if (!context.menu().handleRecipeRequest(
                    context.level(), TYPED_RECIPE_ID, 1,
                    CraftingDestination.NONE, context.player())) {
                helper.fail("Typed family was not discovered");
                return;
            }
            int craftable = context.menu().computeCraftPreview(
                    context.core(), context.player()).craftable();
            if (craftable != 2) {
                helper.fail("Typed family preview did not aggregate retained and consumed resources: "
                        + craftable);
                return;
            }
            if (!context.menu().handleRecipeRequest(
                    context.level(), TYPED_RECIPE_ID, 2,
                    CraftingDestination.INVENTORY, context.player())) {
                helper.fail("Typed family commit failed");
                return;
            }
            if (context.core().getItemCount(ItemKey.of(new ItemStack(Items.COBBLESTONE))) != 0
                    || context.core().getItemCount(ItemKey.of(new ItemStack(Items.IRON_PICKAXE))) != 1
                    || context.core().getResourceAmount(blue) != 50
                    || context.core().getResourceAmount(red) != 5
                    || context.core().getResourceAmount(water) != 500
                    || inventoryCount(context.player().getInventory(), new ItemStack(Items.GRAVEL)) != 6) {
                helper.fail("Typed family changed the wrong exact resources or output amounts");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void typed_family_capacity_failure_changes_no_input_output_or_player_state(
            GameTestHelper helper
    ) {
        withTypedFixture(helper, context -> {
            if (!installStonecutter(context)) {
                helper.fail("Could not install typed-family station");
                return;
            }
            StorageResourceKey blue = mana("blue");
            StorageResourceKey red = mana("red");
            if (context.core().insertItem(new ItemStack(Items.COBBLESTONE, 4)) != 4
                    || context.core().insertItem(new ItemStack(Items.IRON_PICKAXE)) != 1
                    || context.core().insertResource(blue, 200, Action.EXECUTE) != 200
                    || context.core().insertResource(red, 5, Action.EXECUTE) != 5) {
                helper.fail("Could not seed typed rollback resources");
                return;
            }
            for (var item : List.of(
                    Items.STONE, Items.DIRT, Items.SAND,
                    Items.COAL, Items.DIAMOND, Items.EMERALD)) {
                if (context.core().insertItem(new ItemStack(item)) != 1) {
                    helper.fail("Could not fill typed rollback capacity");
                    return;
                }
            }
            if (context.core().getTypeCount() != 10) {
                helper.fail("Typed rollback fixture did not fill all ten type slots");
                return;
            }
            if (!context.menu().handleRecipeRequest(
                    context.level(), TYPED_RECIPE_ID, 1,
                    CraftingDestination.NONE, context.player())) {
                helper.fail("Could not select typed family before rollback check");
                return;
            }
            if (context.menu().handleRecipeRequest(
                    context.level(), TYPED_RECIPE_ID, 1,
                    CraftingDestination.INVENTORY, context.player())) {
                helper.fail("Typed family ignored a full destination resource ledger");
                return;
            }
            if (context.core().getItemCount(ItemKey.of(new ItemStack(Items.COBBLESTONE))) != 4
                    || context.core().getResourceAmount(blue) != 200
                    || context.core().getResourceAmount(red) != 5
                    || inventoryCount(context.player().getInventory(), new ItemStack(Items.GRAVEL)) != 0) {
                helper.fail("Failed typed family commit partially mutated resources");
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

    private static void withTypedFixture(GameTestHelper helper, FixtureAssertion assertion) {
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
            RecipeHolder<FixtureInfusionRecipe> holder = new RecipeHolder<>(
                    TYPED_RECIPE_ID,
                    new FixtureInfusionRecipe(
                            Ingredient.of(Items.COBBLESTONE), new ItemStack(Items.GRAVEL, 3)));
            var manager = level.getRecipeManager();
            List<RecipeHolder<?>> original = List.copyOf(manager.getRecipes());
            List<RecipeHolder<?>> registered = new ArrayList<>(original);
            registered.removeIf(recipe -> recipe.id().equals(TYPED_RECIPE_ID));
            registered.add(holder);
            manager.replaceRecipes(registered);
            try {
                core.rebuildNetwork(level);
                var player = helper.makeMockPlayer(GameType.SURVIVAL);
                var menu = new CraftingTerminalMenu(401, player.getInventory(), core);
                assertion.run(new FixtureContext(
                        level, core, player, menu, new ItemStack(Items.GRAVEL, 3)));
            } finally {
                manager.replaceRecipes(original);
            }
        });
    }

    private static StorageResourceKey mana(String path) {
        return resource(
                ResourceLocation.fromNamespaceAndPath(FixtureMod.MODID, "mana"),
                FixtureMod.MODID,
                path);
    }

    private static StorageResourceKey resource(
            ResourceLocation kind,
            String namespace,
            String path
    ) {
        return StorageResourceKey.of(
                kind, ResourceLocation.fromNamespaceAndPath(namespace, path), new CompoundTag());
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
