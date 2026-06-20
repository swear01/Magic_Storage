package com.swearprom.magicstorage.magic_storage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TerminalSettingsPacket(
        int containerId,
        int visibleRows
) implements CustomPacketPayload {

    public static final Type<TerminalSettingsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MagicStorage.MODID, "terminal_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalSettingsPacket> STREAM_CODEC =
            StreamCodec.composite(
                    net.minecraft.network.codec.ByteBufCodecs.VAR_INT, TerminalSettingsPacket::containerId,
                    net.minecraft.network.codec.ByteBufCodecs.VAR_INT, TerminalSettingsPacket::visibleRows,
                    TerminalSettingsPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
