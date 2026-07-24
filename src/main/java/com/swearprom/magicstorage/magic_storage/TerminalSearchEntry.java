package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

record TerminalSearchEntry(Item item, String namespace, String searchableName) {
    static TerminalSearchEntry create(ItemKey key) {
        return create(key.toStack(1));
    }

    static TerminalSearchEntry create(ItemStack stack) {
        ResourceLocation id = stack.getItem().builtInRegistryHolder().key().location();
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return new TerminalSearchEntry(
                stack.getItem(),
                id.getNamespace(),
                name + " " + id.getPath().toLowerCase(Locale.ROOT));
    }

    boolean is(TagKey<Item> tag) {
        return item.builtInRegistryHolder().is(tag);
    }
}
