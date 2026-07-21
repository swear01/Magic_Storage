package com.swearprom.magicstorage.magic_storage;

import java.util.Objects;

public record TypedRecipeInput(StorageResourceKey key, long amount, Role role) {
    public enum Role {
        CONSUME,
        CATALYST,
        TOOL
    }

    public TypedRecipeInput {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(role, "role");
        if (amount <= 0) throw new IllegalArgumentException("Typed recipe input amount must be positive");
    }

    public static TypedRecipeInput consume(StorageResourceKey key, long amount) {
        return new TypedRecipeInput(key, amount, Role.CONSUME);
    }

    public static TypedRecipeInput catalyst(StorageResourceKey key, long amount) {
        return new TypedRecipeInput(key, amount, Role.CATALYST);
    }

    public static TypedRecipeInput tool(StorageResourceKey key, long amount) {
        return new TypedRecipeInput(key, amount, Role.TOOL);
    }
}
