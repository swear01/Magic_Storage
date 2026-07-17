package com.swearprom.magicstorage.magic_storage;

public record BusOperationId(long gameTime, long sequence) {

    public BusOperationId {
        if (sequence < 0) throw new IllegalArgumentException("Bus operation sequence must be non-negative");
    }
}
