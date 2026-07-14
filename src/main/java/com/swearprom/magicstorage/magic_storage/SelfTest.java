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
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;

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
        assertTrue("glass_bottle gives bottle_fuel", FuelTable.isFuel(new ItemStack(Items.GLASS_BOTTLE)));
        assertTrue("glass_bottle value is one", FuelTable.getFuelValues(new ItemStack(Items.GLASS_BOTTLE))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.BOTTLE_FUEL && fv.valuePerItem() == 1));
        assertTrue("potion gives bottle_fuel", FuelTable.isFuel(new ItemStack(Items.POTION)));
        assertTrue("potion value is one", FuelTable.getFuelValues(new ItemStack(Items.POTION))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.BOTTLE_FUEL && fv.valuePerItem() == 1));
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
        FuelValue bottleAuto = FuelTable.getAutoFuelValue(new ItemStack(Items.GLASS_BOTTLE), type -> 0L);
        assertTrue("single explicit pool is selected without runtime fuel data",
                bottleAuto != null && bottleAuto.pool() == EnergyType.BOTTLE_FUEL);
        assertTrue("blaze fuel has two distinct suppliers",
                FuelTable.getSupplierCount(EnergyType.BLAZE_FUEL) == 2);
        assertTrue("bottle fuel has two distinct suppliers",
                FuelTable.getSupplierCount(EnergyType.BOTTLE_FUEL) == 2);

        FuelValue blazeRodAuto = FuelTable.getAutoFuelValue(new ItemStack(Items.BLAZE_ROD), type -> 0L);
        assertTrue("auto blaze rod prefers the pool with fewer fuel choices",
                blazeRodAuto != null && blazeRodAuto.pool() == EnergyType.BLAZE_FUEL);

        List<FuelValue> equalScarcity = List.of(
                new FuelValue(EnergyType.BLAZE_FUEL, 1),
                new FuelValue(EnergyType.BOTTLE_FUEL, 1));
        FuelValue lowerAmount = FuelTable.selectAutoFuelValue(equalScarcity,
                type -> type == EnergyType.BLAZE_FUEL ? 20L : 10L);
        assertTrue("equal scarcity prefers the lower accumulated pool",
                lowerAmount != null && lowerAmount.pool() == EnergyType.BOTTLE_FUEL);

        FuelValue enumTie = FuelTable.selectAutoFuelValue(equalScarcity, type -> 10L);
        assertTrue("equal scarcity and amount use stable enum order",
                enumTie != null && enumTie.pool() == EnergyType.BLAZE_FUEL);
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

    private static void testEnergyType() {
        assertTrue("smelting is machine-generated", EnergyType.SMELTING_ENERGY.isMachineGenerated());
        assertTrue("blasting is machine-generated", EnergyType.BLASTING_ENERGY.isMachineGenerated());
        assertTrue("furnace_fuel is not machine-generated", !EnergyType.FURNACE_FUEL.isMachineGenerated());
        assertTrue("blaze_fuel is not machine-generated", !EnergyType.BLAZE_FUEL.isMachineGenerated());
        assertTrue("bottle_fuel is not machine-generated", !EnergyType.BOTTLE_FUEL.isMachineGenerated());
        assertTrue("8 types total", EnergyType.values().length == 8);
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
        assertTrue("Bottle Energy uses glass bottle as its representative item",
                EnergyType.BOTTLE_FUEL.representativeStack().is(Items.GLASS_BOTTLE));
        assertTrue("9 station and consumable mappings", MachineEnergyTable.size() == 9);
        assertTrue("Furnace maps to smelting", MachineEnergyTable.get(0).machine() == Items.FURNACE
                && MachineEnergyTable.get(0).energyType() == EnergyType.SMELTING_ENERGY);
        assertTrue("Blast Furnace maps to blasting", MachineEnergyTable.get(1).machine() == Items.BLAST_FURNACE
                && MachineEnergyTable.get(1).energyType() == EnergyType.BLASTING_ENERGY);
        assertTrue("Smoker maps to smoking", MachineEnergyTable.get(2).machine() == Items.SMOKER
                && MachineEnergyTable.get(2).energyType() == EnergyType.SMOKING_ENERGY);
        assertTrue("Campfire maps to campfire", MachineEnergyTable.get(3).machine() == Items.CAMPFIRE
                && MachineEnergyTable.get(3).energyType() == EnergyType.CAMPFIRE_ENERGY);
        assertTrue("Brewing Stand maps to brew", MachineEnergyTable.get(4).machine() == Items.BREWING_STAND
                && MachineEnergyTable.get(4).energyType() == EnergyType.BREW_ENERGY);
        assertTrue("Crafting Table maps to crafting station slot",
                MachineEnergyTable.get(MachineEnergyTable.CRAFTING_TABLE_SLOT).machine() == Items.CRAFTING_TABLE);
        assertTrue("Stonecutter maps to stonecutting station slot",
                MachineEnergyTable.get(MachineEnergyTable.STONECUTTER_SLOT).machine() == Items.STONECUTTER);
        assertTrue("Smithing Table maps to smithing station slot",
                MachineEnergyTable.get(MachineEnergyTable.SMITHING_TABLE_SLOT).machine() == Items.SMITHING_TABLE);
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
        var narrow = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 320, 240, 5, 3);
        assertTrue("crafting geometry retains its terminal profile",
                narrow.profile() == TerminalProfile.CRAFTING);
        assertTrue("320x240 crafting uses narrow fallback", !narrow.wide());
        assertTrue("narrow fallback leaves vertical breathing room", narrow.imageHeight() <= 232);
        assertTrue("narrow grid follows nine-column slot rhythm",
                narrow.itemGrid().width() == 9 * TerminalLayout.SLOT_SIZE
                        && narrow.itemGrid().height() == narrow.visibleRows() * TerminalLayout.SLOT_SIZE);
        assertTrue("narrow recipe workspace follows item grid",
                narrow.workspace().y() >= narrow.itemGrid().bottom()
                        && narrow.workspace().bottom() <= narrow.playerInventory().y());
        assertTrue("narrow player inventory fits inside frame",
                narrow.playerInventory().bottom() <= narrow.imageHeight());
        assertTrue("narrow Fuel machine grid derives five entries", narrow.machineGrid().itemCount() == 5
                && narrow.machineGrid().cells().size() == 5);
        assertTrue("narrow Fuel reserve grid derives three entries", narrow.reserveGrid().itemCount() == 3
                && narrow.reserveGrid().cells().size() == 3);
        assertTrue("narrow Fuel input stays in Fuel card",
                narrow.fuelPanel().contains(narrow.fuelInput().x(), narrow.fuelInput().y()));
        assertTrue("narrow Fuel target selector stays in the Fuel header",
                narrow.fuelTargetSelector().x() >= narrow.fuelPanel().x()
                        && narrow.fuelTargetSelector().y() >= narrow.fuelPanel().y()
                        && narrow.fuelTargetSelector().right() <= narrow.fuelPanel().right()
                        && narrow.fuelTargetSelector().bottom() <= narrow.reserveGrid().bounds().y());
        assertTrue("narrow Fuel target list button is separate from the cycle selector",
                narrow.fuelTargetListButton().width() == TerminalLayout.CONTROL_SIZE
                        && narrow.fuelTargetListButton().height() == TerminalLayout.CONTROL_SIZE
                        && !narrow.fuelTargetListButton().overlaps(narrow.fuelTargetSelector()));
        assertTrue("narrow Fuel target popup is anchored inside the terminal frame",
                narrow.frame().contains(
                        narrow.fuelTargetPopup().bounds().x(), narrow.fuelTargetPopup().bounds().y())
                        && narrow.fuelTargetPopup().bounds().right() <= narrow.frame().right()
                        && narrow.fuelTargetPopup().bounds().bottom() <= narrow.frame().bottom());
        assertTrue("narrow recipe workspace stacks diagram, ledger, and footer",
                narrow.recipeDiagram().bottom() <= narrow.recipeLedger().y()
                        && narrow.recipeLedger().bottom() <= narrow.recipeFooter().y());
        assertTrue("narrow recipe diagram exposes nine positioned input slots",
                narrow.recipeInputSlots().size() == RecipePresentation.MAX_INPUTS
                        && rectanglesDoNotOverlap(narrow.recipeInputSlots()));
        assertTrue("narrow recipe ledger derives twelve bounded rows",
                narrow.recipeLedgerCells(12).size() == 12
                        && rectanglesDoNotOverlap(narrow.recipeLedgerCells(12)));

        var sideBySide = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 423, 291, 5, 3);
        assertTrue("guiScale-4 fullscreen width uses side-by-side layout", sideBySide.wide());
        assertTrue("side-by-side frame shrinks within its supported range",
                sideBySide.imageWidth() == 367);
        assertTrue("side-by-side workspace remains usable", sideBySide.workspace().width() >= 162);
        assertTrue("side-by-side layout reserves vertical breathing room", sideBySide.imageHeight() <= 243);
        assertTrue("side-by-side workspace sits right of item grid",
                sideBySide.workspace().x() >= sideBySide.scrollbar().right());
        assertTrue("side-by-side recipe diagram keeps a larger output slot",
                sideBySide.recipeOutput().width() > sideBySide.recipeInputSlots().getFirst().width()
                        && sideBySide.recipeOutput().height()
                        > sideBySide.recipeInputSlots().getFirst().height());
        assertTrue("side-by-side recipe workspace stacks diagram, ledger, and footer",
                sideBySide.recipeDiagram().bottom() <= sideBySide.recipeLedger().y()
                        && sideBySide.recipeLedger().bottom() <= sideBySide.recipeFooter().y());
        assertTrue("side-by-side boundary is based on complete usable width",
                !TerminalLayout.forProfile(TerminalProfile.CRAFTING, 415, 291, 5, 3).wide()
                        && TerminalLayout.forProfile(TerminalProfile.CRAFTING, 416, 291, 5, 3).wide());
        assertTrue("Fuel content uses the full inner frame width",
                sideBySide.machinePanel().x() == 8
                        && sideBySide.machinePanel().width() == sideBySide.imageWidth() - 16
                        && sideBySide.fuelPanel().x() == 8
                        && sideBySide.fuelPanel().width() == sideBySide.imageWidth() - 16);
        assertTrue("page tabs are visually separated from item controls",
                sideBySide.railButtons().get(3).y() - sideBySide.railButtons().get(2).bottom() >= 6);
        assertTrue("Fuel rail contains only the three page tabs",
                sideBySide.fuelRailButtons().size() == 3);
        assertTrue("Fuel rail panel wraps only the Fuel page tabs",
                sideBySide.fuelRailPanel().equals(new TerminalLayout.Rect(
                        sideBySide.fuelRailButtons().getFirst().x() - 3,
                        sideBySide.fuelRailButtons().getFirst().y() - 3,
                        sideBySide.fuelRailButtons().getFirst().width() + 6,
                        sideBySide.fuelRailButtons().getLast().bottom()
                                - sideBySide.fuelRailButtons().getFirst().y() + 6)));
        assertTrue("five machine entries span the complete flow width",
                sideBySide.machineGrid().cells().getFirst().x() == sideBySide.machineGrid().bounds().x()
                        && sideBySide.machineGrid().cells().getLast().right()
                        == sideBySide.machineGrid().bounds().right());
        assertTrue("three reserve entries span the complete flow width",
                sideBySide.reserveGrid().cells().getFirst().x() == sideBySide.reserveGrid().bounds().x()
                        && sideBySide.reserveGrid().cells().getLast().right()
                        == sideBySide.reserveGrid().bounds().right());
        assertTrue("Fuel popup includes Auto plus every server-approved target",
                sideBySide.fuelTargetPopup().itemCount()
                        == sideBySide.reserveGrid().itemCount() + 1);
        assertTrue("Fuel popup rows are descriptor-driven and bounded",
                sideBySide.fuelTargetPopup().rows(0).size() == 4
                        && rectanglesDoNotOverlap(sideBySide.fuelTargetPopup().rows(0))
                        && sideBySide.fuelTargetPopup().rows(0).stream().allMatch(row ->
                        row.x() >= sideBySide.fuelTargetPopup().bounds().x()
                                && row.right() <= sideBySide.fuelTargetPopup().bounds().right()
                                && row.y() >= sideBySide.fuelTargetPopup().bounds().y()
                                && row.bottom() <= sideBySide.fuelTargetPopup().bounds().bottom()));
        assertTrue("Fuel popup never overlaps the left control rail",
                !sideBySide.fuelTargetPopup().bounds().overlaps(sideBySide.railPanel()));

        TerminalLayout.Rect sampleFlowCell = new TerminalLayout.Rect(20, 30, 72, 29);
        assertTrue("station hover geometry is only the centered 18px slot",
                TerminalLayout.centeredSlot(sampleFlowCell).equals(
                        new TerminalLayout.Rect(47, 30, TerminalLayout.SLOT_SIZE, TerminalLayout.SLOT_SIZE)));
        assertTrue("reserve hover geometry is only the centered 16px icon",
                TerminalLayout.centeredIcon(sampleFlowCell).equals(
                        new TerminalLayout.Rect(48, 30,
                                TerminalLayout.ICON_CANVAS_SIZE, TerminalLayout.ICON_CANVAS_SIZE)));

        var manyFuelDescriptors = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 320, 240, 5, 60);
        int manyFuelLeft = manyFuelDescriptors.centeredFrameLeft(320);
        assertTrue("Fuel target rail stays fixed-size when reserve descriptors grow",
                manyFuelDescriptors.fuelRailButtons().size() == 3);
        assertTrue("large reserve descriptor sets cannot push rail or frame offscreen",
                manyFuelLeft + manyFuelDescriptors.railPanel().x() >= 0
                        && manyFuelLeft + manyFuelDescriptors.imageWidth() <= 320);
        assertTrue("large Fuel target lists expose a bounded popup viewport",
                manyFuelDescriptors.fuelTargetPopup().capacity() > 0
                        && manyFuelDescriptors.fuelTargetPopup().capacity() <= 6
                        && manyFuelDescriptors.fuelTargetPopup().itemCount() == 61);
        int maximumFuelTargetScroll = manyFuelDescriptors.fuelTargetPopup().maxScrollOffset();
        assertTrue("Fuel target popup scroll clamps at both ends",
                manyFuelDescriptors.fuelTargetPopup().clampScrollOffset(-1) == 0
                        && manyFuelDescriptors.fuelTargetPopup().clampScrollOffset(Integer.MAX_VALUE)
                        == maximumFuelTargetScroll
                        && maximumFuelTargetScroll
                        == manyFuelDescriptors.fuelTargetPopup().itemCount()
                        - manyFuelDescriptors.fuelTargetPopup().capacity());
        assertTrue("Fuel target popup final viewport remains full and bounded",
                manyFuelDescriptors.fuelTargetPopup().rows(maximumFuelTargetScroll).size()
                        == manyFuelDescriptors.fuelTargetPopup().capacity());

        var pageCapacity = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 320, 240, 5, 3);
        var partialLastPage = TerminalLayout.forProfile(TerminalProfile.CRAFTING,
                320, 240,
                pageCapacity.machineGrid().capacity() + 1,
                pageCapacity.reserveGrid().capacity() + 1);
        List<TerminalLayout.Rect> lastMachineCells = partialLastPage.machineGrid().cells(1);
        List<TerminalLayout.Rect> lastReserveCells = partialLastPage.reserveGrid().cells(1);
        assertTrue("partial last machine page spans the complete flow width",
                lastMachineCells.size() == 1
                        && lastMachineCells.getFirst().x() == partialLastPage.machineGrid().bounds().x()
                        && lastMachineCells.getFirst().right() == partialLastPage.machineGrid().bounds().right());
        assertTrue("partial last reserve page spans the complete flow width",
                lastReserveCells.size() == 1
                        && lastReserveCells.getFirst().x() == partialLastPage.reserveGrid().bounds().x()
                        && lastReserveCells.getFirst().right() == partialLastPage.reserveGrid().bounds().right());

        for (int machineCount : new int[]{0, 1, 5, 6, 9, 10, 40}) {
            for (int reserveCount : new int[]{0, 1, 3, 4, 8, 30}) {
                var flow = TerminalLayout.forProfile(TerminalProfile.CRAFTING, 320, 240, machineCount, reserveCount);
                assertTrue("machine descriptor count is preserved", flow.machineGrid().itemCount() == machineCount);
                assertTrue("reserve descriptor count is preserved", flow.reserveGrid().itemCount() == reserveCount);
                assertTrue("machine visible cells are bounded by capacity",
                        flow.machineGrid().cells().size() == Math.min(machineCount, flow.machineGrid().capacity()));
                assertTrue("reserve visible cells are bounded by capacity",
                        flow.reserveGrid().cells().size() == Math.min(reserveCount, flow.reserveGrid().capacity()));
                assertTrue("machine overflow exposes deterministic pages",
                        flow.machineGrid().pageCount() == Math.max(1,
                                (machineCount + flow.machineGrid().capacity() - 1) / flow.machineGrid().capacity()));
                assertTrue("reserve overflow exposes deterministic pages",
                        flow.reserveGrid().pageCount() == Math.max(1,
                                (reserveCount + flow.reserveGrid().capacity() - 1) / flow.reserveGrid().capacity()));
                assertTrue("machine cells do not overlap", rectanglesDoNotOverlap(flow.machineGrid().cells()));
                assertTrue("reserve cells do not overlap", rectanglesDoNotOverlap(flow.reserveGrid().cells()));
                if (!flow.machineGrid().cells().isEmpty()) {
                    assertTrue("machine cells span their complete flow width",
                            flow.machineGrid().cells().getFirst().x() == flow.machineGrid().bounds().x()
                                    && flow.machineGrid().cells().getLast().right()
                                    == flow.machineGrid().bounds().right());
                }
                if (!flow.reserveGrid().cells().isEmpty()) {
                    assertTrue("reserve cells span their complete flow width",
                            flow.reserveGrid().cells().getFirst().x() == flow.reserveGrid().bounds().x()
                                    && flow.reserveGrid().cells().getLast().right()
                                    == flow.reserveGrid().bounds().right());
                }
                for (TerminalLayout.Rect cell : flow.machineGrid().cells()) {
                    assertTrue("machine cell stays in machine flow bounds",
                            cell.x() >= flow.machineGrid().bounds().x()
                                    && cell.y() >= flow.machineGrid().bounds().y()
                                    && cell.right() <= flow.machineGrid().bounds().right()
                                    && cell.bottom() <= flow.machineGrid().bounds().bottom());
                }
                for (TerminalLayout.Rect cell : flow.reserveGrid().cells()) {
                    assertTrue("reserve cell stays in reserve flow bounds",
                            cell.x() >= flow.reserveGrid().bounds().x()
                                    && cell.y() >= flow.reserveGrid().bounds().y()
                                    && cell.right() <= flow.reserveGrid().bounds().right()
                                    && cell.bottom() <= flow.reserveGrid().bounds().bottom());
                }
            }
        }

        for (int height = 240; height <= 600; height++) {
            for (int width : new int[]{320, 415, 416, 423, 480, 854}) {
                var geometry = TerminalLayout.forProfile(TerminalProfile.CRAFTING, width, height, 5, 3);
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
                assertTrue("crafting recipe regions remain ordered at " + width + "x" + height,
                        geometry.recipeDiagram().bottom() <= geometry.recipeLedger().y()
                                && geometry.recipeLedger().bottom() <= geometry.recipeFooter().y());
                assertTrue("crafting diagram slots remain bounded at " + width + "x" + height,
                        geometry.recipeInputSlots().size() == RecipePresentation.MAX_INPUTS
                                && rectanglesDoNotOverlap(geometry.recipeInputSlots())
                                && geometry.recipeDiagram().contains(
                                geometry.recipeInputSlots().getFirst().x(),
                                geometry.recipeInputSlots().getFirst().y())
                                && geometry.recipeDiagram().contains(
                                geometry.recipeInputSlots().getLast().right() - 1,
                                geometry.recipeInputSlots().getLast().bottom() - 1));
                for (int resourceCount = 0; resourceCount <= 12; resourceCount++) {
                    List<TerminalLayout.Rect> cells = geometry.recipeLedgerCells(resourceCount);
                    assertTrue("crafting ledger row count follows resources at " + width + "x" + height,
                            cells.size() == resourceCount && rectanglesDoNotOverlap(cells));
                    for (TerminalLayout.Rect cell : cells) {
                        assertTrue("crafting ledger cell stays in its region at " + width + "x" + height,
                                cell.x() >= geometry.recipeLedger().x()
                                        && cell.y() >= geometry.recipeLedger().y()
                                        && cell.right() <= geometry.recipeLedger().right()
                                        && cell.bottom() <= geometry.recipeLedger().bottom());
                    }
                }
                List<TerminalLayout.Rect> footerControls = new ArrayList<>();
                footerControls.addAll(geometry.recipeNavigationButtons());
                footerControls.addAll(geometry.recipeCraftButtons());
                assertTrue("crafting footer controls remain disjoint at " + width + "x" + height,
                        rectanglesDoNotOverlap(footerControls));
                assertTrue("Fuel cards do not overlap player inventory at " + width + "x" + height,
                        geometry.machinePanel().bottom() <= geometry.fuelPanel().y()
                                && !geometry.machinePanel().overlaps(geometry.playerInventory())
                                && !geometry.fuelPanel().overlaps(geometry.playerInventory()));
            }
        }

        for (int height = 240; height <= 600; height++) {
            var geometry = TerminalLayout.forProfile(TerminalProfile.STORAGE, 320, height, 0, 0);
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
