package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CoreRecoverySavedData extends SavedData {
    private static final String DATA_NAME = MagicStorage.MODID + "_core_recovery";
    private static final String TAG_RECOVERIES = "recoveries";
    private static final String TAG_ID = "id";
    private static final String TAG_OWNER = "owner";
    private static final String TAG_CONTENTS = "contents";
    private static final String TAG_TYPE_COUNT = "typeCount";
    private static final String TAG_ITEM_COUNT = "itemCount";
    private static final String TAG_CREATED_AT = "createdAt";

    private final Map<UUID, Recovery> recoveries = new LinkedHashMap<>();

    public static CoreRecoverySavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld is unavailable for Core recovery storage");
        }
        return overworld.getDataStorage().computeIfAbsent(
                new Factory<>(CoreRecoverySavedData::new, CoreRecoverySavedData::load),
                DATA_NAME);
    }

    public UUID create(
            CompoundTag contents,
            UUID owner,
            int typeCount,
            long itemCount,
            long createdAt
    ) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (recoveries.containsKey(id));
        recoveries.put(id, new Recovery(
                contents.copy(),
                owner,
                Math.max(0, typeCount),
                Math.max(0, itemCount),
                createdAt));
        setDirty();
        return id;
    }

    public boolean has(UUID id) {
        return recoveries.containsKey(id);
    }

    public Optional<CompoundTag> claim(UUID id) {
        Recovery recovery = recoveries.remove(id);
        if (recovery == null) return Optional.empty();
        setDirty();
        return Optional.of(recovery.contents().copy());
    }

    public Optional<RecoverySummary> summary(UUID id) {
        Recovery recovery = recoveries.get(id);
        return recovery == null ? Optional.empty() : Optional.of(recovery.summary(id));
    }

    public Optional<RecoverySummary> reissueLatest(UUID owner) {
        RecoverySummary latest = null;
        for (Map.Entry<UUID, Recovery> entry : recoveries.entrySet()) {
            Recovery recovery = entry.getValue();
            if (recovery.owner() == null || !recovery.owner().equals(owner)) continue;
            RecoverySummary candidate = recovery.summary(entry.getKey());
            if (latest == null || candidate.createdAt() >= latest.createdAt()) {
                latest = candidate;
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Recovery> entry : recoveries.entrySet()) {
            Recovery recovery = entry.getValue();
            CompoundTag recoveryTag = new CompoundTag();
            recoveryTag.putUUID(TAG_ID, entry.getKey());
            if (recovery.owner() != null) recoveryTag.putUUID(TAG_OWNER, recovery.owner());
            recoveryTag.put(TAG_CONTENTS, recovery.contents().copy());
            recoveryTag.putInt(TAG_TYPE_COUNT, recovery.typeCount());
            recoveryTag.putLong(TAG_ITEM_COUNT, recovery.itemCount());
            recoveryTag.putLong(TAG_CREATED_AT, recovery.createdAt());
            list.add(recoveryTag);
        }
        tag.put(TAG_RECOVERIES, list);
        return tag;
    }

    private static CoreRecoverySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CoreRecoverySavedData data = new CoreRecoverySavedData();
        ListTag list = tag.getList(TAG_RECOVERIES, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag recoveryTag = list.getCompound(index);
            if (!recoveryTag.hasUUID(TAG_ID)
                    || !recoveryTag.contains(TAG_CONTENTS, Tag.TAG_COMPOUND)) continue;
            UUID id = recoveryTag.getUUID(TAG_ID);
            UUID owner = recoveryTag.hasUUID(TAG_OWNER) ? recoveryTag.getUUID(TAG_OWNER) : null;
            data.recoveries.put(id, new Recovery(
                    recoveryTag.getCompound(TAG_CONTENTS).copy(),
                    owner,
                    Math.max(0, recoveryTag.getInt(TAG_TYPE_COUNT)),
                    Math.max(0, recoveryTag.getLong(TAG_ITEM_COUNT)),
                    recoveryTag.getLong(TAG_CREATED_AT)));
        }
        return data;
    }

    public record RecoverySummary(
            UUID id,
            int typeCount,
            long itemCount,
            long createdAt
    ) {
    }

    private record Recovery(
            CompoundTag contents,
            UUID owner,
            int typeCount,
            long itemCount,
            long createdAt
    ) {
        private RecoverySummary summary(UUID id) {
            return new RecoverySummary(id, typeCount, itemCount, createdAt);
        }
    }
}
