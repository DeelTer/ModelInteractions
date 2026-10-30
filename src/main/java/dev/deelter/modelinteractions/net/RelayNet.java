package dev.deelter.modelinteractions.net;

import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.interaction.CarryClient;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.wire.Wire;
import lombok.Getter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.DataInputStream;
import java.util.UUID;

/**
 * Transport over our own plugin channel, relayed by the Model Interactions Paper plugin.
 * Active only after the server said hello, so on other servers nothing is sent on it.
 */
public final class RelayNet implements NetBridge {
	public static final RelayNet INSTANCE = new RelayNet();
	private static final int HELLO_INTERVAL = 40, HELLO_TRIES = 15;
	@Getter
	private static boolean active;
	@Getter
	private static int range;
	@Getter
	private static boolean mobsAllowed;
	/**
	 * The server lets us lift and throw mobs.
	 */
	@Getter
	private static boolean carryAllowed;
	/**
	 * The server declared itself adults-only and allows external devices.
	 */
	@Getter
	private static boolean adultAllowed;
	private static int helloTicks, hellos;

	private RelayNet() {
	}

	/**
	 * Every client tick until the server answered: say hello. A hello sent from the server
	 * while we were still joining would be lost, so we ask once we can hear the answer.
	 */
	public static void tick() {
		if (active || hellos >= HELLO_TRIES || !ClientPlayNetworking.canSend(Payload.TYPE)) return;
		if (helloTicks++ % HELLO_INTERVAL != 0) return;
		hellos++;
		ClientPlayNetworking.send(new Payload(Wire.encodeClientHello()));
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (p, ctx) -> ctx.client().execute(() -> receive(p.data())));
	}

	public static void reset() {
		active = false;
		helloTicks = 0;
		hellos = 0;
		adultAllowed = false;
	}

	private static void receive(byte[] data) {
		try {
			DataInputStream in = Wire.in(data);
			InteractionManager mgr = InteractionManager.get();
			switch (Wire.kind(in)) {
				case Wire.K_HELLO -> {
					range = in.readUnsignedShort();
					int flags = in.readUnsignedByte();
					mobsAllowed = (flags & Wire.HELLO_MOBS) != 0;
					carryAllowed = (flags & Wire.HELLO_CARRY) != 0;
					adultAllowed = (flags & Wire.HELLO_ADULT) != 0;
					active = true;
					ModelInteractions.LOG.info("Server relays interactions within {} blocks", range);
					mgr.publishOwnPermissions();
				}
				case Wire.K_ACT -> {
					UUID sender = Wire.readUuid(in);
					mgr.handleAction(sender, Wire.readAct(in));
				}
				case Wire.K_CARRY -> {
					UUID from = Wire.readUuid(in);
					CarryClient.onCarried(from, Wire.readCarry(in));
				}
				case Wire.K_LOOK -> {
					UUID from = Wire.readUuid(in);
					CarryClient.onLook(from, Wire.readLook(in));
				}
				case Wire.K_STATE -> {
					int state = in.readUnsignedByte();
					UUID owner = Wire.readUuid(in);
					mgr.handleState(owner, state, Wire.readStateBody(in));
				}
				default -> {
				}
			}
		} catch (Exception e) {
			ModelInteractions.LOG.debug("Bad message from server", e);
		}
	}

	private static boolean send(byte[] data) {
		if (!active || !ClientPlayNetworking.canSend(Payload.TYPE)) return false;
		ClientPlayNetworking.send(new Payload(data));
		return true;
	}

	/**
	 * An already encoded message; dropped when no relay is there.
	 */
	public boolean sendRaw(byte[] data) {
		return send(data);
	}

	@Override
	public boolean sendAction(Wire.Act act) {
		return send(Wire.encodeAct(act, null));
	}

	@Override
	public boolean publishState(int state, byte[] body) {
		return send(Wire.encodeState(state, null, body));
	}

	@Override
	public boolean requestStates(UUID target) {
		return send(Wire.encodeReq(target));
	}

	@Override
	public boolean isAvailable() {
		return active;
	}

	/**
	 * Raw {@link Wire} bytes; a Paper plugin gets and sends exactly these.
	 */
	public record Payload(byte[] data) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(Identifier.parse(Wire.CHANNEL));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBytes(p.data),
				buf -> {
					int n = buf.readableBytes();
					if (n > Wire.MAX_STATE_BODY + 64) throw new IllegalArgumentException("message too big");
					byte[] d = new byte[n];
					buf.readBytes(d);
					return new Payload(d);
				});

		@Override
		public Type<Payload> type() {
			return TYPE;
		}
	}
}
