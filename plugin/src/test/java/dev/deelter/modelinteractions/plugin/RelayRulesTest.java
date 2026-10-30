package dev.deelter.modelinteractions.plugin;

import dev.deelter.modelinteractions.wire.Wire;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelayRulesTest {
	private static final InteractionsPlugin.Settings S = new InteractionsPlugin.Settings(16, 6, 48, true, 32,
			100, 150, 2, 6, 8, 24, 250, 1000);

	@Test
	void nameListsNeverLeaveTheServer() throws IOException {
		Wire.Perm p = new Wire.Perm(1, 0x3f, List.of("friend"), List.of("stalker"));
		Wire.Perm out = Wire.decodePerm(InteractionsPlugin.redacted(p));
		assertTrue(out.whitelist().isEmpty());
		assertTrue(out.blacklist().isEmpty());
		// whitelist is enforced on the server, so others see "everyone"
		assertEquals(0, out.mode());
		assertEquals(0x3f, out.flags());
	}

	@Test
	void serverEnforcesWhoMayTouch() {
		Session s = new Session(S);
		assertTrue(s.allowsActor("anyone"));
		s.perm = new Wire.Perm(1, 0, List.of("friend"), List.of());
		assertTrue(s.allowsActor("Friend"));
		assertFalse(s.allowsActor("other"));
		s.perm = new Wire.Perm(0, 0, List.of(), List.of("stalker"));
		assertFalse(s.allowsActor("Stalker"));
		s.perm = new Wire.Perm(2, 0, List.of(), List.of());
		assertFalse(s.allowsActor("friend"));
	}

	@Test
	void movesOnlyForStartedGrabs() {
		Session s = new Session(S);
		UUID t = UUID.randomUUID();
		assertFalse(s.holds(1, t, 4));
		s.grab(1, t, 4);
		assertTrue(s.holds(1, t, 4));
		assertFalse(s.holds(1, t, 5));
		assertFalse(s.holds(2, t, 4));
		s.release(1);
		assertFalse(s.holds(1, t, 4));
	}

	@Test
	void rateLimitBurstsThenThrottles() {
		TokenBucket b = new TokenBucket(1, 3);
		assertTrue(b.take());
		assertTrue(b.take());
		assertTrue(b.take());
		assertFalse(b.take());
	}

	@Test
	void cooldownPerTarget() {
		Session s = new Session(S);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		assertTrue(s.cooldown("pet", a, 10_000));
		assertFalse(s.cooldown("pet", a, 10_000));
		assertTrue(s.cooldown("pet", b, 10_000));
	}
}
