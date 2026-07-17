package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class BusFilterRule {

    private static final String TAG_TYPE = "type";
    private static final String TAG_STACK = "stack";
    private static final String TAG_ID = "id";
    private static final String TAG_NAMESPACE = "namespace";

    public enum Type {
        EXACT_STACK,
        ITEM,
        TAG,
        MOD,
        UNAVAILABLE;

        String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Type type;
    private final ItemKey exactKey;
    private final ResourceLocation id;
    private final String namespace;
    private final CompoundTag rawUnavailable;

    private BusFilterRule(
            Type type,
            ItemKey exactKey,
            ResourceLocation id,
            String namespace,
            CompoundTag rawUnavailable
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.exactKey = exactKey;
        this.id = id;
        this.namespace = namespace;
        this.rawUnavailable = rawUnavailable == null ? null : rawUnavailable.copy();
    }

    public static BusFilterRule exact(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Exact bus filter cannot be empty");
        return new BusFilterRule(Type.EXACT_STACK, ItemKey.of(stack), null, null, null);
    }

    public static BusFilterRule item(ResourceLocation itemId) {
        return new BusFilterRule(Type.ITEM, null, Objects.requireNonNull(itemId, "itemId"), null, null);
    }

    public static BusFilterRule tag(ResourceLocation tagId) {
        return new BusFilterRule(Type.TAG, null, Objects.requireNonNull(tagId, "tagId"), null, null);
    }

    public static BusFilterRule mod(String namespace) {
        String normalized = Objects.requireNonNull(namespace, "namespace").toLowerCase(Locale.ROOT);
        if (ResourceLocation.tryBuild(normalized, "filter_probe") == null) {
            throw new IllegalArgumentException("Invalid bus filter namespace: " + namespace);
        }
        return new BusFilterRule(Type.MOD, null, null, normalized, null);
    }

    static BusFilterRule load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(TAG_TYPE, Tag.TAG_STRING)) return unavailable(tag);
        return switch (tag.getString(TAG_TYPE)) {
            case "exact_stack" -> loadExact(tag, registries);
            case "item" -> loadItem(tag, registries);
            case "tag" -> loadTag(tag, registries);
            case "mod" -> loadMod(tag);
            default -> unavailable(tag);
        };
    }

    static BusFilterRule loadLegacyExact(CompoundTag stackTag, HolderLookup.Provider registries) {
        CompoundTag ruleTag = new CompoundTag();
        ruleTag.putString(TAG_TYPE, "exact_stack");
        ruleTag.put(TAG_STACK, stackTag.copy());
        return loadExact(ruleTag, registries);
    }

    private static BusFilterRule loadExact(CompoundTag ruleTag, HolderLookup.Provider registries) {
        if (!(ruleTag.get(TAG_STACK) instanceof CompoundTag stackTag)) return unavailable(ruleTag);
        ResourceLocation itemId = ResourceLocation.tryParse(stackTag.getString("id"));
        if (itemId == null || registries.lookupOrThrow(Registries.ITEM)
                .get(ResourceKey.create(Registries.ITEM, itemId)).isEmpty()) {
            return unavailable(ruleTag);
        }
        ItemStack stack = ItemStack.parse(registries, stackTag).orElse(ItemStack.EMPTY);
        return stack.isEmpty() ? unavailable(ruleTag) : exact(stack);
    }

    private static BusFilterRule loadItem(CompoundTag ruleTag, HolderLookup.Provider registries) {
        ResourceLocation itemId = parseId(ruleTag);
        if (itemId == null || registries.lookupOrThrow(Registries.ITEM)
                .get(ResourceKey.create(Registries.ITEM, itemId)).isEmpty()) {
            return unavailable(ruleTag);
        }
        return item(itemId);
    }

    private static BusFilterRule loadTag(CompoundTag ruleTag, HolderLookup.Provider registries) {
        ResourceLocation tagId = parseId(ruleTag);
        if (tagId == null || registries.lookupOrThrow(Registries.ITEM)
                .get(TagKey.create(Registries.ITEM, tagId)).isEmpty()) {
            return unavailable(ruleTag);
        }
        return tag(tagId);
    }

    private static BusFilterRule loadMod(CompoundTag ruleTag) {
        if (!ruleTag.contains(TAG_NAMESPACE, Tag.TAG_STRING)) return unavailable(ruleTag);
        String namespace = ruleTag.getString(TAG_NAMESPACE).toLowerCase(Locale.ROOT);
        if (ResourceLocation.tryBuild(namespace, "filter_probe") == null
                || BuiltInRegistries.ITEM.keySet().stream()
                .noneMatch(id -> id.getNamespace().equals(namespace))) {
            return unavailable(ruleTag);
        }
        return mod(namespace);
    }

    private static ResourceLocation parseId(CompoundTag tag) {
        return tag.contains(TAG_ID, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(TAG_ID))
                : null;
    }

    private static BusFilterRule unavailable(CompoundTag raw) {
        return new BusFilterRule(Type.UNAVAILABLE, null, null, null, raw);
    }

    CompoundTag save(HolderLookup.Provider registries) {
        if (rawUnavailable != null) return rawUnavailable.copy();
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_TYPE, type.serializedName());
        switch (type) {
            case EXACT_STACK -> tag.put(TAG_STACK, exactKey.toStack(1).save(registries));
            case ITEM, TAG -> tag.putString(TAG_ID, id.toString());
            case MOD -> tag.putString(TAG_NAMESPACE, namespace);
            case UNAVAILABLE -> throw new IllegalStateException("Unavailable filter rule has no raw data");
        }
        return tag;
    }

    public Type type() {
        return type;
    }

    public Optional<ItemKey> exactKey() {
        return Optional.ofNullable(exactKey);
    }

    public Optional<ResourceLocation> id() {
        return Optional.ofNullable(id);
    }

    public Optional<String> namespace() {
        return Optional.ofNullable(namespace);
    }

    public boolean available() {
        return rawUnavailable == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BusFilterRule rule)) return false;
        return type == rule.type
                && Objects.equals(exactKey, rule.exactKey)
                && Objects.equals(id, rule.id)
                && Objects.equals(namespace, rule.namespace)
                && Objects.equals(rawUnavailable, rule.rawUnavailable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, exactKey, id, namespace, rawUnavailable);
    }
}
