package dev.deelter.modelinteractions.physics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringJointTest {

	private static void run(SpringJoint j, float seconds) {
		for (float t = 0; t < seconds; t += 1 / 60f) j.step(1 / 60f);
	}

	@Test
	void returnsToRestAfterRelease() {
		SpringJoint j = new SpringJoint(PartType.EAR.defaultProfile(), 4);
		j.setHeld(true);
		j.getTarget().set(0, 0, 0.8f);
		run(j, 1);
		assertEquals(0.8f, j.getOffset().z, 0.05f, "follows the grab");

		j.setHeld(false);
		j.getTarget().zero();
		boolean overshoot = false;
		for (float t = 0; t < 3; t += 1 / 60f) {
			j.step(1 / 60f);
			if (j.getOffset().z < -0.01f) overshoot = true;
		}
		assertTrue(overshoot, "an ear is underdamped and springs past rest");
		assertTrue(j.getOffset().length() < 0.01f, "settles back to rest");
		assertTrue(j.isSleeping());
	}

	@Test
	void keepsPinnedShape() {
		SpringJoint j = new SpringJoint(PartType.TAIL.defaultProfile(), 8);
		j.setHeld(true);
		j.getTarget().set(0, 0.6f, 0);
		run(j, 1.5f);
		j.setHeld(false);
		j.pinCurrent();
		run(j, 3);
		assertEquals(j.getPinned().y, j.getOffset().y, 0.01f);
	}

	@Test
	void respectsMaxAngle() {
		SpringJoint j = new SpringJoint(PartType.EAR.defaultProfile(), 4);
		j.setHeld(true);
		j.getTarget().set(10, 0, 0);
		run(j, 2);
		assertTrue(j.getOffset().length() <= j.getProfile().maxAngle() * 1.16f);
	}

	@Test
	void longerPartsSwingSlower() {
		PhysicsProfile p = PartType.TAIL.defaultProfile();
		assertTrue(p.frequencyFor(16) < p.frequencyFor(4));
	}

	@Test
	void stableWithLargeFrameTimes() {
		SpringJoint j = new SpringJoint(PartType.ACCESSORY.defaultProfile(), 1);
		j.setHeld(true);
		j.getTarget().set(0.3f, 0, 0);
		for (int i = 0; i < 50; i++) j.step(0.1f);
		assertTrue(Float.isFinite(j.getOffset().x));
		assertEquals(0.3f, j.getOffset().x, 0.05f);
	}

	@Test
	void rotationBetweenIsShortestArc() {
		Vector3f rv = SpringJoint.rotationBetween(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f());
		assertEquals((float) Math.PI / 2, rv.length(), 1e-4f);
		assertEquals(1f, rv.z / rv.length(), 1e-4f);
	}
}
