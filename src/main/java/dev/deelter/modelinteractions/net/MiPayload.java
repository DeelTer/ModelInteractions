package dev.deelter.modelinteractions.net;

import dev.deelter.modelinteractions.wire.Wire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Raw {@link Wire} bytes on our channel; a Paper plugin gets and sends exactly these.
 */
public record MiPayload(byte[] data) implements CustomPacketPayload {
	public static final Type<MiPayload> TYPE = new Type<>(Identifier.parse(Wire.CHANNEL));
	public static final StreamCodec<RegistryFriendlyByteBuf, MiPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeBytes(p.data),
			buf -> {
				int n = buf.readableBytes();
				if (n > Wire.MAX_STATE_BODY + 64) throw new IllegalArgumentException("message too big");
				byte[] d = new byte[n];
				buf.readBytes(d);
				return new MiPayload(d);
			});

	@Override
	public Type<MiPayload> type() {
		return TYPE;
	}
}
