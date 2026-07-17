package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class SelfTest {
    private static int passed = 0;
    private static int failed = 0;

    static void runAll() {
        testItemKey();
        testAxeSyntheticDiscovery();
        testAxeEnergyContract();
        testFuelTable();
        testFuelAutoPriority();
        testRecipeEnergyTable();
        testRecipeAdapterFoundation();
        testRecipeAdapterRegistryContract();
        testBuiltInRecipeAdapterFamilies();
        testRecipeAdapterCandidateCoverage();
        testRecipeAdapterReloadIdentity();
        testEnergyType();
        testEnergyCost();
        testFuelValue();
        testEnergyTypeUniqueness();
        testSortMode();
        testSortOrder();
        testSearchMode();
        testTerminalSettingsPacketCodec();
        testSearchModeApply();
        testTerminalProfilesAndCycleDirection();
        testTerminalAmountFormatter();
        testTerminalDisplayStack();
        testTerminalEntryComparator();
        testAdaptiveTerminalLayout();

        MagicStorage.LOGGER.info("SelfTest: {} passed, {} failed, {} total",
                passed, failed, passed + failed);
        if (failed > 0) {
            MagicStorage.LOGGER.error("SelfTest: {} TESTS FAILED!", failed);
        }
    }

    private static void testItemKey() {
        var stack1 = new ItemStack(Items.DIAMOND_SWORD);
        var stack2 = new ItemStack(Items.DIAMOND_SWORD);
        var stack3 = new ItemStack(Items.IRON_SWORD);

        var key1 = ItemKey.of(stack1);
        var key2 = ItemKey.of(stack2);
        var key3 = ItemKey.of(stack3);
        var key1Again = ItemKey.of(stack1);

        assertTrue("same item = same key", key1.equals(key2));
        assertTrue("same item = same hashCode", key1.hashCode() == key2.hashCode());
        assertTrue("different item = different key", !key1.equals(key3));
        assertTrue("key is stable (same stack = same key)", key1.equals(key1Again));
        assertTrue("key = itself", key1.equals(key1));
        assertTrue("key != null", !key1.equals(null));
        assertTrue("primaryKey returns item", key1.item() == Items.DIAMOND_SWORD);
    }

    private static void testAxeSyntheticDiscovery() {
        ResourceLocation vanillaBlock = ResourceLocation.fromNamespaceAndPath("minecraft", "oak_log");
        ResourceLocation arbitraryModBlock = ResourceLocation.fromNamespaceAndPath("audit_mod", "hooked_log");

        assertTrue("axe discovery allows minecraft namespace blocks",
                AxeTransformationCatalog.isSyntheticDiscoveryAllowed(vanillaBlock));
        assertTrue("axe discovery rejects arbitrary mod namespace blocks",
                !AxeTransformationCatalog.isSyntheticDiscoveryAllowed(arbitraryModBlock));
    }

    private static void testAxeEnergyContract() {
        ItemStack pristine = new ItemStack(Items.IRON_AXE);
        ItemStack damaged = pristine.copy();
        damaged.setDamageValue(17);
        ItemStack broken = pristine.copy();
        broken.setDamageValue(broken.getMaxDamage());

        assertTrue("Axe Energy keeps exact remaining durability",
                AxeEnergy.remainingDurability(pristine) == pristine.getMaxDamage()
                        && AxeEnergy.remainingDurability(damaged) == damaged.getMaxDamage() - 17
                        && AxeEnergy.remainingDurability(broken) == 0);
        assertTrue("Axe Energy accepts axe actions and rejects unrelated tools",
                AxeEnergy.accepts(pristine) && !AxeEnergy.accepts(new ItemStack(Items.IRON_PICKAXE)));
        assertTrue("Unbreaking multiplier is exact level plus one",
                AxeEnergy.scaledFiniteValue(5, 2, 1) == 15);
        ItemStack unbreakable = pristine.copy();
        unbreakable.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        assertTrue("explicit Unbreakable becomes infinite Axe Energy", AxeEnergy.isInfinite(unbreakable));
    }

    private static void testEnergyCost() {
        var cost = new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200);
        assertTrue("energyCost processType", cost.processType() == EnergyType.SMELTING_ENERGY);
        assertTrue("energyCost processAmount", cost.processAmount() == 200);
        assertTrue("energyCost fuelType", cost.fuelType() == EnergyType.FURNACE_FUEL);
        assertTrue("energyCost fuelAmount", cost.fuelAmount() == 200);

        var cost2 = new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200);
        assertTrue("energyCost equals", cost.equals(cost2));
        assertTrue("energyCost hashCode", cost.hashCode() == cost2.hashCode());
    }

    private static void testFuelValue() {
        var fv = new FuelValue(EnergyType.FURNACE_FUEL, 1600);
        assertTrue("fuelValue pool", fv.pool() == EnergyType.FURNACE_FUEL);
        assertTrue("fuelValue amount", fv.valuePerItem() == 1600);
    }

    private static void testEnergyTypeUniqueness() {
        Set<String> ids = new HashSet<>();
        for (EnergyType type : EnergyType.values()) {
            assertTrue("unique id: " + type.getId(), ids.add(type.getId()));
        }
    }

    private static void testFuelTable() {
        assertTrue("glass bottle is not fuel", !FuelTable.isFuel(new ItemStack(Items.GLASS_BOTTLE)));
        assertTrue("glass bottle has no fuel values",
                FuelTable.getFuelValues(new ItemStack(Items.GLASS_BOTTLE)).isEmpty());
        assertTrue("potion is not fuel", !FuelTable.isFuel(new ItemStack(Items.POTION)));
        assertTrue("potion has no fuel values",
                FuelTable.getFuelValues(new ItemStack(Items.POTION)).isEmpty());
        assertTrue("blaze rod has explicit brew overlay", FuelTable.isFuel(new ItemStack(Items.BLAZE_ROD)));
        assertTrue("blaze rod brew overlay is 1200", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_ROD))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.BLAZE_FUEL && fv.valuePerItem() == 1200));
        assertTrue("blaze powder has explicit brew overlay", FuelTable.isFuel(new ItemStack(Items.BLAZE_POWDER)));
        assertTrue("blaze powder brew overlay is 600", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_POWDER))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.BLAZE_FUEL && fv.valuePerItem() == 600));
        assertTrue("stone is not fuel", !FuelTable.isFuel(new ItemStack(Items.STONE)));
        assertTrue("blaze rod has one explicit brew overlay", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_ROD))
                .stream().filter(fv -> fv.pool() == EnergyType.BLAZE_FUEL).count() == 1);
    }

    private static void testFuelAutoPriority() {
        assertTrue("retired bottle inputs have no automatic fuel target",
                FuelTable.getAutoFuelValue(new ItemStack(Items.GLASS_BOTTLE), type -> 0L) == null
                        && FuelTable.getAutoFuelValue(new ItemStack(Items.POTION), type -> 0L) == null);
        assertTrue("blaze fuel has two distinct suppliers",
                FuelTable.getSupplierCount(EnergyType.BLAZE_FUEL) == 2);

        FuelValue blazeRodAuto = FuelTable.getAutoFuelValue(new ItemStack(Items.BLAZE_ROD), type -> 0L);
        assertTrue("auto blaze rod prefers the pool with fewer fuel choices",
                blazeRodAuto != null && blazeRodAuto.pool() == EnergyType.BLAZE_FUEL);
        List<FuelValue> equalScarcity = List.of(
                new FuelValue(EnergyType.SMELTING_ENERGY, 1),
                new FuelValue(EnergyType.BLASTING_ENERGY, 1));
        FuelValue lowerAmount = FuelTable.selectAutoFuelValue(equalScarcity,
                type -> type == EnergyType.SMELTING_ENERGY ? 20L : 10L);
        assertTrue("equal scarcity prefers the lower accumulated pool",
                lowerAmount != null && lowerAmount.pool() == EnergyType.BLASTING_ENERGY);
        FuelValue enumTie = FuelTable.selectAutoFuelValue(equalScarcity, type -> 10L);
        assertTrue("equal scarcity and amount use stable enum order",
                enumTie != null && enumTie.pool() == EnergyType.SMELTING_ENERGY);
        assertTrue("non-fuel has no auto target",
                FuelTable.getAutoFuelValue(new ItemStack(Items.STONE), type -> 0L) == null);
    }

    private static void testRecipeEnergyTable() {
        var smeltCost = RecipeEnergyTable.getCost(new SmeltingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.DIRT), new ItemStack(Items.STONE), 0, 37));
        assertTrue("smelting needs smelting_energy", smeltCost.processType() == EnergyType.SMELTING_ENERGY);
        assertTrue("smelting process uses concrete cooking time", smeltCost.processAmount() == 37);
        assertTrue("smelting needs furnace_fuel", smeltCost.fuelType() == EnergyType.FURNACE_FUEL);
        assertTrue("smelting fuel uses concrete cooking time", smeltCost.fuelAmount() == 37);

        var blastCost = RecipeEnergyTable.getCost(new BlastingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.DIRT), new ItemStack(Items.STONE), 0, 23));
        assertTrue("blasting uses concrete cooking time", blastCost.processAmount() == 23);
        assertTrue("blasting uses blast energy", blastCost.processType() == EnergyType.BLASTING_ENERGY);

        var smokeCost = RecipeEnergyTable.getCost(new SmokingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.BEEF), new ItemStack(Items.COOKED_BEEF), 0, 19));
        assertTrue("smoking uses smoking_energy", smokeCost.processType() == EnergyType.SMOKING_ENERGY);
        assertTrue("smoking uses concrete cooking time", smokeCost.processAmount() == 19);

        var campCost = RecipeEnergyTable.getCost(new CampfireCookingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.BEEF), new ItemStack(Items.COOKED_BEEF), 0, 71));
        assertTrue("campfire uses campfire energy", campCost.processType() == EnergyType.CAMPFIRE_ENERGY);
        assertTrue("campfire uses concrete cooking time", campCost.processAmount() == 71);

        var crafting = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)));
        assertTrue("non-cooking recipe cost is null", RecipeEnergyTable.getCost(crafting) == null);
    }

    private static void testRecipeAdapterFoundation() {
        var holder = new net.minecraft.world.item.crafting.RecipeHolder<>(
                ResourceLocation.fromNamespaceAndPath("test_mod", "shapeless_adapter"),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT))));
        var match = BuiltInRecipeAdapters.registry().classify(holder).orElse(null);

        assertTrue("exact shapeless holders select the stable built-in adapter ID",
                match != null && match.adapterId().equals(BuiltInRecipeAdapters.SHAPELESS_ID));
    }

    private static void testRecipeAdapterRegistryContract() {
        RecipeAdapter later = testRecipeAdapter("later", 20);
        RecipeAdapter samePriorityZ = testRecipeAdapter("same_z", 10);
        RecipeAdapter samePriorityA = testRecipeAdapter("same_a", 10);
        RecipeAdapterRegistry registry = new RecipeAdapterRegistry(
                List.of(later, samePriorityZ, samePriorityA));
        List<ResourceLocation> orderedIds = registry.adapters().stream()
                .map(RecipeAdapter::id)
                .toList();
        var holder = new net.minecraft.world.item.crafting.RecipeHolder<>(
                ResourceLocation.fromNamespaceAndPath("test_mod", "priority"),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT))));

        assertTrue("adapter order is priority then stable ID",
                orderedIds.equals(List.of(samePriorityA.id(), samePriorityZ.id(), later.id())));
        assertTrue("adapters are keyed by stable ResourceLocation ID",
                registry.get(samePriorityA.id()).orElse(null) == samePriorityA
                        && registry.get(testRecipeId("missing_adapter")).isEmpty());
        assertTrue("classification uses deterministic first-match priority",
                registry.classify(holder).orElseThrow().adapterId().equals(samePriorityA.id()));

        boolean duplicateRejected = false;
        try {
            new RecipeAdapterRegistry(List.of(
                    testRecipeAdapter("duplicate", 0), testRecipeAdapter("duplicate", 1)));
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        assertTrue("duplicate adapter IDs are rejected", duplicateRejected);
    }

    private static RecipeAdapter testRecipeAdapter(String path, int priority) {
        return new RecipeAdapter() {
            private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test_mod", path);

            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean supports(net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
                return true;
            }

            @Override
            public RecipeCandidateIndex candidateIndex(
                    net.minecraft.world.item.crafting.RecipeHolder<?> holder
            ) {
                return RecipeCandidateIndex.exhaustive(List.of(new ItemStack(Items.DIRT)));
            }
        };
    }

    private static void testBuiltInRecipeAdapterFamilies() {
        List<RecipeHolder<?>> holders = List.of(
                new RecipeHolder<>(testRecipeId("shaped"), new ShapedRecipe(
                        "", CraftingBookCategory.MISC,
                        new ShapedRecipePattern(1, 1,
                                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)),
                                java.util.Optional.empty()),
                        new ItemStack(Items.STONE))),
                new RecipeHolder<>(testRecipeId("shapeless"), new ShapelessRecipe(
                        "", CraftingBookCategory.MISC, new ItemStack(Items.STONE),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)))),
                new RecipeHolder<>(testRecipeId("smelting"), new SmeltingRecipe(
                        "", CookingBookCategory.MISC, Ingredient.of(Items.DIRT),
                        new ItemStack(Items.STONE), 0, 200)),
                new RecipeHolder<>(testRecipeId("blasting"), new BlastingRecipe(
                        "", CookingBookCategory.MISC, Ingredient.of(Items.DIRT),
                        new ItemStack(Items.STONE), 0, 100)),
                new RecipeHolder<>(testRecipeId("smoking"), new SmokingRecipe(
                        "", CookingBookCategory.MISC, Ingredient.of(Items.BEEF),
                        new ItemStack(Items.COOKED_BEEF), 0, 100)),
                new RecipeHolder<>(testRecipeId("campfire"), new CampfireCookingRecipe(
                        "", CookingBookCategory.MISC, Ingredient.of(Items.BEEF),
                        new ItemStack(Items.COOKED_BEEF), 0, 600)),
                new RecipeHolder<>(testRecipeId("stonecutting"), new StonecutterRecipe(
                        "", Ingredient.of(Items.STONE), new ItemStack(Items.STONE_BRICKS))),
                new RecipeHolder<>(testRecipeId("smithing"), new SmithingTransformRecipe(
                        Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        Ingredient.of(Items.DIAMOND_SWORD),
                        Ingredient.of(Items.NETHERITE_INGOT),
                        new ItemStack(Items.NETHERITE_SWORD))),
                new RecipeHolder<>(testRecipeId("axe"), new AxeTransformationRecipe(
                        Ingredient.of(Items.OAK_LOG), new ItemStack(Items.STRIPPED_OAK_LOG),
                        ItemAbilities.AXE_STRIP))
        );
        List<ResourceLocation> expectedAdapterIds = List.of(
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "shaped_crafting"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "shapeless_crafting"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "smelting"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "blasting"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "smoking"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "campfire_cooking"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "stonecutting"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "smithing_transform"),
                ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "axe_transformation")
        );

        for (int index = 0; index < holders.size(); index++) {
            RecipeAdapterMatch match = BuiltInRecipeAdapters.registry()
                    .classify(holders.get(index)).orElse(null);
            assertTrue("exact built-in family selects adapter " + expectedAdapterIds.get(index),
                    match != null && match.adapterId().equals(expectedAdapterIds.get(index)));
        }
        assertTrue("built-in adapter IDs have a stable deterministic order",
                BuiltInRecipeAdapters.registry().adapters().stream()
                        .map(RecipeAdapter::id).toList().equals(expectedAdapterIds));

        ShapelessRecipe customFamily = new ShapelessRecipe(
                "", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT))) {
        };
        assertTrue("custom recipe classes remain unsupported without a registered exact adapter",
                BuiltInRecipeAdapters.registry().classify(
                        new RecipeHolder<>(testRecipeId("custom_family"), customFamily)).isEmpty());
    }

    private static ResourceLocation testRecipeId(String path) {
        return ResourceLocation.fromNamespaceAndPath("test_mod", path);
    }

    private static void testRecipeAdapterCandidateCoverage() {
        RecipeHolder<ShapelessRecipe> simple = new RecipeHolder<>(
                testRecipeId("simple_candidates"),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT))));
        ItemStack exactStone = new ItemStack(Items.STONE);
        exactStone.set(DataComponents.CUSTOM_NAME, Component.literal("exact"));
        Ingredient nonSimple = DataComponentIngredient.of(true, exactStone);
        RecipeHolder<ShapelessRecipe> componentExact = new RecipeHolder<>(
                testRecipeId("non_exhaustive_candidates"),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.EMERALD),
                        NonNullList.of(Ingredient.EMPTY, nonSimple)));

        RecipeCandidateIndex simpleIndex = BuiltInRecipeAdapters.registry()
                .classify(simple).orElseThrow().candidateIndex();
        RecipeCandidateIndex componentIndex = BuiltInRecipeAdapters.registry()
                .classify(componentExact).orElseThrow().candidateIndex();

        assertTrue("simple ingredient representatives are explicitly exhaustive",
                simpleIndex.coverage() == RecipeCandidateIndex.Coverage.EXHAUSTIVE
                        && simpleIndex.representatives().stream().anyMatch(stack -> stack.is(Items.DIRT)));
        assertTrue("non-simple ingredient representatives are explicitly non-exhaustive",
                componentIndex.coverage() == RecipeCandidateIndex.Coverage.NON_EXHAUSTIVE
                        && componentIndex.representatives().stream()
                        .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, exactStone)));
    }

    private static void testRecipeAdapterReloadIdentity() {
        ResourceLocation recipeId = testRecipeId("reload_identity");
        RecipeHolder<ShapelessRecipe> original = new RecipeHolder<>(
                recipeId,
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT))));
        RecipeAdapterMatch originalMatch = BuiltInRecipeAdapters.registry()
                .classify(original).orElseThrow();
        RecipeHolder<ShapelessRecipe> replacement = new RecipeHolder<>(
                recipeId,
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STONE))));
        RecipeAdapterMatch replacementMatch = BuiltInRecipeAdapters.registry()
                .classify(replacement).orElseThrow();

        assertTrue("adapter matches are bound to the exact holder identity",
                originalMatch.isCurrentHolder(original)
                        && !originalMatch.isCurrentHolder(replacement)
                        && replacementMatch.isCurrentHolder(replacement));
        assertTrue("same-ID reloads are classified from the replacement recipe instance",
                replacementMatch.candidateIndex().representatives().stream()
                        .anyMatch(stack -> stack.is(Items.STONE))
                        && replacementMatch.candidateIndex().representatives().stream()
                        .noneMatch(stack -> stack.is(Items.DIRT)));

        ShapelessRecipe replacementCustomFamily = new ShapelessRecipe(
                "", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STONE))) {
        };
        assertTrue("same-ID reloads do not inherit stale adapter support",
                BuiltInRecipeAdapters.registry().classify(
                        new RecipeHolder<>(recipeId, replacementCustomFamily)).isEmpty());
    }

    private static void testEnergyType() {
        assertTrue("smelting is machine-generated", EnergyType.SMELTING_ENERGY.isMachineGenerated());
        assertTrue("blasting is machine-generated", EnergyType.BLASTING_ENERGY.isMachineGenerated());
        assertTrue("furnace_fuel is not machine-generated", !EnergyType.FURNACE_FUEL.isMachineGenerated());
        assertTrue("blaze_fuel is not machine-generated", !EnergyType.BLAZE_FUEL.isMachineGenerated());
        assertTrue("bottle fuel energy type is retired", java.util.Arrays.stream(EnergyType.values())
                .noneMatch(type -> type.name().equals("BOTTLE_FUEL") || type.getId().equals("bottle_fuel")));
        assertTrue("7 types total", EnergyType.values().length == 7);
        assertTrue("smelting id", EnergyType.SMELTING_ENERGY.getId().equals("smelting_energy"));
        assertTrue("every energy type has a representative item",
                java.util.Arrays.stream(EnergyType.values())
                        .allMatch(type -> !type.representativeStack().isEmpty()));
        assertTrue("every representative item is a single display icon",
                java.util.Arrays.stream(EnergyType.values())
                        .allMatch(type -> type.representativeStack().getCount() == 1));
        assertTrue("Smelting Energy uses Furnace as its representative item",
                EnergyType.SMELTING_ENERGY.representativeStack().is(Items.FURNACE));
        assertTrue("Blasting Energy uses Blast Furnace as its representative item",
                EnergyType.BLASTING_ENERGY.representativeStack().is(Items.BLAST_FURNACE));
        assertTrue("Smoking Energy uses Smoker as its representative item",
                EnergyType.SMOKING_ENERGY.representativeStack().is(Items.SMOKER));
        assertTrue("Campfire Energy uses Campfire as its representative item",
                EnergyType.CAMPFIRE_ENERGY.representativeStack().is(Items.CAMPFIRE));
        assertTrue("Brew Energy uses Brewing Stand as its representative item",
                EnergyType.BREW_ENERGY.representativeStack().is(Items.BREWING_STAND));
        assertTrue("Fuel uses coal as its representative item",
                EnergyType.FURNACE_FUEL.representativeStack().is(Items.COAL));
        assertTrue("Brew Energy uses blaze rod as its representative item",
                EnergyType.BLAZE_FUEL.representativeStack().is(Items.BLAZE_ROD));
        assertTrue("9 station and consumable mappings", MachineEnergyTable.size() == 9);
        assertTrue("Furnace maps to smelting", MachineEnergyTable.get(0).representativeStack().is(Items.FURNACE)
                && MachineEnergyTable.get(0).energyType() == EnergyType.SMELTING_ENERGY
                && MachineEnergyTable.get(0).accepts(new ItemStack(Items.FURNACE)));
        assertTrue("Blast Furnace maps to blasting", MachineEnergyTable.get(1).representativeStack().is(Items.BLAST_FURNACE)
                && MachineEnergyTable.get(1).energyType() == EnergyType.BLASTING_ENERGY);
        assertTrue("Smoker maps to smoking", MachineEnergyTable.get(2).representativeStack().is(Items.SMOKER)
                && MachineEnergyTable.get(2).energyType() == EnergyType.SMOKING_ENERGY);
        assertTrue("Campfire maps to campfire", MachineEnergyTable.get(3).representativeStack().is(Items.CAMPFIRE)
                && MachineEnergyTable.get(3).energyType() == EnergyType.CAMPFIRE_ENERGY);
        assertTrue("Brewing Stand maps to brew", MachineEnergyTable.get(4).representativeStack().is(Items.BREWING_STAND)
                && MachineEnergyTable.get(4).energyType() == EnergyType.BREW_ENERGY);
        assertTrue("Crafting Table maps to crafting station slot",
                MachineEnergyTable.get(MachineEnergyTable.CRAFTING_TABLE_SLOT).representativeStack().is(Items.CRAFTING_TABLE));
        assertTrue("Stonecutter maps to stonecutting station slot",
                MachineEnergyTable.get(MachineEnergyTable.STONECUTTER_SLOT).representativeStack().is(Items.STONECUTTER));
        assertTrue("Smithing Table maps to smithing station slot",
                MachineEnergyTable.get(MachineEnergyTable.SMITHING_TABLE_SLOT).representativeStack().is(Items.SMITHING_TABLE));
        assertTrue("Axe slot accepts axes and rejects pickaxes",
                MachineEnergyTable.get(MachineEnergyTable.AXE_SLOT).accepts(new ItemStack(Items.DIAMOND_AXE))
                        && !MachineEnergyTable.get(MachineEnergyTable.AXE_SLOT)
                                .accepts(new ItemStack(Items.DIAMOND_PICKAXE)));
        assertTrue("process machines stack",
                MachineEnergyTable.get(MachineEnergyTable.FURNACE_SLOT).category()
                        == MachineEnergyTable.Category.PROCESS
                        && MachineEnergyTable.get(MachineEnergyTable.FURNACE_SLOT).maxInstalledCount() == 64);
        assertTrue("instant stations install exactly one",
                MachineEnergyTable.get(MachineEnergyTable.CRAFTING_TABLE_SLOT).category()
                        == MachineEnergyTable.Category.INSTANT
                        && MachineEnergyTable.get(MachineEnergyTable.CRAFTING_TABLE_SLOT).maxInstalledCount() == 1);
        assertTrue("axes are consumable energy input, not installed stations",
                MachineEnergyTable.get(MachineEnergyTable.AXE_SLOT).category()
                        == MachineEnergyTable.Category.CONSUMABLE
                        && MachineEnergyTable.get(MachineEnergyTable.AXE_SLOT).maxInstalledCount() == 0);
        assertTrue("machine rate is one per installed block", MachineEnergyTable.get(0).energyPerTick() == 1);
    }

    private static void testTerminalAmountFormatter() {
        assertTrue("slot amount zero stays exact", TerminalAmountFormatter.formatCompact(0).equals("0"));
        assertTrue("slot amount 999 stays exact", TerminalAmountFormatter.formatCompact(999).equals("999"));
        assertTrue("slot amount 1000 uses K", TerminalAmountFormatter.formatCompact(1_000).equals("1K"));
        assertTrue("slot amount 1500 keeps one decimal", TerminalAmountFormatter.formatCompact(1_500).equals("1.5K"));
        assertTrue("slot amount floors instead of overstating",
                TerminalAmountFormatter.formatCompact(8_192).equals("8.1K"));
        assertTrue("slot amount removes decimal at 100K",
                TerminalAmountFormatter.formatCompact(100_000).equals("100K"));
        assertTrue("slot amount 1M uses M", TerminalAmountFormatter.formatCompact(1_000_000).equals("1M"));
        assertTrue("slot amount 1.5M keeps one decimal",
                TerminalAmountFormatter.formatCompact(1_500_000).equals("1.5M"));
        assertTrue("slot amount supports exa scale",
                TerminalAmountFormatter.formatCompact(Long.MAX_VALUE).equals("9.2E"));
        float fixedScale = TerminalAmountFormatter.scaleForSlot(String::length, 4);
        assertTrue("one slot scale is derived from the widest permitted compact shape",
                Math.abs(fixedScale - 0.8F) < 0.0001F);
    }

    private static void testTerminalDisplayStack() {
        ItemStack original = new ItemStack(Items.DIAMOND_SWORD, 7);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("Original"));
        CompoundTag originalData = new CompoundTag();
        originalData.putString("owner", "test");
        original.set(DataComponents.CUSTOM_DATA, CustomData.of(originalData));

        ItemStack zero = TerminalDisplayStack.create(original, 0);
        ItemStack stackSized = TerminalDisplayStack.create(original, 64);
        ItemStack aboveInt = TerminalDisplayStack.create(original, (long) Integer.MAX_VALUE + 1);
        ItemStack huge = TerminalDisplayStack.create(original, Long.MAX_VALUE);
        assertTrue("zero display amount remains a visible one-count stack",
                !zero.isEmpty() && zero.getCount() == 1 && TerminalDisplayStack.amount(zero) == 0);
        assertTrue("display amount preserves exact long",
                stackSized.getCount() == 1 && TerminalDisplayStack.amount(stackSized) == 64
                        && aboveInt.getCount() == 1
                        && TerminalDisplayStack.amount(aboveInt) == (long) Integer.MAX_VALUE + 1
                        && huge.getCount() == 1 && TerminalDisplayStack.amount(huge) == Long.MAX_VALUE);
        assertTrue("display metadata is detected", TerminalDisplayStack.isDisplay(huge));
        assertTrue("plain stacks use their real count",
                TerminalDisplayStack.amount(original) == 7 && !TerminalDisplayStack.isDisplay(original));

        ItemStack stripped = TerminalDisplayStack.strip(huge);
        assertTrue("stripping display metadata preserves item components",
                ItemStack.isSameItemSameComponents(original, stripped));
        assertTrue("stripping display metadata removes only the display marker",
                !TerminalDisplayStack.isDisplay(stripped)
                        && stripped.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag().getString("owner").equals("test"));
        assertTrue("ItemKey ignores display metadata",
                ItemKey.of(huge).equals(ItemKey.of(original)));
        assertTrue("ItemKey never recreates display metadata",
                !TerminalDisplayStack.isDisplay(ItemKey.of(huge).toStack(1)));

        ItemStack noCustomData = new ItemStack(Items.STONE);
        ItemStack strippedNoCustomData = TerminalDisplayStack.strip(
                TerminalDisplayStack.create(noCustomData, 64));
        assertTrue("stripping the only marker removes empty custom data",
                !strippedNoCustomData.has(DataComponents.CUSTOM_DATA));
    }

    private static void testTerminalEntryComparator() {
        ItemStack stone = TerminalDisplayStack.create(new ItemStack(Items.STONE), 5);
        ItemStack apple = TerminalDisplayStack.create(new ItemStack(Items.APPLE), 20);
        ItemStack diamond = TerminalDisplayStack.create(new ItemStack(Items.DIAMOND), 10);

        List<ItemStack> quantity = new ArrayList<>(List.of(apple, diamond, stone));
        quantity.sort(TerminalEntryComparator.forMode(SortMode.QUANTITY, SortOrder.ASCENDING));
        assertTrue("quantity sorting uses exact display amounts",
                quantity.get(0).is(Items.STONE)
                        && quantity.get(1).is(Items.DIAMOND)
                        && quantity.get(2).is(Items.APPLE));

        List<ItemStack> descendingId = new ArrayList<>(List.of(stone, apple, diamond));
        descendingId.sort(TerminalEntryComparator.forMode(SortMode.ID, SortOrder.DESCENDING));
        assertTrue("ID sorting compares the complete identifier and reverses deterministically",
                descendingId.get(0).is(Items.STONE)
                        && descendingId.get(1).is(Items.DIAMOND)
                        && descendingId.get(2).is(Items.APPLE));

        ItemStack alpha = TerminalDisplayStack.create(new ItemStack(Items.STONE), 5);
        alpha.set(DataComponents.CUSTOM_NAME, Component.literal("Alpha"));
        ItemStack beta = TerminalDisplayStack.create(new ItemStack(Items.STONE), 5);
        beta.set(DataComponents.CUSTOM_NAME, Component.literal("Beta"));
        List<ItemStack> variantOrder = new ArrayList<>(List.of(beta, alpha));
        List<ItemStack> reversedInput = new ArrayList<>(List.of(alpha, beta));
        variantOrder.sort(TerminalEntryComparator.forMode(SortMode.ID, SortOrder.ASCENDING));
        reversedInput.sort(TerminalEntryComparator.forMode(SortMode.ID, SortOrder.ASCENDING));
        assertTrue("component variants have a deterministic final tie-breaker",
                ItemKey.of(variantOrder.get(0)).equals(ItemKey.of(reversedInput.get(0)))
                        && ItemKey.of(variantOrder.get(1)).equals(ItemKey.of(reversedInput.get(1))));
    }

    private static void testSortMode() {
        assertTrue("SortMode has 4 values", SortMode.values().length == 4);
        assertTrue("SortMode NAME ordinal 0", SortMode.NAME.ordinal() == 0);
        assertTrue("SortMode QUANTITY ordinal 1", SortMode.QUANTITY.ordinal() == 1);
        assertTrue("SortMode MOD ordinal 2", SortMode.values()[2].name().equals("MOD"));
        assertTrue("SortMode ID ordinal 3", SortMode.ID.ordinal() == 3);
        assertTrue("NAME.next() -> QUANTITY", SortMode.NAME.next() == SortMode.QUANTITY);
        assertTrue("QUANTITY.next() -> MOD", SortMode.QUANTITY.next().name().equals("MOD"));
        assertTrue("MOD.next() -> ID", SortMode.values()[2].next() == SortMode.ID);
        assertTrue("ID.next() -> NAME", SortMode.ID.next() == SortMode.NAME);
        assertTrue("NAME.previous() -> ID", SortMode.NAME.previous() == SortMode.ID);
        assertTrue("QUANTITY.previous() -> NAME", SortMode.QUANTITY.previous() == SortMode.NAME);
        assertTrue("MOD.previous() -> QUANTITY", SortMode.MOD.previous() == SortMode.QUANTITY);
        assertTrue("ID.previous() -> MOD", SortMode.ID.previous() == SortMode.MOD);
    }

    private static void testSortOrder() {
        assertTrue("SortOrder has 2 values", SortOrder.values().length == 2);
        assertTrue("SortOrder ASCENDING ordinal 0", SortOrder.ASCENDING.ordinal() == 0);
        assertTrue("SortOrder DESCENDING ordinal 1", SortOrder.DESCENDING.ordinal() == 1);
        assertTrue("ASCENDING toggle -> DESCENDING",
                SortOrder.toggle(SortOrder.ASCENDING) == SortOrder.DESCENDING);
        assertTrue("DESCENDING toggle -> ASCENDING",
                SortOrder.toggle(SortOrder.DESCENDING) == SortOrder.ASCENDING);
    }

    private static void testSearchMode() {
        assertTrue("SearchMode has 3 values", SearchMode.values().length == 3);
        assertTrue("SearchMode NORMAL ordinal 0", SearchMode.NORMAL.ordinal() == 0);
        assertTrue("SearchMode TAG ordinal 1", SearchMode.TAG.ordinal() == 1);
        assertTrue("SearchMode MOD ordinal 2", SearchMode.MOD.ordinal() == 2);
        assertTrue("NORMAL.next() -> TAG", SearchMode.NORMAL.next() == SearchMode.TAG);
        assertTrue("TAG.next() -> MOD", SearchMode.TAG.next() == SearchMode.MOD);
        assertTrue("MOD.next() -> NORMAL", SearchMode.MOD.next() == SearchMode.NORMAL);
        assertTrue("NORMAL.previous() -> MOD", SearchMode.NORMAL.previous() == SearchMode.MOD);
        assertTrue("TAG.previous() -> NORMAL", SearchMode.TAG.previous() == SearchMode.NORMAL);
        assertTrue("MOD.previous() -> TAG", SearchMode.MOD.previous() == SearchMode.TAG);
    }

    private static void testTerminalProfilesAndCycleDirection() {
        assertTrue("Storage profile is the reduced terminal",
                !TerminalProfile.STORAGE.supports(TerminalProfile.Capability.PAGES)
                        && !TerminalProfile.STORAGE.supports(TerminalProfile.Capability.RECIPE_WORKSPACE)
                        && TerminalProfile.STORAGE.itemRailGroups().equals(List.of(3)));
        assertTrue("Crafting profile composes page, recipe, Fuel, source, and output capabilities",
                TerminalProfile.CRAFTING.supports(TerminalProfile.Capability.PAGES)
                        && TerminalProfile.CRAFTING.supports(TerminalProfile.Capability.RECIPE_WORKSPACE)
                        && TerminalProfile.CRAFTING.supports(TerminalProfile.Capability.FUEL)
                        && TerminalProfile.CRAFTING.supports(TerminalProfile.Capability.PLAYER_INVENTORY_SOURCE)
                        && TerminalProfile.CRAFTING.supports(TerminalProfile.Capability.OUTPUT_DESTINATION)
                        && TerminalProfile.CRAFTING.playerInventorySourceIndex() == 6
                        && TerminalProfile.CRAFTING.outputDestinationIndex() == 7
                        && TerminalProfile.CRAFTING.itemRailGroups().equals(List.of(3, 3, 2))
                        && TerminalProfile.CRAFTING.fuelRailGroups().equals(List.of(3)));
        assertTrue("terminal controls use an 18px hit box and 16px icon canvas",
                TerminalLayout.CONTROL_SIZE == 18 && TerminalLayout.ICON_CANVAS_SIZE == 16);
        assertTrue("left click selects next",
                TerminalCycleDirection.fromMouseButton(0) == TerminalCycleDirection.NEXT);
        assertTrue("right click selects previous",
                TerminalCycleDirection.fromMouseButton(1) == TerminalCycleDirection.PREVIOUS);
        assertTrue("wheel down selects next",
                TerminalCycleDirection.fromScroll(-1.0) == TerminalCycleDirection.NEXT);
        assertTrue("wheel up selects previous",
                TerminalCycleDirection.fromScroll(1.0) == TerminalCycleDirection.PREVIOUS);
    }

    private static void testTerminalSettingsPacketCodec() {
        var original = new TerminalSettingsPacket(123, 6);
        assertTrue("packet containerId 123", original.containerId() == 123);
        assertTrue("packet visibleRows 6", original.visibleRows() == 6);

        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(original.containerId());
        buf.writeVarInt(original.visibleRows());
        assertTrue("codec encode order containerId", buf.readVarInt() == 123);
        assertTrue("codec encode order visibleRows", buf.readVarInt() == 6);

        var extreme = new TerminalSettingsPacket(42, 9);
        var buf2 = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf2.writeVarInt(extreme.containerId());
        buf2.writeVarInt(extreme.visibleRows());
        assertTrue("extreme encode containerId", buf2.readVarInt() == 42);
        assertTrue("extreme encode visibleRows", buf2.readVarInt() == 9);
    }

    private static void testSearchModeApply() {
        assertTrue("NORMAL keeps raw text",
                SearchMode.NORMAL.apply("stone").equals("stone"));
        assertTrue("NORMAL keeps empty text",
                SearchMode.NORMAL.apply("").isEmpty());
        assertTrue("TAG prepends #",
                SearchMode.TAG.apply("logs").equals("#logs"));
        assertTrue("TAG does not double-prefix #",
                SearchMode.TAG.apply("#logs").equals("#logs"));
        assertTrue("MOD prepends @",
                SearchMode.MOD.apply("minecraft").equals("@minecraft"));
        assertTrue("MOD does not double-prefix @",
                SearchMode.MOD.apply("@minecraft").equals("@minecraft"));
        assertTrue("TAG empty text stays empty",
                SearchMode.TAG.apply("").isEmpty());
        assertTrue("MOD empty text stays empty",
                SearchMode.MOD.apply("").isEmpty());
    }

    private static void testAdaptiveTerminalLayout() {
        var counts = new TerminalLayout.FuelDescriptorCounts(4, 5, 3, 3);
        var narrow = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 320, 240, counts);
        assertTrue("crafting geometry retains its terminal profile",
                narrow.profile() == TerminalProfile.CRAFTING);
        assertTrue("320x240 crafting uses narrow fallback", !narrow.wide());
        assertTrue("narrow fallback fits the complete viewport", narrow.imageHeight() <= 240);
        assertTrue("narrow grid follows nine-column slot rhythm",
                narrow.itemGrid().width() == 9 * TerminalLayout.SLOT_SIZE
                        && narrow.itemGrid().height() == narrow.visibleRows() * TerminalLayout.SLOT_SIZE);
        assertTrue("narrow recipe workspace follows item grid",
                narrow.workspace().y() >= narrow.itemGrid().bottom()
                        && narrow.workspace().bottom() <= narrow.playerInventory().y());
        assertTrue("narrow player inventory fits inside frame",
                narrow.playerInventory().bottom() <= narrow.imageHeight());
        assertFuelCategoryGeometry("narrow", narrow, counts);
        assertRecipeGeometry("narrow", narrow);

        var sideBySide = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 423, 291, counts);
        assertTrue("guiScale-4 fullscreen width uses side-by-side layout", sideBySide.wide());
        assertTrue("side-by-side frame shrinks within its supported range",
                sideBySide.imageWidth() == 367);
        assertTrue("side-by-side workspace remains usable", sideBySide.workspace().width() >= 162);
        assertTrue("side-by-side layout reserves vertical breathing room",
                sideBySide.imageHeight() <= 291);
        assertTrue("side-by-side workspace sits right of item grid",
                sideBySide.workspace().x() >= sideBySide.scrollbar().right());
        assertTrue("side-by-side boundary is based on complete usable width",
                !TerminalLayout.forProfile(TerminalProfile.CRAFTING, 415, 291, counts).wide()
                        && TerminalLayout.forProfile(TerminalProfile.CRAFTING, 416, 291, counts).wide());
        assertFuelCategoryGeometry("side-by-side", sideBySide, counts);
        assertRecipeGeometry("side-by-side", sideBySide);
        assertTrue("page tabs are visually separated from item controls",
                sideBySide.railButtons().get(3).y() - sideBySide.railButtons().get(2).bottom() >= 6);
        assertTrue("Fuel rail contains only the three page tabs",
                sideBySide.fuelRailButtons().size() == 3);
        assertTrue("Fuel popup includes Auto plus every server-approved target",
                sideBySide.fuelTargetPopup().itemCount() == counts.fuelTargetCount());
        assertTrue("Fuel popup rows are descriptor-driven and bounded",
                sideBySide.fuelTargetPopup().rows(0).size() == counts.fuelTargetCount()
                        && rectanglesDoNotOverlap(sideBySide.fuelTargetPopup().rows(0)));
        assertTrue("Fuel popup never overlaps the left control rail",
                !sideBySide.fuelTargetPopup().bounds().overlaps(sideBySide.railPanel()));

        TerminalLayout.Rect sampleFlowCell = new TerminalLayout.Rect(20, 30, 72, 28);
        assertTrue("station hover geometry is the upper centered 18px slot",
                TerminalLayout.fuelSlot(sampleFlowCell).equals(
                        new TerminalLayout.Rect(47, 31,
                                TerminalLayout.SLOT_SIZE, TerminalLayout.SLOT_SIZE)));
        assertTrue("reserve hover geometry is the upper centered 16px icon",
                TerminalLayout.fuelIcon(sampleFlowCell).equals(
                        new TerminalLayout.Rect(48, 31,
                                TerminalLayout.ICON_CANVAS_SIZE, TerminalLayout.ICON_CANVAS_SIZE)));
        assertTrue("Fuel amount geometry occupies the lower line",
                TerminalLayout.fuelAmountBounds(sampleFlowCell).equals(
                        new TerminalLayout.Rect(21, 49, 70, 9)));
        assertTrue("Fuel slot and amount never overlap",
                !TerminalLayout.fuelSlot(sampleFlowCell).overlaps(
                        TerminalLayout.fuelAmountBounds(sampleFlowCell)));

        var overflowCounts = new TerminalLayout.FuelDescriptorCounts(64, 64, 64, 61);
        var overflow = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 423, 291, overflowCounts);
        assertTrue("large Fuel target lists expose a bounded popup viewport",
                overflow.fuelTargetPopup().capacity() > 0
                        && overflow.fuelTargetPopup().capacity() <= 6
                        && overflow.fuelTargetPopup().itemCount() == 61);
        assertFullWidthLastPage("consumables", overflow.consumablesGrid());
        assertFullWidthLastPage("timed stations", overflow.timedStationsGrid());
        assertFullWidthLastPage("instant stations", overflow.instantStationsGrid());
        assertTrue("descriptor growth preserves the inventory-side type-capacity panel",
                overflow.fuelStatus().equals(sideBySide.fuelStatus())
                        && overflow.instantStationsGrid().bounds().equals(
                        sideBySide.instantStationsGrid().bounds())
                        && overflow.consumablesGrid().pageCount() > 1
                        && overflow.timedStationsGrid().pageCount() > 1
                        && overflow.instantStationsGrid().pageCount() > 1);
        for (int page = 0; page < overflow.instantStationsGrid().pageCount(); page++) {
            for (TerminalLayout.Rect cell : overflow.instantStationsGrid().cells(page)) {
                assertTrue("paged Instant Stations never enter the inventory-side type-capacity panel",
                        !cell.overlaps(overflow.fuelStatus()));
            }
        }

        for (int height = 240; height <= 600; height++) {
            for (int width : new int[]{320, 415, 416, 423, 480, 854}) {
                var geometry = TerminalLayout.forProfile(
                        TerminalProfile.CRAFTING, width, height, counts);
                int left = geometry.centeredFrameLeft(width);
                int top = (height - geometry.imageHeight()) / 2;
                assertTrue("crafting frame stays onscreen at " + width + "x" + height,
                        left >= 0 && top >= 0 && left + geometry.imageWidth() <= width
                                && top + geometry.imageHeight() <= height);
                assertTrue("crafting rail stays onscreen at " + width + "x" + height,
                        left + geometry.railPanel().x() >= 0
                                && top + geometry.railPanel().y() >= 0
                                && top + geometry.railPanel().bottom() <= height);
                int groupLeft = left + geometry.railPanel().x();
                int groupRight = left + geometry.imageWidth();
                assertTrue("crafting rail+frame group is centered at " + width + "x" + height,
                        Math.abs(groupLeft - (width - groupRight)) <= 1);
                if (geometry.wide()) {
                    assertTrue("side-by-side group keeps horizontal breathing room at "
                                    + width + "x" + height,
                            groupLeft >= 16 && width - groupRight >= 16);
                }
                assertTrue("crafting row count stays supported at " + width + "x" + height,
                        geometry.visibleRows() >= 1 && geometry.visibleRows() <= 9);
                assertTrue("crafting exclusion covers frame and rail at " + width + "x" + height,
                        geometry.exclusionRects().size() == 2
                                && geometry.exclusionRects().get(1).equals(geometry.railPanel()));
                assertTrue("crafting rail buttons do not overlap at " + width + "x" + height,
                        rectanglesDoNotOverlap(geometry.railButtons()));
                assertFuelCategoryGeometry(width + "x" + height, geometry, counts);
                assertRecipeGeometry(width + "x" + height, geometry);
            }
        }

        for (int height = 240; height <= 600; height++) {
            var geometry = TerminalLayout.forProfile(
                    TerminalProfile.STORAGE, 320, height,
                    TerminalLayout.FuelDescriptorCounts.none());
            assertTrue("storage geometry retains its terminal profile",
                    geometry.profile() == TerminalProfile.STORAGE);
            assertTrue("storage frame stays onscreen at height " + height,
                    geometry.imageHeight() <= height);
            assertTrue("storage rows stay 3..9 at height " + height,
                    geometry.visibleRows() >= 3 && geometry.visibleRows() <= 9);
            assertTrue("storage search ends before scrollbar at height " + height,
                    geometry.searchBackground().right() <= geometry.scrollbar().x());
            int left = geometry.centeredFrameLeft(320);
            int groupLeft = left + geometry.railPanel().x();
            int groupRight = left + geometry.imageWidth();
            assertTrue("storage rail+frame group is centered at height " + height,
                    Math.abs(groupLeft - (320 - groupRight)) <= 1);
        }
    }

    private static void assertFuelCategoryGeometry(
            String label,
            TerminalLayout.Geometry geometry,
            TerminalLayout.FuelDescriptorCounts counts
    ) {
        List<TerminalLayout.Rect> panels = List.of(
                geometry.consumablesPanel(),
                geometry.timedStationsPanel(),
                geometry.instantStationsPanel());
        assertTrue(label + " Fuel category panels do not overlap", rectanglesDoNotOverlap(panels));
        assertTrue(label + " Fuel category panels reserve the player-inventory label band",
                panels.getLast().bottom() == geometry.playerInventory().y() - 13);
        assertTrue(label + " Fuel category panels fill from terminal content to inventory",
                panels.getFirst().y() == TerminalLayout.TOP_HEIGHT
                        && panels.getFirst().height() >= 51
                        && panels.get(1).height() >= 32
                        && panels.getLast().height() >= 32);
        for (TerminalLayout.Rect panel : panels) {
            assertTrue(label + " Fuel category panels use full inner width",
                    panel.x() == 8 && panel.width() == geometry.imageWidth() - 16);
        }
        assertTrue(label + " Fuel category panel order is semantic",
                geometry.consumablesPanel().bottom() <= geometry.timedStationsPanel().y()
                        && geometry.timedStationsPanel().bottom() <= geometry.instantStationsPanel().y());
        assertPagedFlowGrid(label + " consumables", geometry.consumablesGrid(), counts.consumableCount());
        assertPagedFlowGrid(label + " timed stations", geometry.timedStationsGrid(), counts.timedStationCount());
        assertPagedFlowGrid(label + " instant stations", geometry.instantStationsGrid(), counts.instantStationCount());
        assertTrue(label + " Fuel rows reserve bounded left category labels",
                geometry.consumablesGrid().bounds().x() >= geometry.consumablesPanel().x() + 68
                        && geometry.timedStationsGrid().bounds().x()
                        >= geometry.timedStationsPanel().x() + 68
                        && geometry.instantStationsGrid().bounds().x()
                        >= geometry.instantStationsPanel().x() + 68);
        assertTrue(label + " Fuel input is the first consumable slot",
                geometry.fuelInput().equals(
                        TerminalLayout.fuelSlot(geometry.consumablesGrid().cells().getFirst())));
        assertTrue(label + " Fuel target selector stays in Consumables header",
                geometry.fuelTargetSelector().x() >= geometry.consumablesPanel().x()
                        && geometry.fuelTargetSelector().right() <= geometry.consumablesPanel().right()
                        && geometry.fuelTargetSelector().y() >= geometry.consumablesPanel().y()
                        && geometry.fuelTargetSelector().bottom()
                        <= geometry.consumablesGrid().bounds().y());
        assertTrue(label + " Fuel target selector is a compact control",
                geometry.fuelTargetSelector().width() <= 96);
        assertTrue(label + " type capacity is an independent panel right of player inventory",
                geometry.fuelStatus().x() == geometry.playerInventory().right() + 2
                        && geometry.fuelStatus().right() == geometry.imageWidth() - 8
                        && geometry.fuelStatus().y() == geometry.playerInventory().y()
                        && geometry.fuelStatus().bottom() == geometry.playerInventory().bottom()
                        && !geometry.fuelStatus().overlaps(geometry.playerInventory())
                        && !geometry.fuelStatus().overlaps(geometry.instantStationsPanel()));
        assertTrue(label + " type capacity keeps icon above its amount",
                !TerminalLayout.fuelIcon(geometry.fuelStatus()).overlaps(
                        TerminalLayout.fuelAmountBounds(geometry.fuelStatus())));
        for (TerminalLayout.FlowGrid grid : List.of(
                geometry.consumablesGrid(),
                geometry.timedStationsGrid(),
                geometry.instantStationsGrid())) {
            for (TerminalLayout.Rect cell : grid.cells()) {
                TerminalLayout.Rect slot = TerminalLayout.fuelSlot(cell);
                TerminalLayout.Rect icon = TerminalLayout.fuelIcon(cell);
                TerminalLayout.Rect amount = TerminalLayout.fuelAmountBounds(cell);
                assertTrue(label + " Fuel slots stay above amounts",
                        !slot.overlaps(amount));
                assertTrue(label + " Fuel icons stay above amounts",
                        !icon.overlaps(amount));
                assertTrue(label + " Fuel vertical content stays inside its cell",
                        slot.x() >= cell.x() && slot.y() >= cell.y()
                                && slot.right() <= cell.right() && slot.bottom() <= cell.bottom()
                                && icon.x() >= cell.x() && icon.y() >= cell.y()
                                && icon.right() <= cell.right() && icon.bottom() <= cell.bottom()
                                && amount.x() >= cell.x() && amount.y() >= cell.y()
                                && amount.right() <= cell.right() && amount.bottom() <= cell.bottom());
            }
        }
    }

    private static void assertPagedFlowGrid(
            String label,
            TerminalLayout.FlowGrid grid,
            int expectedCount
    ) {
        assertTrue(label + " preserves descriptor count", grid.itemCount() == expectedCount);
        assertTrue(label + " has bounded positive page dimensions",
                grid.columns() >= 1 && grid.rows() >= 1
                        && grid.capacity() == grid.columns() * grid.rows());
        assertTrue(label + " visible cells do not overlap", rectanglesDoNotOverlap(grid.cells()));
        if (!grid.cells().isEmpty()) {
            assertTrue(label + " sparse cells span complete width",
                    grid.cells().getFirst().x() == grid.bounds().x()
                            && grid.cells().getLast().right() == grid.bounds().right());
        }
        for (TerminalLayout.Rect cell : grid.cells()) {
            assertTrue(label + " cell stays inside row bounds",
                    cell.x() >= grid.bounds().x()
                            && cell.y() >= grid.bounds().y()
                            && cell.right() <= grid.bounds().right()
                            && cell.bottom() <= grid.bounds().bottom());
        }
    }

    private static void assertFullWidthLastPage(String label, TerminalLayout.FlowGrid grid) {
        List<TerminalLayout.Rect> last = grid.cells(grid.pageCount() - 1);
        assertTrue(label + " overflow creates at least two pages", grid.pageCount() >= 2);
        assertTrue(label + " last page spans complete width",
                !last.isEmpty()
                        && last.getFirst().x() == grid.bounds().x()
                        && last.getLast().right() == grid.bounds().right());
    }

    private static void assertRecipeGeometry(String label, TerminalLayout.Geometry geometry) {
        assertTrue(label + " recipe regions remain ordered",
                geometry.recipeDiagram().bottom() <= geometry.recipeLedger().y()
                        && geometry.recipeLedger().bottom() <= geometry.recipeFooter().y());
        assertTrue(label + " recipe empty state unifies diagram and ledger",
                geometry.recipeContent().equals(new TerminalLayout.Rect(
                        geometry.recipeDiagram().x(),
                        geometry.recipeDiagram().y(),
                        geometry.recipeDiagram().width(),
                        geometry.recipeLedger().bottom() - geometry.recipeDiagram().y()))
                        && geometry.recipeContent().bottom() <= geometry.recipeFooter().y()
                        && !geometry.recipeContent().overlaps(geometry.recipeFooter()));
        int contentTopLimit = geometry.workspace().y() + 4;
        int contentBottomLimit = geometry.recipeFooter().y() - 4;
        int topBreathingRoom = geometry.recipeContent().y() - contentTopLimit;
        int bottomBreathingRoom = contentBottomLimit - geometry.recipeContent().bottom();
        assertTrue(label + " recipe body is compact and centered above its footer",
                geometry.recipeContent().height() <= 126
                        && geometry.recipeLedger().height() <= 54
                        && topBreathingRoom >= 0
                        && bottomBreathingRoom >= 0
                        && Math.abs(topBreathingRoom - bottomBreathingRoom) <= 1);
        assertTrue(label + " recipe diagram exposes nine bounded inputs",
                geometry.recipeInputSlots().size() == RecipePresentation.MAX_INPUTS
                        && rectanglesDoNotOverlap(geometry.recipeInputSlots()));
        for (int resourceCount = 0; resourceCount <= 12; resourceCount++) {
            List<TerminalLayout.Rect> cells = geometry.recipeLedgerCells(resourceCount);
            assertTrue(label + " ledger row count follows resources",
                    cells.size() == resourceCount && rectanglesDoNotOverlap(cells));
        }
        List<TerminalLayout.Rect> oneResource = geometry.recipeLedgerCells(1);
        assertTrue(label + " resource rows align to the ledger top",
                oneResource.size() == 1
                        && oneResource.getFirst().height() <= TerminalLayout.SLOT_SIZE
                        && oneResource.getFirst().y() == geometry.recipeLedger().y());
        List<TerminalLayout.Rect> nineResources = geometry.recipeLedgerCells(9);
        assertTrue(label + " more than eight resources use a third row and at most four columns",
                nineResources.stream().map(TerminalLayout.Rect::y).distinct().count() == 3
                        && nineResources.stream().map(TerminalLayout.Rect::x).distinct().count() <= 4);
        List<TerminalLayout.Rect> craftButtons = geometry.recipeCraftButtons();
        assertTrue(label + " amount strip contains four equal segments", craftButtons.size() == 4
                && craftButtons.stream().map(TerminalLayout.Rect::width).distinct().count() == 1);
        for (int index = 1; index < craftButtons.size(); index++) {
            assertTrue(label + " amount strip segments are contiguous",
                    craftButtons.get(index - 1).right() == craftButtons.get(index).x());
        }
        List<TerminalLayout.Rect> allFooterControls = new ArrayList<>(geometry.recipeNavigationButtons());
        allFooterControls.addAll(craftButtons);
        assertTrue(label + " navigation and amount strip do not overlap",
                rectanglesDoNotOverlap(allFooterControls));
    }

    private static boolean rectanglesDoNotOverlap(List<TerminalLayout.Rect> rectangles) {
        for (int left = 0; left < rectangles.size(); left++) {
            for (int right = left + 1; right < rectangles.size(); right++) {
                if (rectangles.get(left).overlaps(rectangles.get(right))) return false;
            }
        }
        return true;
    }

    private static void assertTrue(String message, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            MagicStorage.LOGGER.error("SelfTest FAILED: {}", message);
        }
    }
}
