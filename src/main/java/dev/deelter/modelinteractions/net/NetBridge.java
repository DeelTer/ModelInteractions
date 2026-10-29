package dev.deelter.modelinteractions.net;

import java.util.UUID;

import dev.deelter.modelinteractions.wire.Wire;

/** Outgoing side of the protocol. */
public interface NetBridge {
	NetBridge NONE = new NetBridge() {
		@Override public boolean sendAction(Wire.Act act) { return false; }
		@Override public boolean publishState(int state, byte[] body) { return false; }
		@Override public boolean requestStates(UUID target) { return false; }
		@Override public boolean isAvailable() { return false; }
	};

	boolean sendAction(Wire.Act act);

	/** Publishes one of the sender's own states ({@link Wire#S_PERM} etc.). */
	boolean publishState(int state, byte[] body);

	/** Asks for another player's states; transports that resend states by themselves ignore it. */
	boolean requestStates(UUID target);

	boolean isAvailable();
}
