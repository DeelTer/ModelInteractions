package dev.deelter.modelinteractions.net;

import dev.deelter.modelinteractions.wire.CarryGroup;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CarryGroupTest {
	private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

	@Test
	void biggerThingsNeedMoreHands() {
		assertEquals(2, CarryGroup.handsNeeded(1.0, 1.6));
		assertEquals(3, CarryGroup.handsNeeded(2.2, 1.6));
		assertEquals(4, CarryGroup.handsNeeded(3.0, 1.6));
		assertEquals(6, CarryGroup.handsNeeded(100, 1.6));
	}

	@Test
	void oneHandEachFromTwoPlayersLiftsTogether() {
		CarryGroup g = new CarryGroup(2);
		g.hold(A, 1, 0, 10, 0, 0);
		assertEquals(CarryGroup.Change.NONE, g.update(0));
		g.hold(B, 1, 2, 10, 0, 0);
		assertEquals(CarryGroup.Change.NONE, g.update(100)); // not yet: a moment of holding first
		g.hold(A, 1, 0, 10, 0, 300);
		g.hold(B, 1, 2, 10, 0, 300);
		assertEquals(CarryGroup.Change.LIFT, g.update(420));
		assertTrue(g.isLifted());
		// hangs between them
		assertEquals(1.0, g.goal()[0], 1e-9);
	}

	@Test
	void pullingApartStaysInTheMiddleWeightedByHands() {
		CarryGroup g = new CarryGroup(3);
		g.hold(A, 2, 0, 0, 0, 0);
		g.hold(B, 1, 3, 0, 0, 0);
		assertEquals(1.0, g.goal()[0], 1e-9);
	}

	@Test
	void fallsWhenTooFewHandsAndThrowsWithTheirAverageSpeed() {
		CarryGroup g = new CarryGroup(2);
		g.hold(A, 1, 0, 0, 0, 0);
		g.hold(B, 1, 0, 0, 0, 0);
		g.update(0);
		assertEquals(CarryGroup.Change.LIFT, g.update(400));
		g.letGo(A, 0, true, 1f, 0, 0, 450);
		g.letGo(B, 0, true, 0.6f, 0, 0, 460);
		assertEquals(CarryGroup.Change.FALL, g.update(470));
		float[] v = g.throwVelocity(10);
		// average 0.8, a little stronger for two throwers
		assertEquals(0.8f * 1.15f, v[0], 1e-4);
	}

	@Test
	void throwIsCappedAndHoldersThatStopReportingLetGo() {
		CarryGroup g = new CarryGroup(2);
		g.hold(A, 2, 0, 0, 0, 0);
		g.update(0);
		g.update(400);
		g.letGo(A, 0, true, 9f, 0, 0, 410);
		g.update(420);
		assertEquals(1.2f, g.throwVelocity(1.2)[0], 1e-4);

		CarryGroup h = new CarryGroup(2);
		h.hold(C, 2, 0, 0, 0, 0);
		h.update(0);
		h.update(400);
		assertTrue(h.isLifted());
		assertEquals(CarryGroup.Change.FALL, h.update(400 + CarryGroup.STALE_MS + 1));
		assertFalse(h.isLifted());
	}

	@Test
	void caughtInTheAirWithOneHandAndLetGoWhenSetDown() {
		CarryGroup g = new CarryGroup(2);
		g.setGrounded(false); // jumping or falling
		g.hold(A, 1, 0, 5, 0, 0);
		g.update(0);
		assertEquals(CarryGroup.Change.LIFT, g.update(CarryGroup.CATCH_DELAY_MS + 1));
		// pulled down onto the ground: one hand isn't enough there, so it stands again
		g.setGrounded(true);
		assertEquals(CarryGroup.Change.FALL, g.update(200));
		assertEquals(0f, g.throwVelocity(5)[1], 1e-6);
	}

	@Test
	void oneHandCannotLiftFromTheGround() {
		CarryGroup g = new CarryGroup(2);
		g.hold(A, 1, 0, 0, 0, 0);
		g.update(0);
		assertEquals(CarryGroup.Change.NONE, g.update(1000));
		assertFalse(g.isLifted());
	}
}
