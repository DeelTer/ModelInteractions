package dev.deelter.modelinteractions.physics;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Semantic category of a model element. Drives default physics and which
 * interactions are allowed on it.
 */
@Getter
@RequiredArgsConstructor
public enum PartType {
	//        freq  damp  maxDeg grabbable
	EAR(9.0f, 0.35f, 75f, true),
	TAIL(4.5f, 0.25f, 110f, true),
	HAIR(6.0f, 0.45f, 60f, true),
	WING(5.0f, 0.55f, 50f, true),
	/**
	 * Soft body parts (chest, belly): small, fast, barely damped wobble. Never auto-detected.
	 */
	JELLY(11f, 0.15f, 14f, true),
	LIMB(7.0f, 0.80f, 70f, true),
	HEAD(8.0f, 0.90f, 35f, true),
	ACCESSORY(12f, 0.60f, 25f, true),
	STATIC(20f, 1.00f, 0f, false);

	public static final PartType[] VALUES = values();

	/**
	 * Natural angular frequency for a 1-block (16px) long element, rad/s.
	 */
	private final float baseFrequency;
	/**
	 * Damping ratio, 1 = critically damped.
	 */
	private final float dampingRatio;
	private final float maxAngleDeg;
	private final boolean grabbable;

	public PhysicsProfile defaultProfile() {
		return new PhysicsProfile(baseFrequency, dampingRatio, (float) Math.toRadians(maxAngleDeg), grabbable);
	}
}
