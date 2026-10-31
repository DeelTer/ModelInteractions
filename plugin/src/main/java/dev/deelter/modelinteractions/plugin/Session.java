package dev.deelter.modelinteractions.plugin;

import dev.deelter.modelinteractions.wire.Wire;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the server knows about one player with the mod. Main thread only.
 */
final class Session {
	final TokenBucket actions, states, requests;
	/**
	 * Latest canonical state bodies this player published.
	 */
	final byte[][] stored = new byte[Wire.S_COUNT][];
	private final Held[] grabs = new Held[Wire.MAX_HAND + 1];
	private final Map<String, Long> cooldowns = new HashMap<>();
	/**
	 * This player's own permissions with their name lists; the lists are never sent to anyone.
	 */
	Wire.Perm perm;
	int strikes;

	Session(InteractionsPlugin.Settings s) {
		actions = new TokenBucket(s.actRate(), s.actBurst());
		states = new TokenBucket(s.stateRate(), s.stateBurst());
		requests = new TokenBucket(s.reqRate(), s.reqBurst());
	}

	void reconfigure(InteractionsPlugin.Settings s) {
		actions.set(s.actRate(), s.actBurst());
		states.set(s.stateRate(), s.stateBurst());
		requests.set(s.reqRate(), s.reqBurst());
	}

	void grab(int hand, UUID target, int bone) {
		grabs[hand] = new Held(target, bone);
	}

	boolean holds(int hand, UUID target, int bone) {
		Held h = grabs[hand];
		return h != null && h.target().equals(target) && h.bone() == bone;
	}

	void release(int hand) {
		grabs[hand] = null;
	}

	/**
	 * The grab a hand holds, as a release message for everyone who saw it; null if it holds nothing.
	 */
	Wire.Act releaseOf(int hand) {
		Held h = grabs[hand];
		return h == null ? null : new Wire.Act(Wire.OP_RELEASE, h.target(), h.bone(), hand, false, null, null);
	}

	void dropGrabs() {
		for (int i = 0; i < grabs.length; i++) grabs[i] = null;
	}

	/**
	 * Whether {@code actorName} may interact with this player at all (mode and name lists).
	 */
	boolean allowsActor(String actorName) {
		if (perm == null) return true;
		String n = actorName.toLowerCase(java.util.Locale.ROOT);
		if (perm.blacklist().contains(n)) return false;
		return switch (perm.mode()) {
			case 1 -> perm.whitelist().contains(n);
			case 2 -> false;
			default -> true;
		};
	}

	boolean cooldown(String what, UUID target, long ms) {
		long now = System.currentTimeMillis();
		String key = what + target;
		Long last = cooldowns.get(key);
		if (last != null && now - last < ms) return false;
		if (cooldowns.size() > 64) cooldowns.values().removeIf(t -> now - t > 60_000);
		cooldowns.put(key, now);
		return true;
	}

	private record Held(UUID target, int bone) {
	}
}
