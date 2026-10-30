package dev.deelter.modelinteractions.net;

import dev.deelter.modelinteractions.wire.Wire;
import lombok.Setter;

import java.util.UUID;

/**
 * Picks the transport: the server plugin's own channel when the server announced it,
 * otherwise CPM plugin messages if CPM is installed, otherwise nothing (local only).
 */
public final class NetRouter implements NetBridge {
	private static final NetRouter INSTANCE = new NetRouter();
	@Setter
	private NetBridge cpm = NetBridge.NONE;

	private NetRouter() {
	}

	public static NetRouter get() {
		return INSTANCE;
	}

	private NetBridge active() {
		if (RelayNet.isActive()) return RelayNet.INSTANCE;
		return cpm;
	}

	@Override
	public boolean sendAction(Wire.Act act) {
		return active().sendAction(act);
	}

	@Override
	public boolean publishState(int state, byte[] body) {
		return active().publishState(state, body);
	}

	@Override
	public boolean requestStates(UUID target) {
		return active().requestStates(target);
	}

	@Override
	public boolean isAvailable() {
		return active().isAvailable();
	}
}
