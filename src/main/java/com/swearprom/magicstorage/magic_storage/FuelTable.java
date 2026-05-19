package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FuelTable {
    private static final Map<Item, List<FuelValue>> TABLE = new HashMap<>();

    static {
        add(Items.COAL, new FuelValue(EnergyType.FURNACE_FUEL, 1600));
        add(Items.CHARCOAL, new FuelValue(EnergyType.FURNACE_FUEL, 1600));
        add(Items.COAL_BLOCK, new FuelValue(EnergyType.FURNACE_FUEL, 16000));
        add(Items.LAVA_BUCKET, new FuelValue(EnergyType.FURNACE_FUEL, 20000));
        add(Items.BLAZE_POWDER, new FuelValue(EnergyType.BLAZE_FUEL, 600));
        add(Items.BLAZE_ROD,
            new FuelValue(EnergyType.FURNACE_FUEL, 2400),
            new FuelValue(EnergyType.BLAZE_FUEL, 1200));
        add(Items.GLASS_BOTTLE, new FuelValue(EnergyType.BOTTLE_FUEL, 1));
        add(Items.POTION, new FuelValue(EnergyType.BOTTLE_FUEL, 1));
    }

    private static void add(Item item, FuelValue... values) {
        TABLE.put(item, List.of(values));
    }

    public static List<FuelValue> getFuelValues(ItemStack stack) {
        return TABLE.getOrDefault(stack.getItem(), List.of());
    }

    public static boolean isFuel(ItemStack stack) {
        return TABLE.containsKey(stack.getItem());
    }
}
