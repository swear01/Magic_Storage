package com.swearprom.magicstorage.magic_storage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

public record SearchFilterPacket(int containerId, String filter) implements CustomPacketPayload {

    public static final Type<SearchFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "search_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SearchFilterPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SearchFilterPacket::containerId,
                    ByteBufCodecs.STRING_UTF8, SearchFilterPacket::filter,
                    SearchFilterPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
