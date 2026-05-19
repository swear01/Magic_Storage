package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record ItemKey(Item item, DataComponentMap components) {

    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack.getItem(), stack.getComponents());
    }

    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return stack;
    }
}
