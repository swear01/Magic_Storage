package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.level.block.Block;

public class StorageUnitBlock extends Block implements IStorageNetworkBlock {

    private final int typeContribution;

    public StorageUnitBlock(Properties properties, int typeContribution) {
        super(properties);
        this.typeContribution = typeContribution;
    }

    public int getTypeContribution() {
        return typeContribution;
    }
}
