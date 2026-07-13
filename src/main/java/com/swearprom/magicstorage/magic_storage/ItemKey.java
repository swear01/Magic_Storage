package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record ItemKey(Item item, DataComponentMap components) {

    public static ItemKey of(ItemStack stack) {
        DataComponentMap snapshot = DataComponentMap.builder()
                .addAll(stack.getComponents())
                .build();
        return new ItemKey(stack.getItem(), snapshot);
    }

    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return stack;
    }
}
