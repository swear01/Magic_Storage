package com.swearprom.magicstorage.fixture.mekanism;

import com.swearprom.magicstorage.magic_storage.Action;
import com.swearprom.magicstorage.magic_storage.CraftingDestination;
import com.swearprom.magicstorage.magic_storage.CraftingTerminalMenu;
import com.swearprom.magicstorage.magic_storage.ItemKey;
import com.swearprom.magicstorage.magic_storage.MagicStorage;
import com.swearprom.magicstorage.magic_storage.StorageCoreBlockEntity;
import com.swearprom.magicstorage.magic_storage.StorageResourceKey;
import com.swearprom.magicstorage.magic_storage.StorageResourceKindApi;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.recipes.basic.BasicCombinerRecipe;
import mekanism.api.recipes.basic.BasicCrushingRecipe;
import mekanism.api.recipes.basic.BasicEnrichingRecipe;
import mekanism.api.recipes.basic.BasicPressurizedReactionRecipe;
import mekanism.api.recipes.basic.BasicSmeltingRecipe;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(MekanismFixtureMod.MODID)
@PrefixGameTestTemplate(false)
public final class MekanismRecipeIntegrationTests {
    private static final int STORAGE_PAGE_BUTTON = 14;
    private static final int FUEL_PAGE_BUTTON = 15;
    private static final int SIMPLE_WORK = 200;
    private static final int REACTION_DURATION = 40;
    private static final long REACTION_ENERGY = 1_000;
    private static final long REACTION_TOTAL_ENERGY = REACTION_ENERGY * REACTION_DURATION;
    private static final long REACTION_FLUID = 250;
    private static final long REACTION_CHEMICAL = 500;
    private static final long REACTION_CHEMICAL_OUTPUT = 125;

    private static final StationSpec CRUSHER = station("crusher");
    private static final StationSpec ENRICHMENT_CHAMBER = station("enrichment_chamber");
    private static final StationSpec ENERGIZED_SMELTER = station("energized_smelter");
    private static final StationSpec COMBINER = station("combiner");
    private static final StationSpec PRESSURIZED_REACTION_CHAMBER =
            station("pressurized_reaction_chamber");

    private MekanismRecipeIntegrationTests() {
    }

    @GameTest(template = "craftingtests.platform")
    public static void crushing_preserves_sized_input_and_exact_output(GameTestHelper helper) {
        ResourceLocation recipeId = recipeId("crushing_sized");
        RecipeHolder<BasicCrushingRecipe> holder = new RecipeHolder<>(recipeId,
                new BasicCrushingRecipe(
                        IngredientCreatorAccess.item().from(Items.COBBLESTONE, 2),
                        new ItemStack(Items.GRAVEL, 3)));
        withFixture(helper, holder, CRUSHER, context -> assertSimpleRecipe(
                helper, context, recipeId, Items.COBBLESTONE, 2, Items.GRAVEL, 3));
    }

    @GameTest(template = "craftingtests.platform")
    public static void enriching_preserves_sized_input_and_exact_output(GameTestHelper helper) {
        ResourceLocation recipeId = recipeId("enriching_sized");
        RecipeHolder<BasicEnrichingRecipe> holder = new RecipeHolder<>(recipeId,
                new BasicEnrichingRecipe(
                        IngredientCreatorAccess.item().from(Items.REDSTONE, 3),
                        new ItemStack(Items.GLOWSTONE_DUST, 4)));
        withFixture(helper, holder, ENRICHMENT_CHAMBER, context -> assertSimpleRecipe(
                helper, context, recipeId, Items.REDSTONE, 3, Items.GLOWSTONE_DUST, 4));
    }

    @GameTest(template = "craftingtests.platform")
    public static void smelting_preserves_sized_input_and_exact_output(GameTestHelper helper) {
        ResourceLocation recipeId = recipeId("smelting_sized");
        RecipeHolder<BasicSmeltingRecipe> holder = new RecipeHolder<>(recipeId,
                new BasicSmeltingRecipe(
                        IngredientCreatorAccess.item().from(Items.RAW_IRON, 2),
                        new ItemStack(Items.IRON_INGOT, 2)));
        withFixture(helper, holder, ENERGIZED_SMELTER, context -> assertSimpleRecipe(
                helper, context, recipeId, Items.RAW_IRON, 2, Items.IRON_INGOT, 2));
    }

    @GameTest(template = "craftingtests.platform")
    public static void combining_consumes_both_sized_item_inputs(GameTestHelper helper) {
        ResourceLocation recipeId = recipeId("combining_sized");
        RecipeHolder<BasicCombinerRecipe> holder = new RecipeHolder<>(recipeId,
                new BasicCombinerRecipe(
                        IngredientCreatorAccess.item().from(Items.COBBLESTONE, 2),
                        IngredientCreatorAccess.item().from(Items.FLINT, 3),
                        new ItemStack(Items.DEEPSLATE, 4)));
        withFixture(helper, holder, COMBINER, context -> {
            seedItem(context.core(), Items.COBBLESTONE, 2);
            seedItem(context.core(), Items.FLINT, 3);
            addWork(context, SIMPLE_WORK);
            if (!select(context, recipeId)
                    || context.menu().computeCraftPreview(
                    context.core(), context.player()).craftable() != 1
                    || !craft(context, recipeId)) {
                helper.fail("Combining did not discover and commit both sized item inputs");
                return;
            }
            if (itemCount(context.core(), Items.COBBLESTONE) != 0
                    || itemCount(context.core(), Items.FLINT) != 0
                    || inventoryCount(context, Items.DEEPSLATE) != 4
                    || stationWork(context) != 0) {
                helper.fail("Combining changed the wrong item counts or station work");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_commits_item_fluid_chemical_energy_work_and_cooutput(
            GameTestHelper helper
    ) {
        ResourceLocation recipeId = recipeId("reaction_all_resources");
        withFixture(helper, itemOutputReaction(recipeId), PRESSURIZED_REACTION_CHAMBER, context -> {
            ReactionKeys keys = seedReactionInputs(context, 1);
            addWork(context, REACTION_DURATION);
            if (!select(context, recipeId)
                    || context.menu().computeCraftPreview(
                    context.core(), context.player()).craftable() != 1
                    || !craft(context, recipeId)) {
                helper.fail("Pressurized Reaction did not commit every typed requirement");
                return;
            }
            if (itemCount(context.core(), Items.CLAY_BALL) != 0
                    || context.core().getResourceAmount(keys.water()) != 0
                    || context.core().getResourceAmount(keys.hydrogen()) != 0
                    || context.core().getResourceAmount(StorageResourceKey.neoforgeEnergy()) != 0
                    || context.core().getResourceAmount(keys.oxygen()) != REACTION_CHEMICAL_OUTPUT
                    || inventoryCount(context, Items.DIAMOND) != 2
                    || stationWork(context) != 0) {
                helper.fail("Pressurized Reaction lost or miscounted a typed input, output, or work");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_missing_item_is_atomic_noop(GameTestHelper helper) {
        assertMissingReactionResource(helper, MissingResource.ITEM);
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_missing_fluid_is_atomic_noop(GameTestHelper helper) {
        assertMissingReactionResource(helper, MissingResource.FLUID);
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_missing_chemical_is_atomic_noop(GameTestHelper helper) {
        assertMissingReactionResource(helper, MissingResource.CHEMICAL);
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_missing_energy_is_atomic_noop(GameTestHelper helper) {
        assertMissingReactionResource(helper, MissingResource.ENERGY);
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_missing_station_work_is_atomic_noop(GameTestHelper helper) {
        assertMissingReactionResource(helper, MissingResource.STATION_WORK);
    }

    @GameTest(template = "craftingtests.platform")
    public static void reaction_full_destination_rolls_back_all_resources_and_work(
            GameTestHelper helper
    ) {
        ResourceLocation recipeId = recipeId("reaction_full_destination");
        withFixture(helper, itemOutputReaction(recipeId), PRESSURIZED_REACTION_CHAMBER, context -> {
            ReactionKeys keys = seedReactionInputs(context, 2);
            for (Item filler : List.of(
                    Items.STONE,
                    Items.DIRT,
                    Items.SAND,
                    Items.OAK_LOG,
                    Items.IRON_INGOT,
                    Items.GOLD_INGOT)) {
                seedItem(context.core(), filler, 1);
            }
            if (context.core().getTypeCount() != context.core().getTotalTypeSlots()) {
                helper.fail("Reaction rollback fixture did not fill storage type capacity");
                return;
            }
            for (int slot = 0; slot < context.player().getInventory().getContainerSize(); slot++) {
                context.player().getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            addWork(context, REACTION_DURATION);
            if (!select(context, recipeId)) {
                helper.fail("Could not select Pressurized Reaction before rollback check");
                return;
            }
            if (craft(context, recipeId)) {
                helper.fail("Full destination unexpectedly accepted Pressurized Reaction outputs");
                return;
            }
            if (itemCount(context.core(), Items.CLAY_BALL) != 4
                    || context.core().getResourceAmount(keys.water()) != REACTION_FLUID * 2
                    || context.core().getResourceAmount(keys.hydrogen()) != REACTION_CHEMICAL * 2
                    || context.core().getResourceAmount(StorageResourceKey.neoforgeEnergy())
                    != REACTION_TOTAL_ENERGY * 2
                    || context.core().getResourceAmount(keys.oxygen()) != 0
                    || inventoryCount(context, Items.DIAMOND) != 0
                    || stationWork(context) != REACTION_DURATION) {
                helper.fail("Failed Pressurized Reaction delivery changed resources or station work");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = "craftingtests.platform")
    public static void chemical_only_reaction_is_not_selectable(GameTestHelper helper) {
        ResourceLocation recipeId = recipeId("reaction_chemical_only");
        Holder<Chemical> hydrogen = chemical("hydrogen");
        Holder<Chemical> oxygen = chemical("oxygen");
        RecipeHolder<BasicPressurizedReactionRecipe> holder = new RecipeHolder<>(recipeId,
                new BasicPressurizedReactionRecipe(
                        IngredientCreatorAccess.item().from(Items.CLAY_BALL, 2),
                        IngredientCreatorAccess.fluid().from(
                                new FluidStack(Fluids.WATER, (int) REACTION_FLUID)),
                        IngredientCreatorAccess.chemicalStack().from(
                                new ChemicalStack(hydrogen, REACTION_CHEMICAL)),
                        REACTION_ENERGY,
                        REACTION_DURATION,
                        ItemStack.EMPTY,
                        new ChemicalStack(oxygen, REACTION_CHEMICAL_OUTPUT)));
        withFixture(helper, holder, PRESSURIZED_REACTION_CHAMBER, context -> {
            seedReactionInputs(context, 1);
            addWork(context, REACTION_DURATION);
            if (select(context, recipeId)) {
                helper.fail("Chemical-only Pressurized Reaction became item-terminal selectable");
                return;
            }
            helper.succeed();
        });
    }

    private static void assertSimpleRecipe(
            GameTestHelper helper,
            FixtureContext context,
            ResourceLocation recipeId,
            Item input,
            int inputCount,
            Item output,
            int outputCount
    ) {
        seedItem(context.core(), input, inputCount);
        addWork(context, SIMPLE_WORK);
        if (!select(context, recipeId)
                || context.menu().computeCraftPreview(
                context.core(), context.player()).craftable() != 1
                || !craft(context, recipeId)) {
            helper.fail("Mekanism item recipe was not discovered, previewed, and committed: " + recipeId);
            return;
        }
        if (itemCount(context.core(), input) != 0
                || inventoryCount(context, output) != outputCount
                || stationWork(context) != 0) {
            helper.fail("Mekanism item recipe changed the wrong counts or station work: " + recipeId);
            return;
        }
        helper.succeed();
    }

    private static void assertMissingReactionResource(
            GameTestHelper helper,
            MissingResource missing
    ) {
        ResourceLocation recipeId = recipeId("reaction_missing_" + missing.id());
        withFixture(helper, itemOutputReaction(recipeId), PRESSURIZED_REACTION_CHAMBER, context -> {
            ReactionKeys keys = reactionKeys(context);
            if (missing != MissingResource.ITEM) seedItem(context.core(), Items.CLAY_BALL, 2);
            if (missing != MissingResource.FLUID) {
                seedResource(context.core(), keys.water(), REACTION_FLUID);
            }
            if (missing != MissingResource.CHEMICAL) {
                seedResource(context.core(), keys.hydrogen(), REACTION_CHEMICAL);
            }
            if (missing != MissingResource.ENERGY) {
                seedResource(
                        context.core(), StorageResourceKey.neoforgeEnergy(), REACTION_TOTAL_ENERGY);
            }
            if (missing != MissingResource.STATION_WORK) addWork(context, REACTION_DURATION);

            long itemBefore = itemCount(context.core(), Items.CLAY_BALL);
            long waterBefore = context.core().getResourceAmount(keys.water());
            long chemicalBefore = context.core().getResourceAmount(keys.hydrogen());
            long energyBefore = context.core().getResourceAmount(StorageResourceKey.neoforgeEnergy());
            long workBefore = stationWork(context);
            if (!select(context, recipeId)) {
                helper.fail("Missing-resource reaction could not be selected for preview: " + missing);
                return;
            }
            if (context.menu().computeCraftPreview(
                    context.core(), context.player()).craftable() != 0) {
                helper.fail("Missing-resource reaction previewed as craftable: " + missing);
                return;
            }
            if (craft(context, recipeId)) {
                helper.fail("Missing-resource reaction unexpectedly committed: " + missing);
                return;
            }
            if (itemCount(context.core(), Items.CLAY_BALL) != itemBefore
                    || context.core().getResourceAmount(keys.water()) != waterBefore
                    || context.core().getResourceAmount(keys.hydrogen()) != chemicalBefore
                    || context.core().getResourceAmount(StorageResourceKey.neoforgeEnergy())
                    != energyBefore
                    || context.core().getResourceAmount(keys.oxygen()) != 0
                    || inventoryCount(context, Items.DIAMOND) != 0
                    || stationWork(context) != workBefore) {
                helper.fail("Missing-resource reaction partially mutated state: " + missing);
                return;
            }
            helper.succeed();
        });
    }

    private static RecipeHolder<BasicPressurizedReactionRecipe> itemOutputReaction(
            ResourceLocation recipeId
    ) {
        Holder<Chemical> hydrogen = chemical("hydrogen");
        Holder<Chemical> oxygen = chemical("oxygen");
        return new RecipeHolder<>(recipeId, new BasicPressurizedReactionRecipe(
                IngredientCreatorAccess.item().from(Items.CLAY_BALL, 2),
                IngredientCreatorAccess.fluid().from(
                        new FluidStack(Fluids.WATER, (int) REACTION_FLUID)),
                IngredientCreatorAccess.chemicalStack().from(
                        new ChemicalStack(hydrogen, REACTION_CHEMICAL)),
                REACTION_ENERGY,
                REACTION_DURATION,
                new ItemStack(Items.DIAMOND, 2),
                new ChemicalStack(oxygen, REACTION_CHEMICAL_OUTPUT)));
    }

    private static ReactionKeys seedReactionInputs(FixtureContext context, int crafts) {
        ReactionKeys keys = reactionKeys(context);
        seedItem(context.core(), Items.CLAY_BALL, Math.multiplyExact(2, crafts));
        seedResource(context.core(), keys.water(), Math.multiplyExact(REACTION_FLUID, crafts));
        seedResource(
                context.core(), keys.hydrogen(), Math.multiplyExact(REACTION_CHEMICAL, crafts));
        seedResource(context.core(), StorageResourceKey.neoforgeEnergy(),
                Math.multiplyExact(REACTION_TOTAL_ENERGY, crafts));
        return keys;
    }

    private static ReactionKeys reactionKeys(FixtureContext context) {
        return new ReactionKeys(
                StorageResourceKey.fluid(
                        new FluidStack(Fluids.WATER, 1), context.level().registryAccess()),
                chemicalKey(chemical("hydrogen")),
                chemicalKey(chemical("oxygen")));
    }

    private static void withFixture(
            GameTestHelper helper,
            RecipeHolder<?> holder,
            StationSpec station,
            FixtureAssertion assertion
    ) {
        var level = helper.getLevel();
        var corePos = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlock(corePos, MagicStorage.STORAGE_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(
                corePos.south(),
                MagicStorage.STORAGE_UNIT_T1.get().defaultBlockState(),
                Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            if (!(level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core)) {
                helper.fail("Core not found");
                return;
            }
            var manager = level.getRecipeManager();
            List<RecipeHolder<?>> original = List.copyOf(manager.getRecipes());
            List<RecipeHolder<?>> registered = new ArrayList<>(original);
            registered.removeIf(recipe -> recipe.id().equals(holder.id()));
            registered.add(holder);
            manager.replaceRecipes(registered);
            try {
                core.rebuildNetwork(level);
                var player = helper.makeMockPlayer(GameType.SURVIVAL);
                var menu = new CraftingTerminalMenu(600, player.getInventory(), core);
                var context = new FixtureContext(level, core, player, menu, station);
                if (!installStation(context)) {
                    helper.fail("Could not install Mekanism station descriptor: " + station.itemId());
                    return;
                }
                if (!MagicStorage.RECIPE_FAMILY_REGISTRY.containsKey(station.descriptorId())) {
                    helper.fail("Mekanism recipe family was not registered: "
                            + station.descriptorId());
                    return;
                }
                assertion.run(context);
            } finally {
                manager.replaceRecipes(original);
            }
        });
    }

    private static boolean installStation(FixtureContext context) {
        Item stationItem = BuiltInRegistries.ITEM.get(context.station().itemId());
        ItemStack stationStack = new ItemStack(stationItem);
        if (stationStack.isEmpty()) return false;
        context.menu().clickMenuButton(context.player(), FUEL_PAGE_BUTTON);
        for (int index = CraftingTerminalMenu.MACHINE_SLOT_START;
             index < CraftingTerminalMenu.MACHINE_SLOT_START + CraftingTerminalMenu.MACHINE_SLOT_COUNT;
             index++) {
            var slot = context.menu().getSlot(index);
            if (!slot.isActive() || !slot.mayPlace(stationStack)) continue;
            slot.set(stationStack.copy());
            slot.setChanged();
            context.menu().clickMenuButton(context.player(), STORAGE_PAGE_BUTTON);
            return true;
        }
        context.menu().clickMenuButton(context.player(), STORAGE_PAGE_BUTTON);
        return false;
    }

    private static void addWork(FixtureContext context, int ticks) {
        for (int tick = 0; tick < ticks; tick++) context.core().tick();
    }

    private static long stationWork(FixtureContext context) {
        return context.core().getStationWork(context.station().descriptorId());
    }

    private static boolean select(FixtureContext context, ResourceLocation recipeId) {
        return context.menu().handleRecipeRequest(
                context.level(), recipeId, 1, CraftingDestination.NONE, context.player());
    }

    private static boolean craft(FixtureContext context, ResourceLocation recipeId) {
        return context.menu().handleRecipeRequest(
                context.level(), recipeId, 1, CraftingDestination.INVENTORY, context.player());
    }

    private static void seedItem(StorageCoreBlockEntity core, Item item, int count) {
        if (core.insertItem(new ItemStack(item, count)) != count) {
            throw new IllegalStateException("Could not seed " + item + " x" + count);
        }
    }

    private static void seedResource(
            StorageCoreBlockEntity core,
            StorageResourceKey key,
            long amount
    ) {
        if (core.insertResource(key, amount, Action.EXECUTE) != amount) {
            throw new IllegalStateException("Could not seed " + key + " x" + amount);
        }
    }

    private static long itemCount(StorageCoreBlockEntity core, Item item) {
        return core.getItemCount(ItemKey.of(new ItemStack(item)));
    }

    private static int inventoryCount(FixtureContext context, Item item) {
        int count = 0;
        for (int slot = 0; slot < context.player().getInventory().getContainerSize(); slot++) {
            ItemStack stack = context.player().getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static Holder<Chemical> chemical(String path) {
        return MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceKey.create(
                MekanismAPI.CHEMICAL_REGISTRY_NAME,
                ResourceLocation.fromNamespaceAndPath("mekanism", path))).orElseThrow();
    }

    private static StorageResourceKey chemicalKey(Holder<Chemical> chemical) {
        return StorageResourceKey.of(
                StorageResourceKindApi.CHEMICAL_KIND,
                chemical.unwrapKey().orElseThrow().location(),
                new CompoundTag());
    }

    private static StationSpec station(String path) {
        return new StationSpec(
                ResourceLocation.fromNamespaceAndPath("mekanism", path),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "mekanism_" + path));
    }

    private static ResourceLocation recipeId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MekanismFixtureMod.MODID, path);
    }

    private enum MissingResource {
        ITEM("item"),
        FLUID("fluid"),
        CHEMICAL("chemical"),
        ENERGY("energy"),
        STATION_WORK("station_work");

        private final String id;

        MissingResource(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }
    }

    @FunctionalInterface
    private interface FixtureAssertion {
        void run(FixtureContext context);
    }

    private record StationSpec(ResourceLocation itemId, ResourceLocation descriptorId) {
    }

    private record ReactionKeys(
            StorageResourceKey water,
            StorageResourceKey hydrogen,
            StorageResourceKey oxygen
    ) {
    }

    private record FixtureContext(
            net.minecraft.server.level.ServerLevel level,
            StorageCoreBlockEntity core,
            net.minecraft.world.entity.player.Player player,
            CraftingTerminalMenu menu,
            StationSpec station
    ) {
    }
}
