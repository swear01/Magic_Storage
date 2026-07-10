package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

public interface Actor {
    Actor EMPTY = () -> "empty";

    String name();

    static Actor bus(BlockPos pos) {
        return () -> "bus@" + pos.toShortString();
    }

    static Actor player(Player player) {
        return () -> "player@" + player.getUUID();
    }

    static Actor magicCrafting() {
        return () -> "magic_crafting";
    }
}
